#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""远程健康基线采集（testing 角色）。

通过 tools/sh.py（SSH，只读命令）+ 本机 HTTP 探测，采集 8.153.153.176 面板与主机的
健康基线快照，写入 tests/baseline.md。后续任何「面板坏了 / 行为变了」的疑问，
都先和这份基线比对（例如 502、进程消失、限流策略变化、证书过期等）。

只执行**只读**命令：不 restart、不 systemctl stop、不写任何文件。
每条命令独立执行并记录 rc，超时由 paramiko 控制。

用法：python tests/collect_baseline.py
"""
import importlib.util
import json
import os
import sys
import time
import urllib.request
import urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TESTS_DIR = os.path.join(ROOT, "tests")
PANEL_URL = os.environ.get("SM_PANEL_URL", "http://8.153.153.176/panel")
LOCAL_API = "http://127.0.0.1:9000/panel/api"  # 面板监听 127.0.0.1:9000（upstream/config.json）

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # 直连，绕开本机代理

# (标签, 远程命令)
CMDS = [
    ("时间与内核", "date -Is; uptime; uname -a"),
    ("发行版", "grep -E '^(NAME|VERSION)=' /etc/os-release"),
    ("CPU / 内存", "nproc; free -m | head -3"),
    ("磁盘用量", "df -hT -x tmpfs -x devtmpfs -x squashfs | head -15"),
    ("面板服务状态", "systemctl status cloud-monitor --no-pager -l 2>&1 | head -25"),
    ("面板进程", "ps -ef | grep -E 'node .*cloud-monitor|node .*index.js' | grep -v grep"),
    ("监听端口", "ss -ltnp | head -20"),
    ("Node 版本", "/usr/local/bin/node -v 2>/dev/null || node -v"),
    ("面板版本", "cat /opt/cloud-monitor/package.json 2>/dev/null | head -20"),
    ("面板最近日志", "journalctl -u cloud-monitor --no-pager -n 25 2>&1 | tail -25"),
    ("面板数据目录", "du -sh /var/lib/cloud-panel/* 2>/dev/null | head -15"),
    ("中继采集器", "systemctl list-units --all --no-pager | grep -E 'relay' | head -25"),
    ("中继采集器日志", "journalctl -u relay-collector --no-pager -n 8 2>&1 | tail -8; systemctl status relay-selfheal.timer relay-alert.timer --no-pager 2>&1 | head -12"),
    ("Nginx 面板配置", "grep -rn -E 'proxy_pass|location|listen ' /etc/nginx/ 2>/dev/null | grep -E '9000|panel|listen' | head -20"),
    ("数据库引擎进程", "ps -ef | grep -E 'postgres|mysqld|redis-server|mongod' | grep -v grep | head -10"),
    ("系统服务失败项", "systemctl --failed --no-pager --plain 2>/dev/null | head -10"),
]


def sh():
    p = os.path.join(ROOT, "tools", "sh.py")
    spec = importlib.util.spec_from_file_location("sm_sh", p)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def http_probe(url, timeout=20):
    req = urllib.request.Request(url)
    try:
        with OPENER.open(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")[:400]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:400]
    except Exception as e:
        return -1, repr(e)


def main():
    shmod = sh()
    rows = []
    for label, cmd in CMDS:
        t0 = time.time()
        try:
            rc, out, err = shmod.run(cmd, timeout=90)
        except Exception as e:
            rc, out, err = -1, "", repr(e)
        rows.append({"label": label, "cmd": cmd, "rc": rc,
                     "out": (out or "").strip(), "err": (err or "").strip(),
                     "sec": round(time.time() - t0, 1)})
        print("[sh] %-16s rc=%s %.1fs" % (label, rc, rows[-1]["sec"]), flush=True)

    # HTTP 探测（本机直连面板端口 + 公网入口）
    # ⚠️ 服务器本机端口必须通过 SSH curl 探测：在本机请求 127.0.0.1:9000 打到的是
    #    **本机**的 9000 端口，不是远程面板（曾误判为服务器，见 git 历史）。
    probes = [
        ("公网 /panel/api/health", PANEL_URL + "/api/health", "local"),
        ("公网 /panel/public/index.html", PANEL_URL + "/public/index.html", "local"),
        ("服务器本机 127.0.0.1:9000/panel/api/health", LOCAL_API + "/health", "ssh"),
    ]
    prows = []
    for label, url, via in probes:
        if via == "ssh":
            rc, out, err = shmod.run("curl -s -o /dev/null -w '%{http_code}' " + url, timeout=30)
            rc2, body, _ = shmod.run("curl -s " + url, timeout=30)
            st = int(out.strip()) if out.strip().isdigit() else -1
            prows.append({"label": label, "url": url, "status": st, "body": (body or "")[:400],
                          "via": "SSH curl（远程机本机端口）"})
        else:
            st, body = http_probe(url)
            prows.append({"label": label, "url": url, "status": st, "body": body, "via": "本机直连"})
        print("[http] %-42s -> %s" % (label, prows[-1]["status"]), flush=True)

    # 通过 SSH 在服务器本机拿一份机器可读健康摘要（无需 token）
    summary = {}
    for p in prows:
        if p["via"].startswith("SSH") and p["status"] == 200:
            try:
                summary["health"] = json.loads(p["body"])
            except Exception:
                pass

    ts = time.strftime("%Y-%m-%d %H:%M:%S %z")
    lines = []
    a = lines.append
    a("# 远程健康基线（tests/baseline.md）")
    a("")
    a("> 自动生成：`python tests/collect_baseline.py`（testing 角色，全部只读命令）。")
    a("> 用途：面板/主机任何异常时的**回归比对基准**；也用于确认复刻 App 所依赖的运行时事实"
      "（监听端口、限流、引擎可用性）没有变化。")
    a("")
    a("- 采集时间：%s" % ts)
    a("- 目标：`%s`（SSH root@8.153.153.176，key: `~/.ssh/oc_relay_ed25519`）" % PANEL_URL)
    a("- 面板监听：`127.0.0.1:9000`（`/panel` base），前置 Nginx 暴露 80 端口")
    a("")
    a("## HTTP 探测")
    a("")
    a("| 探测点 | URL | 经由 | 状态 | 响应摘要 |")
    a("|---|---|---|---|---|")
    for p in prows:
        a("| %s | `%s` | %s | %s | `%s` |" % (p["label"], p["url"], p["via"], p["status"],
                                              (p["body"] or "").replace("\n", " ")[:100].replace("|", "\\|")))
    a("")
    a("注意：本机（Windows）探测必须**绕过系统代理**（如 127.0.0.1:7890），"
      "否则代理对裸 HTTP 返回空 502 —— 那是本机代理问题，不是服务器故障。")
    a("")
    if summary.get("health"):
        a("### 机器可读健康摘要（服务器本机端口）")
        a("")
        a("```json")
        a(json.dumps(summary["health"], ensure_ascii=False, indent=1))
        a("```")
        a("")
    a("## 远程命令基线")
    a("")
    for r in rows:
        a("### %s" % r["label"])
        a("")
        a("```")
        a("$ " + r["cmd"])
        body = r["out"] if r["out"] else "(无输出)"
        if r["err"]:
            body += "\n[stderr] " + r["err"][:500]
        a(body[:4000])
        a("```")
        a("")
        a("rc=%s，耗时 %ss" % (r["rc"], r["sec"]))
        a("")

    a("## 回归比对要点")
    a("")
    a("1. `cloud-monitor.service` 必须 `active (running)`，且 `ps` 里有 `node ... server/index.js`；")
    a("2. 面板进程必须监听 `127.0.0.1:9000`，Nginx 暴露 `/panel`；")
    a("3. `/panel/api/health` 返回 `{ok:true,name:'cloud-monitor',version:'1.0.0',ts}`；")
    a("4. API 限流 1800 次/分钟/IP（`server/auth.js`），登录失败 8 次锁 10 分钟 —— 自动化测试必须限速；")
    a("5. 引擎可用性（postgres/redis/mongo/docker）会随服务器状态变化，"
      "App 与测试都要按「引擎不可用 → 展示 `{error,output}`」处理，不得当异常崩溃。")
    a("")

    out = os.path.join(TESTS_DIR, "baseline.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("written: %s" % out, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
