#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RelayHub · 内网中继中心 — 管理面板后端
零依赖(仅 Python 标准库),负责:
  1. 面板登录与会话
  2. 聚合 frps Dashboard API,生成设备/代理/流量视图
  3. 采集本机(中继服务器)系统指标
  4. 提供客户端安装脚本与 frpc 二进制分发、远程端口分配
启动: python3 app.py   (设备经 frpc 接入,面板聚合 frps 实时数据)
"""
import base64
import concurrent.futures
import hashlib
import hmac
import json
import os
import random
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STATIC_DIR = ROOT / "static"
# 分发目录: 优先 panel/downloads, 兼容部署布局 /opt/relayhub/downloads
DOWNLOADS_DIR = ROOT / "downloads"
if not DOWNLOADS_DIR.is_dir():
    _alt = ROOT.parent / "downloads"
    if _alt.is_dir():
        DOWNLOADS_DIR = _alt
CONFIG_PATH = ROOT / "config.json"
INSTALLER_PATH = ROOT / "install.sh"
MAPPINGS_PATH = ROOT / "mappings.json"
FRPS_TOML = ROOT.parent / "frps.toml"
if not FRPS_TOML.is_file() and (ROOT / "frps.toml").is_file():
    FRPS_TOML = ROOT / "frps.toml"
PORT_USAGE_PATH = ROOT / "port_usage.json"

SESSION_TTL = 12 * 3600
SAMPLE_INTERVAL = 0.5
PROBE_INTERVAL = 0.5    # 可达性探测周期(独立线程,绝不阻塞采样)
HISTORY_MAX = 1210      # 前端图表展示近 10 分钟(0.5s * 1200 点),多留 10 点余量
STATIC_CACHE_MAX = 8 * 1024 * 1024

DEFAULT_CONFIG = {
    "bind_host": "127.0.0.1",
    "bind_port": 8100,
    "public_addr": "127.0.0.1",
    "panel_base": "http://127.0.0.1:8100",
    "frps_bind_port": 7000,
    "frp_ws_port": 80,     # 设备实际接入端口(经 nginx WebSocket,安全组恒放行)
    "frp_version": "0.71.1",
    "port_range_start": 6000,
    "port_range_end": 6999,
    "remote_port_ranges": [[6000, 6999]],
    "frps": {"host": "127.0.0.1", "port": 7500, "user": "relayhub", "password": ""},
}


# ---------------------------------------------------------------- config
def gen_password(n=20):
    alpha = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alpha) for _ in range(n))


def hash_password(pw, salt):
    return hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt), 60000).hex()


def load_config():
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))  # deep copy
    if CONFIG_PATH.exists():
        cfg.update(json.loads(CONFIG_PATH.read_text("utf-8")))
    changed = False
    if not cfg.get("admin_password"):
        pw = os.environ.get("RELAY_ADMIN_PASSWORD") or gen_password(10)
        cfg["admin_password"] = pw
        changed = True
    if "admin_password_hash" not in cfg:
        salt = secrets.token_hex(16)
        cfg["admin_password_salt"] = salt
        cfg["admin_password_hash"] = hash_password(cfg["admin_password"], salt)
        changed = True
    if not cfg.get("session_secret"):
        cfg["session_secret"] = secrets.token_hex(32)
        changed = True
    f = cfg.setdefault("frps", {})
    if not f.get("password"):
        f["password"] = gen_password(20)
        changed = True
    if not cfg.get("frps_token"):
        cfg["frps_token"] = gen_password(32)
        changed = True
    # ---- 端口池: 无 port_pool 时从旧键迁移, 有则规范化 ----
    _mp = cfg.get("port_pool")
    if isinstance(_mp, dict) and isinstance(_mp.get("ranges"), list) and _mp.get("ranges"):
        _norm = normalize_pool(_mp)
        if _norm != _mp:
            cfg["port_pool"] = _norm
            changed = True
    else:
        cfg["port_pool"] = build_pool_from_legacy(cfg)
        changed = True
    if changed:
        CONFIG_PATH.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), "utf-8")
        try:
            os.chmod(CONFIG_PATH, 0o600)
        except OSError:
            pass
    return cfg


# ------------------------------------------------------- port pool(单一可信源)
DEFAULT_PROTECTED = [22, 80, 443, 7000, 7500, 8100, 13345]


def normalize_pool(p):
    """补齐/规整端口池结构, 只保留已知字段。"""
    p = p if isinstance(p, dict) else {}
    ranges = []
    for i, r in enumerate(p.get("ranges") or []):
        if not isinstance(r, dict):
            continue
        try:
            a, b = int(r.get("start")), int(r.get("end"))
        except (TypeError, ValueError):
            continue
        label = str(r.get("label") or "").strip() or ("段%d" % (i + 1))
        ranges.append({"label": label[:32], "start": a, "end": b})
    exc = []
    for e in p.get("exclude") or []:
        try:
            e = int(e)
        except (TypeError, ValueError):
            continue
        if 1 <= e <= 65535:
            exc.append(e)
    pol = p.get("alloc_policy")
    if pol not in ("sequential", "random", "least_used"):
        pol = "sequential"
    try:
        mpc = int(p.get("max_per_client"))
    except (TypeError, ValueError):
        mpc = 16
    prot_src = p.get("protected") if isinstance(p.get("protected"), list) else DEFAULT_PROTECTED
    prot = []
    for e in prot_src:
        try:
            e = int(e)
        except (TypeError, ValueError):
            continue
        if 1 <= e <= 65535:
            prot.append(e)
    return {
        "ranges": ranges,
        "exclude": sorted(set(exc)),
        "alloc_policy": pol,
        "max_per_client": max(1, min(256, mpc)),
        "sync_ufw": bool(p.get("sync_ufw", True)),
        "protected": sorted(set(prot)) or list(DEFAULT_PROTECTED),
    }


def build_pool_from_legacy(cfg):
    """旧配置(remote_port_ranges / port_range_*)迁移为端口池。"""
    rr = cfg.get("remote_port_ranges")
    if not (isinstance(rr, list) and rr):
        rr = [[cfg.get("port_range_start", 6000), cfg.get("port_range_end", 6999)]]
    ranges, seen = [], set()
    for i, pair in enumerate(rr):
        try:
            a, b = int(pair[0]), int(pair[1])
        except (TypeError, ValueError, IndexError):
            continue
        if a > b:
            a, b = b, a
        if not (1 <= a <= 65535 and 1 <= b <= 65535) or (a, b) in seen:
            continue
        seen.add((a, b))
        ranges.append({"label": "段%d" % (i + 1), "start": a, "end": b})
    return normalize_pool({"ranges": ranges})


def pool_raw_ranges(pool=None):
    src = pool if isinstance(pool, dict) else (CFG.get("port_pool") or {})
    out = []
    for r in (src.get("ranges") or []):
        try:
            a, b = int(r["start"]), int(r["end"])
        except (KeyError, TypeError, ValueError):
            continue
        if a <= b and 1 <= a <= 65535 and 1 <= b <= 65535:
            out.append((a, b))
    return sorted(out)


def pool_exclude(pool=None):
    src = pool if isinstance(pool, dict) else (CFG.get("port_pool") or {})
    out = set()
    for e in (src.get("exclude") or []):
        try:
            e = int(e)
        except (TypeError, ValueError):
            continue
        if 1 <= e <= 65535:
            out.add(e)
    return out


def effective_port_ranges(pool=None):
    """端口段扣除 exclude 后的有效范围, 相邻段自动合并。"""
    exc = sorted(pool_exclude(pool))
    out = []
    for a, b in pool_raw_ranges(pool):
        cur = a
        for e in exc:
            if e < cur:
                continue
            if e > b:
                break
            if cur <= e - 1:
                out.append((cur, e - 1))
            cur = e + 1
        if cur <= b:
            out.append((cur, b))
    merged = []
    for a, b in sorted(out):
        if merged and a <= merged[-1][1] + 1:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b))
        else:
            merged.append((a, b))
    return merged


def guide_port_range():
    """接入指南示例用: 取容量最大的有效段。"""
    eff = allowed_remote_ranges()
    if not eff:
        return [CFG.get("port_range_start", 6000), CFG.get("port_range_end", 6999)]
    a, b = max(eff, key=lambda r: r[1] - r[0])
    return [a, b]


def max_per_client():
    try:
        return int((CFG.get("port_pool") or {}).get("max_per_client") or 16)
    except (TypeError, ValueError):
        return 16


_usage_lock = threading.Lock()


def _load_port_usage():
    try:
        data = json.loads(PORT_USAGE_PATH.read_text("utf-8"))
        return {int(k): int(v) for k, v in data.items()}
    except Exception:
        return {}


def _bump_port_usage(p):
    with _usage_lock:
        _port_usage[p] = _port_usage.get(p, 0) + 1
        try:
            PORT_USAGE_PATH.write_text(json.dumps(_port_usage, ensure_ascii=False), "utf-8")
        except OSError:
            pass


def alloc_candidates():
    """按池的分配策略生成候选公网端口序列(已剔除 exclude)。"""
    pol = (CFG.get("port_pool") or {}).get("alloc_policy", "sequential")
    ports = [p for a, b in allowed_remote_ranges() for p in range(a, b + 1)]
    if pol == "random":
        random.shuffle(ports)
    elif pol == "least_used":
        with _usage_lock:
            ports.sort(key=lambda p: (_port_usage.get(p, 0), p))
    return ports


_port_usage = _load_port_usage()


CFG = load_config()


# ---------------------------------------------------------------- frps collector
class Collector(threading.Thread):
    """轮询 frps Dashboard API 与本机指标,维护面板数据视图与流量历史。"""

    DEV_HIST_MAX = 1210  # 每设备速率历史(0.5s * 1200 = 10 分钟)
    PROBE_HIST_MAX = 12  # 每代理探测历史

    def __init__(self):
        super().__init__(daemon=True)
        self.lock = threading.Lock()
        self.state = {
            "frps_ok": False, "frps_error": "", "server": {},
            "proxies": [], "history": [],
            "probes": {}, "health": {},
        }
        self._last_totals = None        # 上一样本 (t, in, out)
        self._last_totals2 = None       # 上上样本(10 秒窗口,消除探测节奏锯齿)
        self._last_dev_traffic = None   # {dev: [in,out]}
        self._last_dev_traffic2 = None
        self._dev_hist = {}             # {dev: deque}
        self._probe_hist = {}           # {proxy: deque}
        self._sample_count = 0
        self.summary_json = ""          # 预序列化缓存,请求零拷贝
        self.summary_etag = ""          # 内容指纹,配合 304 减少传输
        self._probe_pool = concurrent.futures.ThreadPoolExecutor(max_workers=8)

    # ---- 可达性主动探测:TCP连通 + SSH横幅/HTTP状态识别 ----
    def _probe_one(self, port, proxy_name):
        t0 = time.time()
        try:
            s = socket.create_connection(("127.0.0.1", port), timeout=2.5)
            ms = (time.time() - t0) * 1000
            detail = "TCP 端口可达"
            s.settimeout(3)
            if proxy_name.endswith(".ssh"):
                try:
                    data = s.recv(48)
                    if data.startswith(b"SSH-"):
                        detail = data.decode("utf-8", "replace").strip()[:32]
                except Exception:
                    pass
            elif proxy_name.endswith(".web"):
                try:
                    s.sendall(b"GET / HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n")
                    data = s.recv(128)
                    if data.startswith(b"HTTP/"):
                        parts = data.split(b" ", 2)
                        detail = "HTTP " + (parts[1] + b" " + parts[2][:14].rstrip()).decode("utf-8", "replace") if len(parts) > 2 else "HTTP 响应"
                except Exception:
                    pass
            try:
                s.close()
            except Exception:
                pass
            return {"ok": True, "ms": round(ms, 1), "detail": detail}
        except Exception as e:
            return {"ok": False, "ms": None, "detail": type(e).__name__}

    @staticmethod
    def _proxy_port(p):
        conf = p.get("conf") or {}
        rp = conf.get("remotePort")
        if rp:
            try:
                return int(rp)
            except (TypeError, ValueError):
                pass
        ra = str(p.get("remoteAddr") or "")
        if ":" in ra:
            try:
                return int(ra.rsplit(":", 1)[1])
            except ValueError:
                pass
        return None

    def _probe_all(self, proxies):
        import concurrent.futures
        results = {}
        targets = []
        for p in proxies:
            port = self._proxy_port(p)
            if p.get("_proto") == "tcp" and port and 1 <= port <= 65535 and port not in (80, 22, 7000, 7500, 8100):
                targets.append((port, str(p.get("name", "?"))))
        # 常驻线程池复用,不再每轮新建
        futs = {self._probe_pool.submit(self._probe_one, port, name): name for port, name in targets}
        for fut, name in futs.items():
            try:
                results[name] = fut.result(timeout=6)
            except Exception as e:
                results[name] = {"ok": False, "ms": None, "detail": type(e).__name__}
        now = int(time.time())
        for name, r in results.items():
            r["t"] = now
            q = self._probe_hist.setdefault(name, [])
            q.append(r["ms"])
            del q[:-self.PROBE_HIST_MAX]
            r["hist"] = list(q)
        # 清理已不存在的代理探测历史,防止设备更替后字典无限增长
        live_names = {str(p.get("name", "?")) for p in proxies}
        for name in [n for n in self._probe_hist if n not in live_names]:
            del self._probe_hist[name]
        return results

    def _health_checks(self):
        h = {"frps_api": False, "tcp_7000": False, "nginx_80": False}
        try:
            s = socket.create_connection(("127.0.0.1", 7000), timeout=1.5); s.close()
            h["tcp_7000"] = True
        except Exception:
            pass
        try:
            s = socket.create_connection(("127.0.0.1", 80), timeout=1.5); s.close()
            h["nginx_80"] = True
        except Exception:
            pass
        h["frps_api"] = bool(self.state.get("frps_ok"))
        return h

    # ---- frps dashboard api ----
    def _frps_get(self, path):
        req = urllib.request.Request(
            "http://%s:%s%s" % (CFG["frps"]["host"], CFG["frps"]["port"], path))
        token = ("%s:%s" % (CFG["frps"]["user"], CFG["frps"]["password"])).encode()
        req.add_header("Authorization", "Basic " + base64.b64encode(token).decode())
        with urllib.request.urlopen(req, timeout=4) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def _collect_real(self):
        info = self._frps_get("/api/serverinfo")
        proxies = []
        # 只抓实际存在代理的类型(serverinfo 的 proxyTypeCount),空类型不再请求
        counts = info.get("proxyTypeCount") or {}
        if not counts:
            counts = {"tcp": 1}
        for ptype in ("tcp", "udp", "http", "https", "stcp", "xtcp", "sudp"):
            if not counts.get(ptype):
                continue
            try:
                r = self._frps_get("/api/proxy/" + ptype)
                for p in r.get("proxies", []):
                    p["_proto"] = ptype
                    proxies.append(p)
            except Exception:
                pass
        return info, proxies

    # ---- local system metrics ----
    def _sys_stats(self):
        st = {"cpu": None, "mem_percent": None, "mem_total_mb": None,
              "disk_percent": None, "net_rates": [0, 0], "load1": None, "uptime_s": None}
        try:
            with open("/proc/stat") as f:
                parts = [float(x) for x in f.readline().split()[1:]]
            idle = parts[3] + (parts[4] if len(parts) > 4 else 0)
            total = sum(parts)
            key = (idle, total)
            if getattr(self, "_cpu_prev", None):
                pi, pt = self._cpu_prev
                di, dt = idle - pi, total - pt
                if dt > 0:
                    st["cpu"] = round(max(0.0, min(100.0, (1 - di / dt) * 100)), 1)
            self._cpu_prev = key
        except Exception:
            pass
        try:
            with open("/proc/meminfo") as f:
                mi = {}
                for line in f:
                    k, v = line.split(":", 1)
                    mi[k] = int(v.strip().split()[0]) * 1024
            st["mem_total_mb"] = round(mi["MemTotal"] / 1048576)
            st["mem_percent"] = round((mi["MemTotal"] - mi["MemAvailable"]) / mi["MemTotal"] * 100, 1)
        except Exception:
            pass
        try:
            usage = shutil.disk_usage("/")
            st["disk_percent"] = round(usage.used / usage.total * 100, 1)
        except Exception:
            pass
        try:
            with open("/proc/loadavg") as f:
                st["load1"] = round(float(f.read().split()[0]), 2)
        except Exception:
            pass
        try:
            with open("/proc/uptime") as f:
                st["uptime_s"] = int(float(f.read().split()[0]))
        except Exception:
            pass
        try:
            rates = [0, 0]
            for iface in ("/proc/net/dev",):
                with open(iface) as f:
                    for line in f.readlines()[2:]:
                        if_ = line.split(":")[0].strip()
                        if if_ in ("lo",):
                            continue
                        cols = line.split(":")[1].split()
                        rx, tx = int(cols[0]), int(cols[8])
                        rates[0] += rx; rates[1] += tx
            key = (rates[0], rates[1], time.time())
            if getattr(self, "_net_prev", None):
                prx, ptx, pt = self._net_prev
                dt = max(0.2, key[2] - pt)
                st["net_rates"] = [round((rates[0] - prx) / dt), round((rates[1] - ptx) / dt)]
            self._net_prev = key
        except Exception:
            pass
        return st

    def _rebuild_summary(self):
        """预序列化 summary 供 API 直接复用;须在 self.lock 之外调用
        (build_summary 内部会自行取锁,非可重入锁)。"""
        try:
            self.summary_json = json.dumps(build_summary(), ensure_ascii=False,
                                            separators=(",", ":"))
            self.summary_etag = '"%s"' % hashlib.md5(
                self.summary_json.encode("utf-8")).hexdigest()
        except Exception:
            pass

    def probe_loop(self):
        """可达性探测独立线程: 探测最长可阻塞 6s,绝不能拖慢 500ms 采样循环。"""
        while True:
            try:
                with self.lock:
                    proxies = list(self.state.get("proxies", []))
                probes = self._probe_all(proxies) if proxies else {}
                health = self._health_checks()
                with self.lock:
                    self.state["probes"] = probes
                    self.state["health"] = health
                self._rebuild_summary()
            except Exception:
                pass
            time.sleep(PROBE_INTERVAL)

    def run(self):
        while True:
            try:
                info, proxies = self._collect_real()
                now = time.time()
                tin = info.get("totalTrafficIn", 0)
                tout = info.get("totalTrafficOut", 0)
                conns = info.get("curConns", 0)
                # 速率用 1 秒窗口(上上两个样本差值,0.5s 采样下足够平滑)
                in_bw = out_bw = 0
                if self._last_totals2:
                    lt, li, lo = self._last_totals2
                    dt = max(1.0, now - lt)
                    in_bw = max(0, (tin - li) / dt)
                    out_bw = max(0, (tout - lo) / dt)
                self._last_totals2 = self._last_totals
                self._last_totals = (now, tin, tout)
                hist_entry = {"t": int(now), "in": round(in_bw), "out": round(out_bw),
                              "conns": conns, "cpu": None}

                # ---- 按设备聚合流量与实时速率(同样 1 秒窗口) ----
                dev_traffic = {}
                for p in proxies:
                    dev = str(p.get("name", "?")).split(".", 1)[0]
                    agg = dev_traffic.setdefault(dev, [0, 0])
                    agg[0] += p.get("todayTrafficIn", 0) or 0
                    agg[1] += p.get("todayTrafficOut", 0) or 0
                dev_rates = {}
                if self._last_dev_traffic2:
                    lt, prev = self._last_dev_traffic2
                    dt = max(1.0, now - lt)
                    for dev, agg in dev_traffic.items():
                        if dev in prev:
                            dev_rates[dev] = [
                                max(0, round((agg[0] - prev[dev][0]) / dt)),
                                max(0, round((agg[1] - prev[dev][1]) / dt)),
                            ]
                self._last_dev_traffic2 = self._last_dev_traffic
                self._last_dev_traffic = (now, dev_traffic)
                for dev in dev_traffic:
                    q = self._dev_hist.setdefault(dev, [])
                    r = dev_rates.get(dev, [0, 0])
                    q.append({"t": int(now), "in": r[0], "out": r[1]})
                    del q[:-self.DEV_HIST_MAX]
                # 清理已下线且代理全部消失的设备历史,防止设备更替后字典无限增长
                for dev in [d for d in self._dev_hist if d not in dev_traffic]:
                    del self._dev_hist[dev]
                dev_hist_out = {dev: list(q) for dev, q in self._dev_hist.items()}

                # 可达性探测/通道健康已迁至 probe_loop 线程,采样循环保持 500ms 不被打断
                sysst = self._sys_stats()
                hist_entry["cpu"] = sysst["cpu"]
                with self.lock:
                    self.state.update({
                        "frps_ok": True, "frps_error": "",
                        "server": {"frps": info, "sys": sysst},
                        "proxies": proxies,
                        "dev_rates": dev_rates, "dev_hist": dev_hist_out,
                    })
                    self.state["history"].append(hist_entry)
                    del self.state["history"][:-HISTORY_MAX]
                # 预序列化缓存:API 请求直接复用,避免每请求深拷贝
                self._rebuild_summary()
            except Exception as e:
                with self.lock:
                    self.state["frps_ok"] = False
                    self.state["frps_error"] = str(e)[:200]
            time.sleep(SAMPLE_INTERVAL)


COLLECTOR = Collector()
COLLECTOR.start()
threading.Thread(target=COLLECTOR.probe_loop, daemon=True,
                 name="relay-probe").start()

# 远程端口分配(供安装脚本调用): 已占用 + 短时预留(带每 IP 配额)
_port_reservations = {}   # port -> expiry ts
_reservations_by_ip = {}  # ip -> {port: expiry ts}
_reservations_lock = threading.Lock()


# ---------------- device multi-port relay mappings ----------------
# 每台设备可声明多条 本地端口 -> 公网端口 映射;面板保存期望态,
# 并据此生成设备端 frpc.toml 与一键下发命令(在客户端执行)。
MAPPINGS_PATH = ROOT / "mappings.json"
_mappings = {}                      # device -> [mapping, ...]
_mappings_lock = threading.Lock()


def _load_mappings():
    try:
        if MAPPINGS_PATH.exists():
            data = json.loads(MAPPINGS_PATH.read_text("utf-8"))
            if isinstance(data, dict):
                return {str(k): list(v) for k, v in data.items() if isinstance(v, list)}
    except Exception:
        pass
    return {}


def _persist_mappings():
    tmp = MAPPINGS_PATH.with_name(MAPPINGS_PATH.name + ".tmp")
    tmp.write_text(json.dumps(_mappings, ensure_ascii=False, indent=2), "utf-8")
    tmp.replace(MAPPINGS_PATH)


def allowed_remote_ranges():
    """公网端口可用范围 —— 单一可信源为 config.json 的 port_pool(已剔除 exclude)。"""
    pool = CFG.get("port_pool")
    if isinstance(pool, dict) and pool.get("ranges"):
        eff = effective_port_ranges()
        if eff:
            return eff
    rr = CFG.get("remote_port_ranges")   # 兼容回退(旧配置)
    if isinstance(rr, list) and rr:
        try:
            return [(int(a), int(b)) for a, b in rr]
        except (TypeError, ValueError):
            pass
    return [(int(CFG["port_range_start"]), int(CFG["port_range_end"]))]


def remote_port_allowed(p):
    return any(a <= p <= b for a, b in allowed_remote_ranges())


def ranges_text():
    return ", ".join("%d-%d" % (a, b) if a != b else str(a)
                     for a, b in allowed_remote_ranges())


def desired_remote_ports(exclude_device=None):
    """已写入配置但可能尚未在 frps 生效的公网端口,避免自动分配撞车。"""
    out = set()
    with _mappings_lock:
        for dev, ms in _mappings.items():
            if dev == exclude_device:
                continue
            for m in ms:
                try:
                    out.add(int(m.get("remotePort")))
                except (TypeError, ValueError):
                    pass
    return out


def _live_ports_by_owner():
    """frps 现有代理: {remotePort: ownerDevice}(代理名形如 <user>.<proxy>)。"""
    out = {}
    with COLLECTOR.lock:
        for p in COLLECTOR.state.get("proxies", []):
            rp = COLLECTOR._proxy_port(p)
            if not rp:
                continue
            nm = str(p.get("name") or "")
            out[rp] = nm.split(".", 1)[0] if "." in nm else nm
    return out


def validate_mappings(device, mappings):
    """返回 (错误列表, 清洗后的映射列表)。清洗后列表为空表示清除该设备配置。"""
    if not isinstance(device, str) or not re.match(r"^[a-z0-9][a-z0-9-]{0,30}$", device):
        return ["设备名仅允许小写字母、数字与 -,且以字母或数字开头"], []
    if not isinstance(mappings, list):
        return ["端口映射必须是数组"], []

    errs = []
    _mpc = max_per_client()
    if len(mappings) > _mpc:
        errs.append("单设备最多 %d 个代理(frps maxPortsPerClient = %d)" % (_mpc, _mpc))

    live = _live_ports_by_owner()
    other_desired = {}
    with _mappings_lock:
        for dev, ms in _mappings.items():
            if dev == device:
                continue
            for m in ms:
                try:
                    other_desired[int(m.get("remotePort"))] = dev
                except (TypeError, ValueError):
                    pass

    out, seen_l, seen_n, seen_r = [], set(), set(), set()
    for i, m in enumerate(mappings):
        tag = "第 %d 条" % (i + 1)
        if not isinstance(m, dict):
            errs.append("%s: 格式不正确" % tag)
            continue
        try:
            lp = int(m.get("localPort"))
        except (TypeError, ValueError):
            errs.append("%s: 本地端口必须是数字" % tag)
            continue
        try:
            rp = int(m.get("remotePort"))
        except (TypeError, ValueError):
            errs.append("%s: 公网端口必须是数字" % tag)
            continue
        if not (1 <= lp <= 65535):
            errs.append("%s: 本地端口需在 1-65535" % tag)
            continue
        if not remote_port_allowed(rp):
            errs.append("%s: 公网端口 %d 不在端口池内 %s" % (tag, rp, ranges_text()))
            continue
        if rp in seen_r:
            errs.append("%s: 公网端口 %d 在本设备内重复" % (tag, rp))
            continue
        owner = live.get(rp)
        if owner and owner != device:
            errs.append("%s: 公网端口 %d 已被设备「%s」占用" % (tag, rp, owner))
            continue
        owner = other_desired.get(rp)
        if owner:
            errs.append("%s: 公网端口 %d 已被设备「%s」的配置占用" % (tag, rp, owner))
            continue
        if lp in seen_l:
            errs.append("%s: 本地端口 %d 在本设备内重复" % (tag, lp))
            continue
        typ = str(m.get("type") or "tcp").lower()
        if typ not in ("tcp", "udp"):
            errs.append("%s: 协议仅支持 tcp / udp" % tag)
            continue
        name = str(m.get("name") or ("port-%d" % lp)).strip()
        if not re.match(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{0,31}$", name):
            errs.append("%s: 代理名需以字母/数字开头,最多 32 位" % tag)
            continue
        if name in seen_n:
            errs.append("%s: 代理名「%s」重复" % (tag, name))
            continue
        seen_l.add(lp)
        seen_n.add(name)
        seen_r.add(rp)
        out.append({
            "name": name, "type": typ,
            "localIP": str(m.get("localIP") or "127.0.0.1")[:64],
            "localPort": lp, "remotePort": rp,
            "note": str(m.get("note") or "")[:60],
        })
    return errs, out


def generate_frpc_config(device, mappings):
    if not mappings:
        return ""                     # 无映射 => 不生成可下发配置(避免清空隧道)
    lines = [
        "# RelayHub client config (由面板生成, 设备: %s)" % device,
        'serverAddr = "%s"' % CFG["public_addr"],
        "serverPort = %d" % int(CFG.get("frp_ws_port", 80)),
        'user = "%s"' % device,
        'auth.token = "%s"' % CFG.get("frps_token", ""),
        "loginFailExit = false",
        "",
        "# 80 端口复用 nginx, 必须以 WebSocket 接入",
        'transport.protocol = "websocket"',
        "",
        'log.to = "/var/log/relay-client.log"',
        'log.level = "info"',
        "log.maxDays = 3",
    ]
    for m in mappings:
        lines += [
            "",
            "[[proxies]]",
            'name = "%s"' % m["name"],
            'type = "%s"' % m["type"],
            'localIP = "%s"' % m["localIP"],
            "localPort = %d" % m["localPort"],
            "remotePort = %d" % m["remotePort"],
        ]
    return "\n".join(lines) + "\n"


def frpc_apply_command(config_text):
    """自包含的一行下发命令: 无需面板再提供任何下载端点。"""
    b64 = base64.b64encode(config_text.encode("utf-8")).decode("ascii")
    return ("echo '%s' | base64 -d | sudo tee /etc/frp/frpc.toml >/dev/null && "
            "sudo systemctl restart relay-client" % b64)


def _local_port_hint(device, sub):
    """frps 不回报 conf.localPort; 先查本面板期望态, 再按 install.sh 命名习惯推断。"""
    with _mappings_lock:
        for m in _mappings.get(device) or []:
            if isinstance(m, dict) and str(m.get("name")) == sub:
                try:
                    return int(m.get("localPort"))
                except (TypeError, ValueError):
                    pass
    if sub == "ssh":
        return 22
    if sub.startswith("port-"):
        try:
            return int(sub[5:])
        except ValueError:
            pass
    return None


def ssh_drop_warning(device, mappings):
    """新配置不再暴露 22 端口、而该设备当前确有 SSH 暴露时给出显式警告。"""
    try:
        keeps_ssh = any(int(m.get("localPort")) == 22 for m in mappings)
    except (TypeError, ValueError):
        keeps_ssh = True          # 数据异常时不误报
    if keeps_ssh:
        return ""
    reason = ""
    with _mappings_lock:
        prev = list(_mappings.get(device) or [])
    try:
        if any(int(m.get("localPort")) == 22 for m in prev if isinstance(m, dict)):
            reason = "上一份配置包含 22 端口映射"
    except (TypeError, ValueError):
        pass
    if not reason:
        with COLLECTOR.lock:
            for p in COLLECTOR.state.get("proxies", []):
                nm = str(p.get("name") or "")
                owner = nm.split(".", 1)[0] if "." in nm else nm
                if owner != device:
                    continue
                sub = nm.split(".", 1)[-1] if "." in nm else nm
                if _local_port_hint(device, sub) == 22:
                    reason = "现网代理「%s」当前暴露 22 端口" % sub
                    break
    if not reason:
        return ""
    return ("注意: %s。新配置不含 22 端口映射, 下发后将无法再经中继 SSH 登录该设备。" % reason)


_mappings.update(_load_mappings())


def used_remote_ports():
    used = set()
    with COLLECTOR.lock:
        for p in COLLECTOR.state["proxies"]:
            conf = p.get("conf") or {}
            rp = conf.get("remotePort")
            if rp:
                try:
                    used.add(int(rp))
                    continue
                except (TypeError, ValueError):
                    pass
            ra = str(p.get("remoteAddr") or "")
            if ":" in ra:
                try:
                    used.add(int(ra.rsplit(":", 1)[1]))
                except ValueError:
                    pass
    return used


def alloc_port(ip=""):
    now = time.time()
    with _reservations_lock:
        for p in [p for p, exp in _port_reservations.items() if exp < now]:
            del _port_reservations[p]
        for a in list(_reservations_by_ip):
            _reservations_by_ip[a] = {k: v for k, v in _reservations_by_ip[a].items() if v > now}
            if not _reservations_by_ip[a]:
                del _reservations_by_ip[a]
    used = used_remote_ports() | set(_port_reservations) | desired_remote_ports()
    for p in alloc_candidates():
        if p not in used:
            with _reservations_lock:
                _port_reservations[p] = now + 300
                _reservations_by_ip.setdefault(ip, {})[p] = now + 300
            _bump_port_usage(p)
            return p
    return None



# ------------------------------------------------------------- port pool 生效
_pool_lock = threading.Lock()        # 应用端口池期间持有, 防并发应用
_UFW_POOL_CMT = re.compile(r"relayhub-pool|frp proxy ports|frp relay port")


def _ufw(args, timeout=25):
    try:
        r = subprocess.run(["ufw"] + [str(a) for a in args],
                           capture_output=True, text=True, timeout=timeout)
        return r.returncode, ((r.stdout or "") + (r.stderr or "")).strip()
    except Exception as e:
        return 1, str(e)


def ufw_pool_rules():
    """编号列出与端口池相关的 ufw 规则(按注释标记识别)。"""
    _, out = _ufw(["status", "numbered"])
    rules = []
    for line in out.splitlines():
        m = re.match(r"\s*\[\s*(\d+)\]\s+(.*?)\s+(ALLOW IN|DENY IN|REJECT IN)\s+(.*?)"
                     r"(?:\s+#\s*(.*))?\s*$", line)
        if not m:
            continue
        spec = m.group(2).strip()
        v6 = spec.endswith("(v6)")
        if v6:
            spec = spec[:-4].strip()
        rules.append({"num": int(m.group(1)), "spec": spec, "v6": v6,
                      "action": m.group(3), "comment": (m.group(5) or "").strip()})
    return rules


def _pool_ufw_rules():
    return [r for r in ufw_pool_rules() if _UFW_POOL_CMT.search(r["comment"])]


def ufw_sync(eff):
    """回收旧端口池规则(按注释标记识别), 再按新池重建。返回 (removed, added, errs)。"""
    removed, added, errs = [], [], []
    seen = set()
    for r in sorted(_pool_ufw_rules(), key=lambda x: -x["num"]):
        rc, out = _ufw(["--force", "delete", str(r["num"])])
        if rc == 0:
            if r["spec"] not in seen:
                seen.add(r["spec"])
                removed.append(r["spec"] + (("  # " + r["comment"]) if r["comment"] else ""))
        else:
            errs.append("删除旧规则 %s 失败: %s" % (r["spec"], (out.splitlines() or [""])[-1]))
            break                      # 编号已变化, 停止删除避免误删
    for a, b in eff:
        spec = "%d/tcp" % a if a == b else "%d:%d/tcp" % (a, b)
        rc, out = _ufw(["allow", spec, "comment", "relayhub-pool"])
        if rc == 0:
            added.append(spec)
        else:
            errs.append("放行 %s 失败: %s" % (spec, (out.splitlines() or [""])[-1]))
    return removed, added, errs


def read_frps_allowports():
    try:
        txt = FRPS_TOML.read_text("utf-8")
    except OSError:
        return ""
    m = re.search(r"(?m)^allowPorts\s*=\s*.+$", txt)
    return m.group(0).strip() if m else ""


def frps_allowports_value(eff):
    return "[" + ", ".join("{ start = %d, end = %d }" % (a, b) for a, b in eff) + "]"


def rewrite_frps_toml(eff, max_ports):
    """只改写 allowPorts 与 maxPortsPerClient 两行, token/DASHPW 等其余内容原样保留。"""
    txt = FRPS_TOML.read_text("utf-8")
    before = read_frps_allowports() or "(原文件无 allowPorts)"
    line = "allowPorts = " + frps_allowports_value(eff)
    if re.search(r"(?m)^allowPorts\s*=", txt):
        txt = re.sub(r"(?m)^allowPorts\s*=.*$", lambda _m: line, txt, count=1)
    else:
        txt = txt.rstrip("\n") + "\n\n# 允许客户端申请的公网端口段(由端口池派生)\n" + line + "\n"
    mp = str(int(max_ports))
    if re.search(r"(?m)^maxPortsPerClient\s*=", txt):
        txt = re.sub(r"(?m)^maxPortsPerClient\s*=.*$",
                     lambda _m: "maxPortsPerClient = %s  # 由端口池 max_per_client 派生" % mp,
                     txt, count=1)
    else:
        txt = txt.rstrip("\n") + "\nmaxPortsPerClient = %s\n" % mp + "\n"
    if "auth.token" not in txt or "webServer.password" not in txt:
        raise RuntimeError("frps.toml 内容异常, 拒绝改写(缺少 token/dashboard)")
    FRPS_TOML.write_text(txt, "utf-8")
    return before, line


def pool_conflicts(eff):
    """期望态映射中落在新池之外的端口 —— 必须先改映射才能应用。"""
    out = []
    with _mappings_lock:
        for dev, ms in _mappings.items():
            for m in ms:
                try:
                    rp = int(m.get("remotePort"))
                except (TypeError, ValueError):
                    continue
                if not any(a <= rp <= b for a, b in eff):
                    out.append({"device": dev, "name": str(m.get("name") or "?"), "port": rp})
    return out


def tunnels_at_risk(eff):
    """现网隧道中, 新 allowPorts 生效后将被拒的端口。"""
    out = []
    with COLLECTOR.lock:
        proxies = list(COLLECTOR.state.get("proxies", []))
    for p in proxies:
        rp = COLLECTOR._proxy_port(p)
        try:
            rp = int(rp)
        except (TypeError, ValueError):
            continue
        if not any(a <= rp <= b for a, b in eff):
            out.append({"name": str(p.get("name") or "?"), "port": rp,
                        "status": str(p.get("status") or "")})
    return out


def validate_pool(pool_raw):
    """结构校验: 端口段/重叠/保护端口/排除端口/策略/上限。"""
    p = normalize_pool(pool_raw)
    errs = []
    rng = p["ranges"]
    if not rng:
        errs.append("端口池至少需要一个端口段")
    if len(rng) > 32:
        errs.append("端口段最多 32 个")
    iv = []
    for r in rng:
        a, b = r["start"], r["end"]
        if not (1 <= a <= 65535 and 1 <= b <= 65535):
            errs.append("端口段「%s」超出 1-65535" % r["label"])
            continue
        if a > b:
            errs.append("端口段「%s」起止颠倒(%d > %d)" % (r["label"], a, b))
            continue
        iv.append((a, b, r["label"]))
    iv.sort()
    for i in range(1, len(iv)):
        if iv[i][0] <= iv[i - 1][1]:
            errs.append("端口段「%s」与「%s」重叠(%d-%d)"
                        % (iv[i][2], iv[i - 1][2], iv[i][0], min(iv[i][1], iv[i - 1][1])))
    for a, b, label in iv:
        hits = [x for x in p["protected"] if a <= x <= b]
        if hits:
            errs.append("端口段「%s」(%d-%d)覆盖了保护端口 %s"
                        % (label, a, b, ", ".join(str(x) for x in sorted(hits))))
    for e in p["exclude"]:
        if iv and not any(a <= e <= b for a, b, _ in iv):
            errs.append("排除端口 %d 不在任何端口段内" % e)
    if not errs and not effective_port_ranges(p):
        errs.append("剔除排除端口后端口池为空")
    return errs


def _eff_text(eff):
    return ", ".join("%d-%d" % (a, b) if a != b else str(a) for a, b in eff)


def pool_preview(pool_raw):
    """不落盘的变更预览: 校验 + frps/ufw/冲突 diff。"""
    pool = normalize_pool(pool_raw)
    errs = validate_pool(pool)
    eff_new = effective_port_ranges(pool)
    eff_old = allowed_remote_ranges()
    conflicts = pool_conflicts(eff_new) if not errs else []
    for c in conflicts:
        errs.append("公网端口 %d 不在新池内(设备 %s 的映射「%s」), 请先修改该映射"
                    % (c["port"], c["device"], c["name"]))
    risk = tunnels_at_risk(eff_new) if not errs else []
    add_specs = ["%d/tcp" % a if a == b else "%d:%d/tcp" % (a, b) for a, b in eff_new]
    cur_specs = []
    for r in _pool_ufw_rules():
        if r["spec"] not in cur_specs:
            cur_specs.append(r["spec"])
    return {
        "ok": not errs,
        "errors": errs,
        "pool": pool,
        "diff": {
            "before": {"text": _eff_text(eff_old),
                       "count": sum(b - a + 1 for a, b in eff_old),
                       "frps": read_frps_allowports() or "(无)"},
            "after": {"text": _eff_text(eff_new),
                      "count": sum(b - a + 1 for a, b in eff_new),
                      "frps": "allowPorts = " + frps_allowports_value(eff_new),
                      "max_per_client": pool.get("max_per_client")},
            "ufw_remove": [s for s in cur_specs if s not in add_specs],
            "ufw_add": [s for s in add_specs if s not in cur_specs],
            "blocked": conflicts,
            "tunnels_at_risk": risk,
        },
    }


def _probe_tcp(host, port, timeout=2.5):
    t0 = time.time()

    def ms():
        return int((time.time() - t0) * 1000)
    try:
        s = socket.create_connection((host, port), timeout)
        s.close()
        return {"port": port, "state": "open", "ms": ms(), "note": "已放行, 隧道在线"}
    except ConnectionRefusedError:
        return {"port": port, "state": "refused", "ms": ms(),
                "note": "可路由、无监听 —— 安全组与 ufw 已放行"}
    except (socket.timeout, TimeoutError):
        return {"port": port, "state": "blocked", "ms": ms(),
                "note": "超时 —— 被云安全组或 ufw 拦截"}
    except OSError as e:
        if getattr(e, "errno", None) in (111, 10061):
            return {"port": port, "state": "refused", "ms": ms(),
                    "note": "可路由、无监听 —— 安全组与 ufw 已放行"}
        return {"port": port, "state": "blocked", "ms": ms(), "note": "不可达(%s)" % e}


def handle_pool_probe(body):
    """从公网 IP 反打自检: 分类 open / refused / blocked。"""
    host = str(body.get("host") or CFG.get("public_addr") or "").strip()
    if not re.match(r"^[A-Za-z0-9.:\-]{1,100}$", host):
        return {"ok": False, "error": "公网探测目标不合法: %s" % host}
    eff = allowed_remote_ranges()
    samples = sorted(desired_remote_ports() | used_remote_ports())
    for a, b in eff:
        samples.extend([a, b])
        if b - a >= 2:
            samples.append((a + b) // 2)
    seen, ports = set(), []
    for p in samples:
        if p not in seen:
            seen.add(p)
            ports.append(p)
    ports = ports[:14]
    timeout = max(0.5, min(8.0, float(body.get("timeout") or 2.5)))
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        results = list(ex.map(lambda p: _probe_tcp(host, p, timeout), ports))
    blocked = [r for r in results if r["state"] == "blocked"]
    n_open = sum(1 for r in results if r["state"] == "open")
    n_ref = sum(1 for r in results if r["state"] == "refused")
    return {
        "ok": True, "host": host, "took_ms": int((time.time() - t0) * 1000),
        "results": results,
        "summary": {"open": n_open, "refused": n_ref, "blocked": len(blocked)},
        "covered": not blocked,
        "loopback": host in ("127.0.0.1", "localhost", "::1"),
        "note": ("安全组/防火墙已覆盖所测端口" if not blocked
                 else "以下端口超时, 需在云控制台安全组放行: %s"
                 % ", ".join(str(r["port"]) for r in blocked)),
    }


def port_pool_status():
    pool = json.loads(json.dumps(CFG.get("port_pool") or {}))
    eff = allowed_remote_ranges()
    with _usage_lock:
        usage = sorted(_port_usage.items(), key=lambda kv: -kv[1])[:20]
    return {
        "pool": pool,
        "effective": [[a, b] for a, b in eff],
        "ranges_text": ranges_text(),
        "port_count": sum(b - a + 1 for a, b in eff),
        "protected": pool.get("protected") or list(DEFAULT_PROTECTED),
        "used": sorted(desired_remote_ports() | used_remote_ports()),
        "usage_top": [{"port": p, "count": n} for p, n in usage],
        "conflicts": pool_conflicts(eff),
        "ufw_rules": _pool_ufw_rules(),
        "frps_allowports": read_frps_allowports(),
        "max_ports_per_client_frps": max_per_client(),
        "public_addr": CFG.get("public_addr"),
        "busy": _pool_lock.locked(),
    }


def apply_port_pool(pool_raw):
    """备份 → 写 config → 就地重载 → 改写 frps.toml → 同步 ufw → 重启 frps。
    绝不重启面板自身; 异常时自动从备份回滚。"""
    pool = normalize_pool(pool_raw)
    errs = validate_pool(pool)
    if errs:
        return 400, {"ok": False, "errors": errs}
    eff = effective_port_ranges(pool)
    conflicts = pool_conflicts(eff)
    if conflicts:
        return 409, {"ok": False,
                     "errors": ["新池与现有端口映射冲突, 请先在映射编辑器调整: "
                                + ", ".join("%s/%s=%d" % (c["device"], c["name"], c["port"])
                                            for c in conflicts)],
                     "conflicts": conflicts}
    if not _pool_lock.acquire(blocking=False):
        return 409, {"ok": False, "errors": ["另一个端口池应用操作正在进行, 请稍候再试"]}
    steps, warns, bak_map = [], [], {}
    old_pool = json.loads(json.dumps(CFG.get("port_pool") or {}))
    try:
        ts = str(int(time.time()))
        for f in (CONFIG_PATH, FRPS_TOML):
            if f.is_file():
                bak = f.with_name(f.name + ".bak.pool." + ts)
                shutil.copy2(str(f), str(bak))
                bak_map[f] = str(bak)
        steps.append({"step": "备份", "ok": True,
                      "detail": ", ".join(bak_map.values()) or "无可备份文件"})
        cfg = json.loads(CONFIG_PATH.read_text("utf-8"))
        cfg["port_pool"] = pool
        cfg["remote_port_ranges"] = [[a, b] for a, b in pool_raw_ranges(pool)]
        if cfg["remote_port_ranges"]:
            cfg["port_range_start"] = min(a for a, _b in cfg["remote_port_ranges"])
            cfg["port_range_end"] = max(b for _a, b in cfg["remote_port_ranges"])
        CONFIG_PATH.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), "utf-8")
        steps.append({"step": "写入配置", "ok": True,
                      "detail": "config.json: port_pool 已更新, 旧键已镜像(保持兼容)"})
        fresh = load_config()
        CFG.clear()
        CFG.update(fresh)
        steps.append({"step": "就地重载", "ok": True, "detail": "面板进程内配置刷新完成(未重启面板)"})
        before, line = rewrite_frps_toml(effective_port_ranges(), max_per_client())
        steps.append({"step": "改写 frps.toml", "ok": True, "detail": "%s  ->  %s" % (before, line)})
        if pool.get("sync_ufw"):
            removed, added, uerrs = ufw_sync(effective_port_ranges())
            steps.append({"step": "同步 ufw", "ok": not uerrs,
                          "detail": "回收 %d 条旧规则, 新增 %d 条" % (len(removed), len(added)),
                          "removed": removed, "added": added})
            warns.extend(uerrs)
        else:
            steps.append({"step": "同步 ufw", "ok": True, "detail": "按配置跳过(sync_ufw=false)"})
        r = subprocess.run(["systemctl", "restart", "relayhub-frps"],
                           capture_output=True, text=True, timeout=40)
        if r.returncode != 0:
            detail = (r.stderr or r.stdout or "").strip() or "未知错误"
            warns.append("frps 重启失败: %s" % detail)
            steps.append({"step": "重启 frps", "ok": False, "detail": detail})
        else:
            alive, fport = False, int(CFG.get("frps_bind_port", 7000))
            for _i in range(20):
                time.sleep(0.5)
                try:
                    _s = socket.create_connection(("127.0.0.1", fport), 1.0)
                    _s.close()
                    alive = True
                    break
                except OSError:
                    pass
            steps.append({"step": "重启 frps", "ok": alive,
                          "detail": "已重启, 端口 %d %s" % (fport, "已监听" if alive else
                                    "10 秒内未监听, 查看 journalctl -u relayhub-frps")})
            if not alive:
                warns.append("frps 未能重新监听, 隧道暂时不可用")
        risk = tunnels_at_risk(effective_port_ranges())
        if risk:
            warns.append("这些现网隧道端口已不在新池内, 重启后将失效: "
                         + ", ".join("%s(%d)" % (x["name"], x["port"]) for x in risk))
        else:
            warns.append("frps 已重启, 在线隧道将在数秒内自动重连")
        ok = all(s.get("ok") for s in steps)
        return (200 if ok else 500), {"ok": ok, "steps": steps, "warnings": warns,
                                      "ranges_text": ranges_text(),
                                      "eff": [[a, b] for a, b in effective_port_ranges()],
                                      "backups": list(bak_map.values()),
                                      "rollback_pool": old_pool}
    except Exception as e:
        steps.append({"step": "异常中断", "ok": False, "detail": str(e)})
        try:
            restored = [f.name for f, b in bak_map.items() if Path(b).is_file()]
            for f, b in bak_map.items():
                if Path(b).is_file():
                    shutil.copy2(b, str(f))
            if restored:
                fresh = load_config()
                CFG.clear()
                CFG.update(fresh)
                subprocess.run(["systemctl", "restart", "relayhub-frps"],
                               capture_output=True, timeout=40)
                steps.append({"step": "自动回滚", "ok": True,
                              "detail": "已还原 %s 并重启 frps" % ", ".join(restored)})
        except Exception as e2:
            steps.append({"step": "自动回滚", "ok": False, "detail": "回滚失败: %s" % e2})
        return 500, {"ok": False, "steps": steps, "warnings": warns,
                     "errors": ["应用失败: %s" % e], "backups": list(bak_map.values())}
    finally:
        _pool_lock.release()


# ---------------------------------------------------------------- session
# ---------------------------------------------------------------- static cache
_static_cache = {}  # rel -> (mtime_ns, size, data, ctype)


def _static_lookup(rel, root_dir):
    """带 mtime 校验的静态文件内存缓存;超大文件返回 None 走流式。"""
    p = root_dir / rel
    try:
        st = p.stat()
    except OSError:
        return None
    hit = _static_cache.get(rel)
    if hit and hit[0] == st.st_mtime_ns and hit[1] == st.st_size:
        return hit[2], hit[3]
    if st.st_size > STATIC_CACHE_MAX:
        return None
    ctype = MIME.get(p.suffix, "application/octet-stream")
    data = p.read_bytes()
    if len(_static_cache) > 32:  # 简单防膨胀
        _static_cache.clear()
    _static_cache[rel] = (st.st_mtime_ns, st.st_size, data, ctype)
    return data, ctype


def make_session():
    exp = str(int(time.time()) + SESSION_TTL)
    sig = hmac.new(CFG["session_secret"].encode(), exp.encode(), hashlib.sha256).hexdigest()
    return exp + "." + sig


def check_session(token):
    if not token or "." not in token:
        return False
    exp, sig = token.split(".", 1)
    if not re.fullmatch(r"\d+", exp) or int(exp) < time.time():
        return False
    good = hmac.new(CFG["session_secret"].encode(), exp.encode(), hashlib.sha256).hexdigest()
    return hmac.compare_digest(good, sig)


# ---------------------------------------------------------------- login throttle
_fail_counts = {}  # ip -> (count, window_start, locked_until)
_fail_lock = threading.Lock()


def login_allowed(ip):
    with _fail_lock:
        rec = _fail_counts.get(ip)
        if rec and rec[2] > time.time():
            return False, int(rec[2] - time.time()) + 1
    return True, 0


def record_fail(ip):
    with _fail_lock:
        c, w, lock = _fail_counts.get(ip, (0, time.time(), 0))
        now = time.time()
        if now - w > 300:
            c, w = 0, now
        c += 1
        if c >= 5:
            lock = now + 60
            c, w = 0, now
        _fail_counts[ip] = (c, w, lock)


# ---------------------------------------------------------------- request guards
_req_counts = {}  # ip -> (count, window_start) 轻量请求限流(除轮询接口外)
_req_lock = threading.Lock()
REQ_WINDOW = 60
REQ_MAX = 240          # 每 IP 每分钟非轮询请求上限
ALLOC_MAX_PER_IP = 5   # 每 IP 并发端口预留上限


def request_allowed(ip):
    with _req_lock:
        c, w = _req_counts.get(ip, (0, time.time()))
        now = time.time()
        if now - w > REQ_WINDOW:
            c, w = 0, now
        c += 1
        _req_counts[ip] = (c, w)
        return c <= REQ_MAX


def alloc_quota_ok(ip):
    now = time.time()
    with _reservations_lock:
        mine = sum(1 for v in _reservations_by_ip.get(ip, {}).values() if v > now)
    return mine < ALLOC_MAX_PER_IP


# ---------------------------------------------------------------- views
def build_summary():
    with COLLECTOR.lock:
        st = dict(COLLECTOR.state)
        st["history"] = list(COLLECTOR.state.get("history", []))  # 浅拷贝,避免序列化竞争
    proxies = st["proxies"]
    probes = st.get("probes", {})
    dev_rates = st.get("dev_rates", {})
    dev_hist = st.get("dev_hist", {})
    devices = {}
    for p in proxies:
        name = str(p.get("name", "?"))
        dev = name.split(".", 1)[0] if "." in name else "(默认)"
        d = devices.setdefault(dev, {
            "name": dev, "status": "offline", "proxies": 0, "online_proxies": 0,
            "today_in": 0, "today_out": 0, "cur_conns": 0,
            "last_start": "", "last_close": "", "err": "",
            "reachable": 0, "probed": 0, "avg_latency": None,
        })
        d["proxies"] += 1
        d["today_in"] += p.get("todayTrafficIn", 0) or 0
        d["today_out"] += p.get("todayTrafficOut", 0) or 0
        d["cur_conns"] += p.get("curConns", 0) or 0
        pr = probes.get(name)
        if pr:
            d["probed"] += 1
            if pr.get("ok"):
                d["reachable"] += 1
                lat = pr.get("ms")
                if lat is not None:
                    cur = d["avg_latency"]
                    d["avg_latency"] = round(lat, 1) if cur is None else round((cur * (d["reachable"] - 1) + lat) / d["reachable"], 1)
        if str(p.get("status")) == "online":
            d["online_proxies"] += 1
            if p.get("lastStartTime", "") > d["last_start"]:
                d["last_start"] = p.get("lastStartTime", "")
        else:
            if p.get("lastCloseTime", "") > d["last_close"]:
                d["last_close"] = p.get("lastCloseTime", "")
        if p.get("err"):
            d["err"] = p["err"]
    for d in devices.values():
        if d["online_proxies"] > 0:
            d["status"] = "online"
        elif d["err"]:
            d["status"] = "error"
        r = dev_rates.get(d["name"])
        d["rate_in"], d["rate_out"] = (r[0], r[1]) if r else (0, 0)
        d["hist"] = dev_hist.get(d["name"], [])
    st["devices"] = sorted(devices.values(), key=lambda x: (x["status"] != "online", x["name"]))
    # frps 会为"已被客户端注销的代理"保留一条 conf 被清空的墓碑记录(远端端口与
    # remoteAddr 都为空、status=offline)。设备存在性必须仍按上面的原始列表统计,
    # 否则清空某设备全部映射后它会从列表消失、图形化编辑器反而够不着;
    # 但这种记录不该出现在详情表里 —— 否则每删除一条映射就永久多一行"离线"幽灵。
    # 仅剔除:未上线 且 既无 remotePort 也无 remoteAddr 的记录。
    st["proxies"] = [p for p in proxies
                     if str(p.get("status")) == "online"
                     or (p.get("conf") or {}).get("remotePort")
                     or p.get("remoteAddr")]
    st["meta"] = {
        "public_addr": CFG["public_addr"], "frps_bind_port": CFG["frps_bind_port"],
        "frp_ws_port": CFG.get("frp_ws_port", 80),
        "panel_base": CFG["panel_base"],
        "port_range": guide_port_range(),
        "ranges_text": ranges_text(),
        "frp_version": CFG["frp_version"], "panel_version": "1.2.0",
        "frps_token": CFG.get("frps_token", ""),
        "now": int(time.time()),
    }
    return st


# ---------------------------------------------------------------- http handler
MIME = {
    ".html": "text/html; charset=utf-8", ".js": "application/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml", ".png": "image/png",
    ".ico": "image/x-icon", ".gz": "application/gzip", ".tar": "application/x-tar",
    ".json": "application/json; charset=utf-8", ".sh": "text/x-shellscript; charset=utf-8",
}


class Handler(BaseHTTPRequestHandler):
    server_version = "RelayHub"      # 不暴露版本细节
    protocol_version = "HTTP/1.1"
    timeout = 30                     # 单请求读写超时,防慢速连接占用线程

    def log_message(self, fmt, *args):
        pass  # 静默访问日志,避免刷屏;如需调试可改为 stderr 输出

    # ---- helpers ----
    def _send(self, code, body, ctype="application/json; charset=utf-8", headers=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        hdrs = {
            "Content-Type": ctype,
            "Content-Length": str(len(body)),
            "Cache-Control": "no-store" if ctype.startswith("application/json") else "max-age=60",
            "X-Content-Type-Options": "nosniff",
            "Referrer-Policy": "no-referrer",
            "X-Frame-Options": "DENY",
        }
        if ctype.startswith("text/html"):
            hdrs["Content-Security-Policy"] = (
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                "img-src 'self' data:; connect-src 'self'; object-src 'none'; "
                "frame-ancestors 'none'; base-uri 'self'")
        hdrs.update(headers or {})  # 调用方可覆盖默认值
        self.send_response(code)
        for k, v in hdrs.items():
            self.send_header(k, v)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _same_origin_ok(self):
        """浏览器发起的状态变更请求(POST)做同源校验,防 CSRF。"""
        origin = self.headers.get("Origin")
        if not origin:
            return True  # 非浏览器客户端(安装脚本/内部代理)不携带 Origin
        host = self.headers.get("Host", "")
        try:
            from urllib.parse import urlparse
            return urlparse(origin).netloc == host
        except Exception:
            return False

    def _json(self, code, obj, headers=None):
        self._send(code, json.dumps(obj, ensure_ascii=False, separators=(",", ":")),
                   headers=headers)

    def _cookie_session(self):
        c = self.headers.get("Cookie", "")
        m = re.search(r"relay_session=([A-Za-z0-9.]+)", c)
        return m.group(1) if m else None

    def _client_ip(self):
        # 面板仅监听 127.0.0.1,外部流量必经 nginx;信任其设置的真实客户端 IP
        xrip = self.headers.get("X-Real-IP", "").strip()
        if xrip:
            return xrip
        xff = self.headers.get("X-Forwarded-For", "").split(",")[0].strip()
        return xff or self.client_address[0]

    def _authed(self):
        if check_session(self._cookie_session()):
            return True
        # 面板本机互信:Cloud Monitor 后端代理携带内部密钥访问
        key = self.headers.get("X-RelayHub-Key", "")
        return bool(key and CFG.get("internal_key")
                    and hmac.compare_digest(key, CFG["internal_key"]))

    # ---- routes ----
    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/", "/index.html", "/devices", "/proxies", "/guide", "/login"):
            self._serve_static("index.html")
            return
        if path.startswith("/static/"):
            self._serve_static(path[len("/static/"):])
            return
        if path == "/api/summary":
            if not self._authed():
                self._json(401, {"error": "unauthorized"})
                return
            cached = COLLECTOR.summary_json
            if not cached:  # 启动初期缓存尚未生成
                self._json(200, build_summary())
                return
            etag = COLLECTOR.summary_etag
            if etag and self.headers.get("If-None-Match") == etag:
                self._send(304, b"", headers={"ETag": etag, "Cache-Control": "no-cache"})
                return
            self._send(200, cached, headers={"ETag": etag, "Cache-Control": "no-cache"})
            return
        if path == "/api/mappings":
            if not self._authed():
                self._json(401, {"error": "unauthorized"})
                return
            with _mappings_lock:
                maps = json.loads(json.dumps(_mappings))
            live = {}
            with COLLECTOR.lock:
                for p in COLLECTOR.state.get("proxies", []):
                    rp = COLLECTOR._proxy_port(p)
                    if not rp:
                        continue
                    nm = str(p.get("name") or "")
                    owner, sub = (nm.split(".", 1) + [nm])[:2] if "." in nm else (nm, nm)
                    conf = p.get("conf") or {}
                    live.setdefault(owner, []).append({
                        "name": sub, "remotePort": rp,
                        "localPort": conf.get("localPort") or _local_port_hint(owner, sub),
                        "localIP": conf.get("localIP"),
                        "type": conf.get("type", "tcp"),
                        "status": str(p.get("status") or ""),
                    })
            self._json(200, {
                "allowed": allowed_remote_ranges(),
                "ranges_text": ranges_text(),
                "mappings": maps,
                "live": live,
                "max_per_device": max_per_client(),
            })
            return
        if path == "/api/frpc-config":
            if not self._authed():
                self._json(401, {"error": "unauthorized"})
                return
            from urllib.parse import parse_qs
            q = parse_qs(self.path.split("?", 1)[1]) if "?" in self.path else {}
            dev = (q.get("name") or [""])[0]
            if not re.match(r"^[a-z0-9][a-z0-9-]{0,30}$", dev):
                self._json(400, {"error": "设备名不合法"})
                return
            with _mappings_lock:
                maps = json.loads(json.dumps(_mappings.get(dev, [])))
            cfg_txt = generate_frpc_config(dev, maps)
            self._json(200, {
                "ok": True, "device": dev, "mappings": maps,
                "config": cfg_txt,
                "apply": frpc_apply_command(cfg_txt) if cfg_txt else "",
                "warning": ssh_drop_warning(dev, maps),
                "hint": "在该设备上以 root 执行上面的命令即可生效",
            })
            return
        if path == "/api/port-pool":
            if not self._authed():
                self._json(401, {"error": "unauthorized"})
                return
            try:
                self._json(200, port_pool_status())
            except Exception as e:
                self._json(500, {"error": "读取端口池失败: %s" % e})
            return
        if path == "/api/alloc-port":
            ip = self._client_ip()
            if not request_allowed(ip):
                self._json(429, {"error": "请求过于频繁"})
                return
            if not alloc_quota_ok(ip):
                self._json(429, {"error": "该 IP 预留端口数已达上限"})
                return
            p = alloc_port(ip)
            self._json(200, {"port": p} if p else {"error": "no free port"})
            return
        if path == "/install.sh":
            if INSTALLER_PATH.exists():
                body = (INSTALLER_PATH.read_text("utf-8")
                        .replace("__PANEL_URL__", CFG["panel_base"])
                        .replace("__SERVER_ADDR__", CFG["public_addr"])
                        .replace("__SERVER_PORT__", str(CFG.get("frp_ws_port", 80)))
                        .replace("__FRP_VER__", CFG["frp_version"])
                        .replace("__PORT_RANGE__", ranges_text())
                        .encode("utf-8"))
            else:
                body = b"#!/bin/sh\necho 'installer missing'\n"
            self._send(200, body, "text/x-shellscript; charset=utf-8")
            return
        if path.startswith("/dl/"):
            fname = os.path.basename(path[len("/dl/"):])
            f = (DOWNLOADS_DIR / fname).resolve()
            if not str(f).startswith(str(DOWNLOADS_DIR.resolve())) or not f.is_file():
                self._json(404, {"error": "not found"})
                return
            ctype = MIME.get(f.suffix, "application/octet-stream")
            got = _static_lookup(fname, DOWNLOADS_DIR)
            if got:  # 小文件走内存缓存
                self._send(200, got[0], got[1], headers={"Cache-Control": "public, max-age=3600"})
                return
            # 大文件(如 frp 分发包)流式发送,不整读进内存
            size = f.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "public, max-age=3600")
            self.end_headers()
            with open(f, "rb") as fh:
                while True:
                    chunk = fh.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            return
        if path == "/api/health":
            self._json(200, {"ok": True})
            return
        self._json(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        ip = self._client_ip()
        if not request_allowed(ip):
            self._json(429, {"error": "请求过于频繁"})
            return
        if not self._same_origin_ok():
            self._json(403, {"error": "cross-origin rejected"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length > 16384:
            self._json(413, {"error": "too large"})
            return
        raw = self.rfile.read(length) if length else b"{}"
        if path == "/api/login":
            ok, wait = login_allowed(self._client_ip())
            if not ok:
                self._json(429, {"error": "尝试过多,请 %d 秒后再试" % wait})
                return
            try:
                pw = json.loads(raw.decode("utf-8")).get("password", "")
            except Exception:
                pw = ""
            good = hmac.compare_digest(
                hash_password(pw, CFG["admin_password_salt"]), CFG["admin_password_hash"])
            if good:
                tok = make_session()
                self._json(200, {"ok": True}, headers={
                    "Set-Cookie": "relay_session=%s; Path=/; HttpOnly; SameSite=Lax; Max-Age=%d" % (tok, SESSION_TTL)})
            else:
                record_fail(self._client_ip())
                self._json(403, {"error": "密码错误"})
            return
        if path == "/api/logout":
            self._json(200, {"ok": True}, headers={
                "Set-Cookie": "relay_session=; Path=/; HttpOnly; Max-Age=0"})
            return
        if path == "/api/mappings":
            if not self._authed():
                self._json(401, {"error": "unauthorized"})
                return
            try:
                body = json.loads(raw.decode("utf-8") or "{}")
            except Exception:
                self._json(400, {"error": "JSON 解析失败"})
                return
            dev = str(body.get("device") or "")
            errs, clean = validate_mappings(dev, body.get("mappings"))
            if errs:
                self._json(400, {"error": "校验未通过: " + "; ".join(errs),
                                  "details": errs})
                return
            warning = ssh_drop_warning(dev, clean)   # 必须在写入期望态之前计算
            with _mappings_lock:
                if clean:
                    _mappings[dev] = clean
                else:
                    _mappings.pop(dev, None)
                try:
                    _persist_mappings()
                except Exception as e:
                    self._json(500, {"error": "写入失败: %s" % e})
                    return
            cfg_txt = generate_frpc_config(dev, clean)
            self._json(200, {
                "ok": True, "device": dev, "count": len(clean),
                "config": cfg_txt,
                "apply": frpc_apply_command(cfg_txt) if cfg_txt else "",
                "warning": warning,
            })
            return
        if path in ("/api/port-pool/preview", "/api/port-pool/apply", "/api/port-pool/probe"):
            if not self._authed():
                self._json(401, {"error": "unauthorized"})
                return
            try:
                body = json.loads(raw.decode("utf-8") or "{}")
            except Exception:
                self._json(400, {"error": "JSON 解析失败"})
                return
            if path == "/api/port-pool/probe":
                try:
                    self._json(200, handle_pool_probe(body))
                except Exception as e:
                    self._json(500, {"ok": False, "error": "探测失败: %s" % e})
                return
            pool_raw = body.get("pool")
            if not isinstance(pool_raw, dict):
                self._json(400, {"error": "缺少 pool 对象"})
                return
            if path.endswith("/preview"):
                self._json(200, pool_preview(pool_raw))
                return
            code, payload = apply_port_pool(pool_raw)
            self._json(code, payload)
            return
        if path == "/api/probe-now":
            if not self._authed():
                self._json(401, {"error": "unauthorized"})
                return
            with COLLECTOR.lock:
                proxies = list(COLLECTOR.state["proxies"])
            results = COLLECTOR._probe_all(proxies) if proxies else {}
            with COLLECTOR.lock:
                COLLECTOR.state["probes"] = results
            self._json(200, {"ok": True, "probes": results})
            return
        self._json(404, {"error": "not found"})

    def _serve_static(self, rel):
        f = (STATIC_DIR / rel).resolve()
        if not str(f).startswith(str(STATIC_DIR.resolve())) or not f.is_file():
            self._send(404, "not found", "text/plain; charset=utf-8")
            return
        got = _static_lookup(rel, STATIC_DIR)
        if not got:  # 理论上仅当文件超限时触发
            ctype = MIME.get(f.suffix, "application/octet-stream")
            with open(f, "rb") as fh:
                self._send(200, fh.read(), ctype)
            return
        data, ctype = got
        # HTML 壳始终回源校验;带版本号的资源长缓存
        cc = "no-cache" if f.suffix == ".html" else "public, max-age=86400"
        self._send(200, data, ctype, headers={"Cache-Control": cc})


def main():
    port = int(os.environ.get("RELAY_PORT") or CFG["bind_port"])
    host = os.environ.get("RELAY_HOST") or CFG["bind_host"]
    httpd = ThreadingHTTPServer((host, port), Handler)
    print("RelayHub panel listening on %s:%d" % (host, port), flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
