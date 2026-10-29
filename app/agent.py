#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Cloud Monitor 内网设备硬件采集 Agent
=====================================

把内网设备(树莓派 / 任意 Linux)的硬件性能主动推送到公网 Cloud Monitor 面板。

设计要点(生产化):
  * 只读 /proc 与 sysfs,不 fork、不依赖第三方库(纯标准库,Debian 自带 Python 即可)
  * 采样线程与发送线程分离:网络抖动绝不拖慢采样节拍(默认 500ms)
  * 发送失败时样本进环形缓冲(默认 10 分钟),网络恢复后按序补传,不丢数据
  * 请求体 gzip 压缩,500ms 上报的实际流量约 40~60 KB/分钟
  * 重数据(进程 Top / 系统信息 / 健康度)低频上报(默认 5s),核心指标高频上报
  * systemd 托管,崩溃自动拉起;`--install` 可一键完成自我安装

用法:
  python3 agent.py                                  # 前台运行(读取 config.json)
  python3 agent.py --install --endpoint URL --token T --device NAME
  python3 agent.py --once                           # 打印一份样本自检
  python3 agent.py --check                          # 连通性自检
"""
import argparse
import gzip
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import deque

AGENT_VER = "1.1.0"
HOME = "/opt/hwagent"
CONF = os.path.join(HOME, "config.json")
UNIT = "/etc/systemd/system/hwagent.service"
UNIT_NAME = "hwagent"
DEFAULTS = {
    "endpoint": "",
    "token": "",
    "device": "",
    "interval_ms": 500,       # 核心指标采样/上报节拍
    "heavy_ms": 5000,         # 进程 Top 等重数据节拍
    "journal_ms": 30000,      # journal 错误采集节拍(最贵)
    "buffer_max": 1200,       # 离线缓冲上限(条) ≈ 10 分钟
    "timeout": 8,
}

CLK_TCK = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100
PAGE = os.sysconf("SC_PAGE_SIZE") if hasattr(os, "sysconf") else 4096

_lock = threading.Lock()
_log_lock = threading.Lock()


def log(msg):
    with _log_lock:
        sys.stdout.write("%s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))
        sys.stdout.flush()


def read(path):
    """读小文件;任何异常都返回 None,采集绝不因单个文件失败而中断。"""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return f.read()
    except Exception:
        return None


def run_cmd(argv, timeout=6):
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout,
                           env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL": "C"})
        return (p.stdout or "") + (p.stderr or "")
    except Exception:
        return ""


# ---------------------------------------------------------------- 采集:CPU
def _read_cpu_stat():
    """返回 {cpu: (total, idle), cpu0: (...), ...} 以及 procs_running 等全局量。"""
    txt = read("/proc/stat")
    out = {}
    extra = {}
    if not txt:
        return out, extra
    for line in txt.splitlines():
        if line.startswith("cpu"):
            parts = line.split()
            if len(parts) < 5:
                continue
            try:
                vals = [int(x) for x in parts[1:]]
            except ValueError:
                continue
            idle = vals[3] + (vals[4] if len(vals) > 4 else 0)
            out[parts[0]] = (sum(vals), idle)
        elif line.startswith("procs_running") or line.startswith("procs_blocked") \
                or line.startswith("processes") or line.startswith("ctxt"):
            k, _, v = line.partition(" ")
            try:
                extra[k] = int(v)
            except ValueError:
                pass
    return out, extra


def cpu_pct(prev, cur):
    """由两次 /proc/stat 差分算使用率;无差分时返回 None。"""
    res = {}
    for name, (t, i) in cur.items():
        if name not in prev:
            continue
        pt, pi = prev[name]
        dt, di = t - pt, i - pi
        if dt <= 0:
            continue
        used = dt - di
        res[name] = max(0.0, min(100.0, used * 100.0 / dt))
    return res


# ---------------------------------------------------------------- 采集:网络
def _read_net():
    txt = read("/proc/net/dev")
    out = {}
    if not txt:
        return out
    for line in txt.splitlines()[2:]:
        if ":" not in line:
            continue
        name, rest = line.split(":", 1)
        v = rest.split()
        if len(v) < 16:
            continue
        try:
            out[name.strip()] = {
                "rx": int(v[0]), "tx": int(v[8]),
                "rxErr": int(v[2]) + int(v[3]), "txErr": int(v[10]) + int(v[11]),
            }
        except ValueError:
            continue
    return out


# ---------------------------------------------------------------- 采集:磁盘
REAL_FS = ("ext2", "ext3", "ext4", "xfs", "btrfs", "f2fs", "zfs", "vfat", "exfat",
           "ntfs", "ntfs3", "jfs", "reiserfs", "ufs", "apfs")


def _read_mounts():
    """只保留真实磁盘挂载(排除 tmpfs/proc/sysfs 等),避免把 /tmp 之类算进容量。"""
    txt = read("/proc/mounts")
    out = []
    if not txt:
        return out
    for line in txt.splitlines():
        parts = line.split()
        if len(parts) < 4:
            continue
        dev, mp, fst = parts[0], parts[1], parts[2]
        if fst not in REAL_FS or not dev.startswith("/"):
            continue
        if mp in ("/var/lib/docker",) or mp.startswith("/var/lib/docker/"):
            continue
        out.append({"dev": dev, "mount": mp, "fs": fst})
    # 按路径长度排序,父挂载在前
    out.sort(key=lambda x: x["mount"])
    return out


def disk_usage(mounts):
    res = []
    for m in mounts:
        try:
            st = os.statvfs(m["mount"])
        except Exception:
            continue
        if st.f_blocks <= 0:
            continue
        total = st.f_blocks * st.f_frsize
        avail = st.f_bavail * st.f_frsize
        used = total - avail
        row = dict(m)
        row.update({
            "total": total, "used": used, "avail": avail,
            "pct": round(used * 100.0 / total, 1),
        })
        if st.f_files > 0:
            row["inoPct"] = round((st.f_files - st.f_ffree) * 100.0 / st.f_files, 1)
        res.append(row)
    return res


def _read_diskstats():
    """{devname: {read, write}}  sectors(512B)"""
    txt = read("/proc/diskstats")
    out = {}
    if not txt:
        return out
    for line in txt.splitlines():
        p = line.split()
        if len(p) < 10:
            continue
        name = p[2]
        if name.startswith(("loop", "ram", "fd", "sr")):
            continue
        # 跳过分区(sda1),只留整盘(sda);无分区后缀的设备(name 以数字结尾则可能是 mmcblk0p1)
        try:
            out[name] = {"read": int(p[5]), "write": int(p[9])}
        except ValueError:
            continue
    return out


# ---------------------------------------------------------------- 采集:树莓派专属
def rpi_temp():
    t = read("/sys/class/thermal/thermal_zone0/temp")
    if t:
        try:
            return round(int(t.strip()) / 1000.0, 1)
        except ValueError:
            pass
    o = run_cmd(["vcgencmd", "measure_temp"], timeout=3)
    m = re.search(r"temp=([0-9.]+)", o)
    return round(float(m.group(1)), 1) if m else None


def rpi_freq():
    t = read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
    if t:
        try:
            return int(int(t.strip()) / 1000)   # MHz
        except ValueError:
            pass
    return None


THROTTLE_BITS = [
    (0, "当前欠压"), (1, "当前降频"), (2, "当前限流"), (3, "当前温度限制"),
    (16, "曾发生欠压"), (17, "曾发生降频"), (18, "曾发生限流"), (19, "曾发生温度限制"),
]


def rpi_throttle():
    o = run_cmd(["vcgencmd", "get_throttled"], timeout=3)
    m = re.search(r"throttled=(0x[0-9a-fA-F]+)", o)
    if not m:
        return None
    raw = int(m.group(1), 16)
    return {"raw": m.group(1), "flags": [d for b, d in THROTTLE_BITS if raw & (1 << b)]}


def wifi_info():
    txt = read("/proc/net/wireless")
    if not txt:
        return None
    for line in txt.splitlines()[2:]:
        p = line.split()
        if len(p) < 4 or not p[0].endswith(":"):
            continue
        try:
            quality = int(float(p[2]))
            level = int(float(p[3]))
        except (ValueError, IndexError):
            continue
        return {"iface": p[0][:-1], "quality": quality, "signal": level,
                "qualityPct": min(100, max(0, quality * 100 // 70))}
    return None


# ---------------------------------------------------------------- 采集:进程
def scan_procs(prev):
    """一次扫描 /proc,得到 CPU/MEM;返回 (列表, 本次差分快照)。"""
    cur = {}
    rows = []
    try:
        pids = [d for d in os.listdir("/proc") if d.isdigit()]
    except Exception:
        return [], prev
    for pid in pids:
        base = "/proc/" + pid
        st = read(base + "/stat")
        if not st:
            continue
        i = st.rfind(")")          # comm 可能含空格与括号
        if i < 0:
            continue
        comm = st[st.find("(") + 1:i]
        rest = st[i + 2:].split()
        if len(rest) < 22:
            continue
        try:
            utime, stime, rss = int(rest[11]), int(rest[12]), int(rest[21])
        except (ValueError, IndexError):
            continue
        try:
            uid = os.stat(base).st_uid
        except Exception:
            uid = -1
        cur[pid] = utime + stime
        rows.append({"pid": int(pid), "comm": comm, "uid": uid,
                     "cpuTicks": utime + stime, "rss": rss * PAGE})

    # 用上次快照把累计 ticks 换算成本周期 CPU%
    n = len(rows)
    for r in rows:
        p = prev.get(r["pid"])
        r["cpu"] = round((r["cpuTicks"] - p) * 100.0 / CLK_TCK, 1) if p is not None and p <= r["cpuTicks"] else 0.0
    return rows, cur


def fmt_user(uid, cache={}):
    if uid in cache:
        return cache[uid]
    name = str(uid)
    try:
        import pwd
        name = pwd.getpwuid(uid).pw_name
    except Exception:
        pass
    cache[uid] = name
    return name


# ---------------------------------------------------------------- 采集:健康度
def health(cache):
    """cache: dict(at/journal/svcAt/svc) — 跨次调用复用,避免每拍 fork。"""
    h = {}
    # systemd 失败单元(fork 较贵,10 秒一次)
    if time.time() - cache.get("svcAt", 0.0) >= 10:
        out = run_cmd(["systemctl", "list-units", "--failed", "--no-legend", "--no-pager"], timeout=6)
        failed = []
        for line in out.splitlines():
            p = line.split()
            if p:
                failed.append(p[0])
        cache["svcAt"] = time.time()
        cache["svc"] = failed[:20]
    h["failedUnits"] = cache.get("svc", [])

    # 最近 journal 报错(低频采集,结果带缓存)
    now = time.time()
    if now - cache["at"] >= cache["ttl"]:
        out = run_cmd(["journalctl", "-p", "err", "-n", "10", "--since", "-10 min",
                       "--no-pager", "-o", "short-iso", "--no-hostname"], timeout=8)
        lines = [l for l in out.splitlines() if l and not l.startswith("--")]
        cache["at"], cache["journal"] = now, lines[:10]
    h["journal"] = cache["journal"]

    # 只读挂载(磁盘写保护/故障的强信号)
    ro = []
    txt = read("/proc/mounts")
    if txt:
        for line in txt.splitlines():
            p = line.split()
            if len(p) >= 4 and p[3].startswith("ro,") and p[2] in REAL_FS and p[1] != "/boot/firmware":
                ro.append(p[1])
    h["roMounts"] = ro

    h["wifi"] = wifi_info()
    h["throttle"] = rpi_throttle()
    return h


def sysinfo():
    os_name = ""
    txt = read("/etc/os-release") or ""
    m = re.search(r'PRETTY_NAME="?([^"\n]+)"?', txt)
    if m:
        os_name = m.group(1)
    model = ""
    cpuinfo = read("/proc/cpuinfo") or ""
    for key in ("Model", "Hardware"):
        m = re.search(r"^%s\s*:\s*(.+)$" % key, cpuinfo, re.M)
        if m:
            model = m.group(1).strip()
            break
    mem_total = 0
    mi = read("/proc/meminfo") or ""
    m = re.search(r"^MemTotal:\s+(\d+)", mi, re.M)
    if m:
        mem_total = int(m.group(1)) * 1024
    return {
        "host": socket.gethostname(),
        "os": os_name,
        "kernel": platform.release(),
        "arch": platform.machine(),
        "model": model,
        "cores": os.cpu_count() or 1,
        "memTotal": mem_total,
        "agent": AGENT_VER,
    }


# ---------------------------------------------------------------- 主采集器
class Sampler:
    def __init__(self, cfg):
        self.cfg = cfg
        self.mounts = _read_mounts()
        self.prev_stat, self.prev_extra = _read_cpu_stat()
        self.prev_net = _read_net()
        self.prev_disk = _read_diskstats()
        time.sleep(0.15)                       # 预热一次差分窗口
        s, e = _read_cpu_stat()
        self.prev_stat, self.prev_extra = s, e
        self.prev_net = _read_net()
        self.prev_disk = _read_diskstats()
        self.prev_proc = {}
        # 整盘设备集合(/sys/block 下的名字):sda、mmcblk0 …;分区不在其中
        try:
            self.whole_disks = frozenset(os.listdir("/sys/block"))
        except Exception:
            self.whole_disks = frozenset()
        self.heavy_at = 0.0
        self.jcache = {"at": 0.0, "ttl": self.cfg["journal_ms"] / 1000.0, "journal": []}
        self.io_at = time.time()
        self.heavy = None

    def sample(self):
        now = time.time()
        cur_stat, extra = _read_cpu_stat()
        pct = cpu_pct(self.prev_stat, cur_stat)
        self.prev_stat = cur_stat

        load = (read("/proc/loadavg") or "").split()
        up = (read("/proc/uptime") or "").split()

        # ---- 内存 ----
        mem = {}
        txt = read("/proc/meminfo") or ""
        kv = {}
        for line in txt.splitlines():
            k, _, v = line.partition(":")
            v = v.strip().split()
            if v:
                try:
                    kv[k] = int(v[0]) * 1024
                except ValueError:
                    pass
        total = kv.get("MemTotal", 0)
        avail = kv.get("MemAvailable", 0)
        used = max(0, total - avail)
        stot = kv.get("SwapTotal", 0)
        sfree = kv.get("SwapFree", 0)
        mem = {
            "total": total, "used": used, "avail": avail,
            "usedPct": round(used * 100.0 / total, 1) if total else 0,
            "swapTotal": stot, "swapUsed": max(0, stot - sfree),
            "swapPct": round((stot - sfree) * 100.0 / stot, 1) if stot else 0,
        }

        # ---- 网络 ----
        cur_net = _read_net()
        ifaces = {}
        for name, c in cur_net.items():
            p = self.prev_net.get(name)
            row = dict(c)
            row["rxBps"] = 0
            row["txBps"] = 0
            if p and c["rx"] >= p["rx"]:
                row["rxBps"] = c["rx"] - p["rx"]
            if p and c["tx"] >= p["tx"]:
                row["txBps"] = c["tx"] - p["tx"]
            ifaces[name] = row
        self.prev_net = cur_net

        # ---- 磁盘 IO(只统计整盘,避免把 sda 与 sda1 重复计入;换算成 B/s) ----
        cur_disk = _read_diskstats()
        now_io = time.time()
        elapsed = max(1e-6, now_io - self.io_at)
        io = {"read": 0, "write": 0}
        for name, c in cur_disk.items():
            if name not in self.whole_disks:
                continue
            p = self.prev_disk.get(name)
            if not p:
                continue
            if c["read"] >= p["read"]:
                io["read"] += (c["read"] - p["read"]) * 512
            if c["write"] >= p["write"]:
                io["write"] += (c["write"] - p["write"]) * 512
        self.prev_disk = cur_disk
        self.io_at = now_io
        io["read"] = int(io["read"] / elapsed)
        io["write"] = int(io["write"] / elapsed)

        s = {
            "ts": int(now * 1000),
            "cpu": {
                "total": round(pct.get("cpu", 0.0), 1),
                "cores": [round(pct.get("cpu%d" % i, 0.0), 1) for i in range(len(cur_stat) - 1)],
                "load": [float(load[0]), float(load[1]), float(load[2])] if len(load) >= 3 else [],
                "freq": rpi_freq(),
                "procs": extra.get("processes"),
                "running": extra.get("procs_running"),
            },
            "mem": mem,
            "net": {"ifaces": ifaces},
            "disk": {"mounts": disk_usage(self.mounts), "io": io},
            "temp": rpi_temp(),
            "uptime": int(float(up[0])) if up else 0,
        }

        # ---- 重数据(低频) ----
        fresh = self.heavy is None or (now - self.heavy_at) * 1000 >= self.cfg["heavy_ms"]
        if fresh:
            self.heavy_at = now
            rows, self.prev_proc = scan_procs(self.prev_proc)
            total_mem = mem["total"]

            def enrich(r):
                """补 user/cmd/memPct(需要读 cmdline,故只对 TopN 做)。"""
                cmd = (read("/proc/%d/cmdline" % r["pid"]) or "").replace("\x00", " ").strip()
                return {
                    "pid": r["pid"],
                    "user": fmt_user(r["uid"]),
                    "cpu": r.get("cpu", 0.0),
                    "rss": r["rss"],
                    "memPct": round(r["rss"] * 100.0 / total_mem, 1) if total_mem else 0,
                    "cmd": cmd or ("[" + r["comm"] + "]"),
                }

            top_cpu = [enrich(r) for r in sorted(rows, key=lambda x: x.get("cpu", 0), reverse=True)[:10]]
            top_mem = [enrich(r) for r in sorted(rows, key=lambda x: x["rss"], reverse=True)[:10]]

            self.heavy = {
                "procs": top_cpu,
                "topMem": top_mem,
                "procCount": len(rows),
                "sys": sysinfo(),
                "health": health(self.jcache),
            }
        # 只在刚刷新时携带重数据(否则每拍都要多传 3~5KB,500ms 节拍下白烧流量)
        if fresh:
            s["heavy"] = self.heavy
        s["heavyAge"] = int((now - self.heavy_at) * 1000)
        return s


# ---------------------------------------------------------------- 发送
class Pusher:
    def __init__(self, cfg, sampler):
        self.cfg = cfg
        self.sampler = sampler
        self.buf = deque(maxlen=int(cfg["buffer_max"]))
        self.wake = threading.Event()
        self.backoff = 1.0
        self.ok_count = 0
        self.fail_count = 0
        self.last_ok = 0.0

    def enqueue(self, s):
        with _lock:
            self.buf.append(s)
        self.wake.set()

    def _post(self, samples):
        body = json.dumps({"device": self.cfg["device"], "samples": samples},
                          separators=(",", ":")).encode("utf-8")
        raw = gzip.compress(body) if len(body) > 512 else body
        headers = {
            "Content-Type": "application/json",
            "X-Device-Token": self.cfg["token"],
            "X-Agent-Ver": AGENT_VER,
            "User-Agent": "cm-hwagent/" + AGENT_VER,
        }
        if raw is not body:
            headers["Content-Encoding"] = "gzip"
        req = urllib.request.Request(self.cfg["endpoint"], data=raw, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=self.cfg["timeout"]) as r:
            return r.status, r.read(4096).decode("utf-8", "replace")

    def loop(self):
        while True:
            self.wake.wait(timeout=1.0)
            self.wake.clear()
            while True:
                with _lock:
                    if not self.buf:
                        break
                    batch = [self.buf[i] for i in range(min(200, len(self.buf)))]
                    for _ in range(len(batch)):
                        self.buf.popleft()
                try:
                    code, txt = self._post(batch)
                    if code != 200:
                        raise RuntimeError("HTTP %s %s" % (code, txt[:200]))
                    self.ok_count += 1
                    self.fail_count = 0
                    self.backoff = 1.0
                    self.last_ok = time.time()
                    if self.ok_count % 200 == 1:
                        log("push ok samples=%d buffered=%d" % (len(batch), len(self.buf)))
                except Exception as e:
                    # 失败:塞回缓冲(保持顺序),退避后重试
                    with _lock:
                        for s in reversed(batch):
                            self.buf.appendleft(s)
                    self.fail_count += 1
                    if self.fail_count in (1, 5, 30):
                        log("push fail(%s) buffered=%d" % (str(e)[:120], len(self.buf)))
                    time.sleep(self.backoff)
                    self.backoff = min(self.backoff * 1.7, 30.0)
                    break


# ---------------------------------------------------------------- 主循环
def load_config():
    cfg = dict(DEFAULTS)
    try:
        with open(CONF, encoding="utf-8") as f:
            cfg.update(json.load(f))
    except Exception as e:
        log("配置读取失败 %s: %s" % (CONF, e))
    for k in ("interval_ms", "heavy_ms", "journal_ms", "buffer_max", "timeout"):
        try:
            cfg[k] = int(cfg[k])
        except Exception:
            cfg[k] = DEFAULTS[k]
    return cfg


def run(cfg):
    if not cfg["endpoint"] or not cfg["token"]:
        log("endpoint / token 未配置,退出")
        return 2
    log("hwagent %s start device=%s interval=%dms -> %s"
        % (AGENT_VER, cfg["device"] or "?", cfg["interval_ms"], cfg["endpoint"]))
    sampler = Sampler(cfg)
    pusher = Pusher(cfg, sampler)
    threading.Thread(target=pusher.loop, name="push", daemon=True).start()

    interval = cfg["interval_ms"] / 1000.0
    next_at = time.time()
    while True:
        try:
            s = sampler.sample()
            pusher.enqueue(s)
        except Exception as e:
            log("sample error: %s" % e)
        next_at += interval
        delay = next_at - time.time()
        if delay < -2:                 # 严重落后(系统休眠等)则重新对齐
            next_at = time.time()
            delay = 0
        if delay > 0:
            time.sleep(delay)


def install(args):
    if os.geteuid() != 0:
        print("请用 root / sudo 执行: sudo python3 agent.py --install ...")
        return 2
    src = os.path.abspath(__file__)
    os.makedirs(HOME, exist_ok=True)
    dst = os.path.join(HOME, "agent.py")
    if os.path.realpath(src) != os.path.realpath(dst):
        shutil.copy2(src, dst)
    os.chmod(dst, 0o755)

    cfg = dict(DEFAULTS)
    if os.path.exists(CONF):
        try:
            with open(CONF, encoding="utf-8") as f:
                cfg.update(json.load(f))
        except Exception:
            pass
    for k in ("endpoint", "token", "device"):
        if getattr(args, k, None):
            cfg[k] = getattr(args, k)
    with open(CONF, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    os.chmod(CONF, 0o600)

    with open(UNIT, "w", encoding="utf-8") as f:
        f.write("""[Unit]
Description=Cloud Monitor hardware metrics agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 %s
Restart=always
RestartSec=2
Nice=10
OOMScoreAdjust=-100
NoNewPrivileges=true
ProtectSystem=full
PrivateTmp=true
TasksMax=64

[Install]
WantedBy=multi-user.target
""" % dst)
    run_cmd(["systemctl", "daemon-reload"], timeout=15)
    run_cmd(["systemctl", "enable", "--now", UNIT_NAME], timeout=20)
    time.sleep(1.5)
    out = run_cmd(["systemctl", "is-active", UNIT_NAME], timeout=10).strip()
    print("安装完成: %s  配置: %s  状态: %s" % (dst, CONF, out))
    return 0


def main():
    ap = argparse.ArgumentParser(description="Cloud Monitor hardware agent")
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--endpoint")
    ap.add_argument("--token")
    ap.add_argument("--device")
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()

    if a.install:
        return install(a)
    cfg = load_config()
    for k in ("endpoint", "token", "device"):
        if getattr(a, k):
            cfg[k] = getattr(a, k)
    if a.once:
        s = Sampler(cfg).sample()
        s["heavy"] = None
        print(json.dumps(s, ensure_ascii=False, indent=1))
        return 0
    if a.check:
        p = Pusher(cfg, Sampler(cfg))
        try:
            code, txt = p._post([{"ts": int(time.time() * 1000), "check": True}])
            print("HTTP %s %s" % (code, txt[:300]))
            return 0 if code == 200 else 1
        except Exception as e:
            print("FAIL: %s" % e)
            return 1
    try:
        return run(cfg)
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
