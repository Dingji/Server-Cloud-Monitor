#!/usr/bin/env python
"""Self-test the portable panel package on the source box, in an isolated prefix.

Uploads deploy/cloud-monitor-panel to /tmp, runs install.sh with a private
--prefix/--data-dir/--port/--service and --no-nginx --no-systemd (so the live
cloud-monitor.service, /opt/cloud-monitor and /var/lib/cloud-panel are untouched),
boots the panel in the foreground, smoke-tests login + ops endpoints, then tears
everything down.

  python tools/test_panel_package.py            # run + cleanup
  python tools/test_panel_package.py --keep     # leave it running for inspection
  python tools/test_panel_package.py --no-start # install only, do not boot
"""
import os
import posixpath
import subprocess
import sys
import tempfile
import time

import paramiko

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
PKG = os.path.join(REPO, "deploy", "cloud-monitor-panel")

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
PASS = os.environ.get("SM_PASS", "Ladridibiciclette!")

PREFIX = "/opt/cm-pack-test"
DATA = "/var/lib/cm-pack-test"
PORT = 9101
SERVICE = "cm-pack-test"
PW = "PackTest#2026-selftest"
REMOTE_PKG = "/tmp/cm-pack-pkg-test"
BOOT_LOG = "/tmp/cm-pack-boot.log"

FAILS = []
PIDS = []


def check(label, ok, extra=""):
    print(("  OK   " if ok else "  BAD  ") + label + ((" — " + extra) if extra else ""))
    if not ok:
        FAILS.append(label)


def connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    key = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
    if os.path.exists(key):
        try:
            c.connect(HOST, username=USER, key_filename=key, timeout=25, banner_timeout=25,
                      auth_timeout=25, look_for_keys=False, allow_agent=False)
            return c
        except paramiko.SSHException:
            pass
    c.connect(HOST, username=USER, password=PASS, timeout=25,
              banner_timeout=25, auth_timeout=25, look_for_keys=False, allow_agent=False)
    return c


def run(c, cmd, timeout=240):
    _, out, err = c.exec_command(cmd, timeout=timeout)
    o = out.read().decode("utf-8", "replace")
    e = err.read().decode("utf-8", "replace")
    return out.channel.recv_exit_status(), o, e


def fire(c, cmd):
    """Start a long-lived background process without waiting for the channel to close.

    The caller's own redirections must win, so the detach redirections go on a
    wrapping command group rather than being appended to the same command.
    """
    chan = c.get_transport().open_channel("session")
    chan.settimeout(5)
    chan.exec_command("{ " + cmd + " ; } < /dev/null > /dev/null 2>&1 & exit 0")
    time.sleep(1.5)
    chan.close()


def kill_by_port(c, port, expect_cwd):
    """Kill whichever process holds `port`, but only if its cwd is the test prefix.

    pkill -f cannot be used: the panel is started as `cd PREFIX && node server/index.js`,
    so its argv holds no prefix at all.
    """
    cmd = (f"PID=$(ss -ltnp 2>/dev/null | sed -n 's/.*:{port} .*pid=\\([0-9]*\\).*/\\1/p' | head -1); "
           f"if [ -n \"$PID\" ] && [ \"$(readlink /proc/$PID/cwd 2>/dev/null)\" = \"{expect_cwd}\" ]; then "
           f"kill \"$PID\" 2>/dev/null && echo KILLED_$PID; else echo NO_MATCH; fi")
    return run(c, cmd)


def main():
    keep = "--keep" in sys.argv
    no_start = "--no-start" in sys.argv
    c = connect()

    print("== 0. 前置：清掉上一轮可能残留的测试实例，确认与生产互不重叠 ==")
    code, out, _ = kill_by_port(c, PORT, PREFIX)
    if "KILLED" in out:
        print("   残留实例已终止:", out.strip())
    run(c, f"sleep 1; rm -rf {PREFIX} {DATA} {REMOTE_PKG} {REMOTE_PKG}.tar.gz {BOOT_LOG}")
    _, out, _ = run(c, f"ls -d {PREFIX} {DATA} {REMOTE_PKG} 2>/dev/null | wc -l; ss -ltn | grep -c ':{PORT} '")
    lines = [x.strip() for x in out.split()]
    check("测试前缀/数据目录不存在且端口空闲",
          len(lines) >= 2 and lines[0] == "0" and lines[1] == "0", out.strip()[:120])
    _, prod, _ = run(c, "systemctl is-active cloud-monitor; echo ---; md5sum /opt/cloud-monitor/config.json")
    print("   生产状态（全程不应变化）:", prod.strip().replace("\n", " | "))

    print("== 1. 上传包 ==")
    local_tgz = os.path.join(tempfile.gettempdir(), "cm-pack-pkg.tar.gz")
    subprocess.run(["tar", "-czf", local_tgz, "-C", PKG,
                    "app", "vendor", "install.sh", "uninstall.sh", "README.md"], check=True)
    size = os.path.getsize(local_tgz)
    sftp = c.open_sftp()
    sftp.put(local_tgz, REMOTE_PKG + ".tar.gz")
    sftp.close()
    run(c, f"rm -rf {REMOTE_PKG} && mkdir -p {REMOTE_PKG} && tar -xzf {REMOTE_PKG}.tar.gz -C {REMOTE_PKG}")
    _, out, _ = run(c, f"ls {REMOTE_PKG} && du -sh {REMOTE_PKG}/app {REMOTE_PKG}/vendor")
    check("包已解出（app + vendor + 脚本）", "install.sh" in out and "app" in out, f"{size/1024/1024:.1f}MB")

    print("== 2. 运行 install.sh（隔离参数，不写 nginx / 不建 systemd）==")
    cmd = (f"cd {REMOTE_PKG} && bash install.sh --prefix {PREFIX} --data-dir {DATA} "
           f"--port {PORT} --service {SERVICE} --no-nginx --no-systemd --password '{PW}' 2>&1")
    code, out, err = run(c, cmd, timeout=600)
    print("   " + out.replace("\n", "\n   ").rstrip()[:3000])
    check("install.sh 退出码 0", code == 0, f"rc={code}")
    _, out, _ = run(c, (f"test -f {PREFIX}/server/index.js && test -f {PREFIX}/server/ops/watchdog.js && "
                        f"test -f {PREFIX}/public/index.html && test -d {PREFIX}/node_modules/express && "
                        f"test -f {PREFIX}/keys/pty_key && test -d {DATA} && echo ALL_PRESENT || echo MISSING"))
    check("代码/ops/静态页/依赖/终端密钥/数据目录都在位", "ALL_PRESENT" in out, out.strip())
    _, out, _ = run(c, f"find {PREFIX}/node_modules -name '*.node' | head -3; echo NATIVE_CHECK_DONE")
    check("依赖里没有原生 .node", "NATIVE_CHECK_DONE" in out and ".node" not in out.split("NATIVE_CHECK_DONE")[0])

    print("== 3. 启动面板（独立进程，非 systemd）==")
    if not no_start:
        run(c, f"rm -f {BOOT_LOG}")
        env = f"CM_DATA_DIR={DATA} CM_PORT={PORT} CM_BIND=127.0.0.1 CM_PASSWORD='{PW}'"
        fire(c, f"cd {PREFIX} && exec env {env} node server/index.js >> {BOOT_LOG} 2>&1")
        listening = False
        for _ in range(30):
            code, out, _ = run(c, f"ss -ltn | grep -c ':{PORT} '")
            if out.strip().startswith("1"):
                listening = True
                break
            time.sleep(1)
        check(f"端口 {PORT} 已监听", listening, "")
        _, boot, _ = run(c, f"cat {BOOT_LOG}")
        print("   启动日志: " + boot.replace("\n", " / ").strip()[:400])
        # 必须是这一轮新起的进程在监听（防止上一轮残留实例冒充）
        check("监听者就是本次安装的副本",
              f"Cloud Monitor listening on http://127.0.0.1:{PORT}/panel" in boot, boot.strip()[:120])
        _, cwd, _ = run(c, f"readlink /proc/$(ss -ltnp | sed -n 's/.*:{PORT} .*pid=\\([0-9]*\\).*/\\1/p' | head -1)/cwd")
        check("进程工作目录就是测试前缀", cwd.strip() == PREFIX, cwd.strip())
        # node:sqlite 会打正常的 ExperimentalWarning，只认真正的失败串
        check("ops 模块已挂载、指标入库未报错",
              "ops modules not mounted" not in boot and "[metricsstore] node:sqlite 不可用" not in boot,
              boot.strip()[:200])

        print("== 4. 端点冒烟 ==")
        api = f"http://127.0.0.1:{PORT}/panel/api"
        code, out, _ = run(c, f"curl -s -o /dev/null -w '%{{http_code}}' http://127.0.0.1:{PORT}/panel/public/index.html")
        check("Web 壳可取", out.strip() == "200", "HTTP " + out.strip())
        code, out, _ = run(c, f"curl -s -X POST {api}/login -H 'Content-Type: application/json' -d '{{\"password\":\"{PW}\"}}'")
        token = out.split('"token":"')[1].split('"')[0] if '"token":"' in out else ""
        check("口令登录拿到 token", len(token) > 20, out[:80])
        hdr = f"-H 'Authorization: Bearer {token}'"
        for path, needle, label in [
            ("/me", '"refreshMs"', "面板基本信息 /me"),
            ("/ops/overview", '"ok":true', "ops 总览（九模块聚合）"),
            ("/ops/sec/checks", '"fixes"', "安全巡检目录"),
            ("/ops/disk/overview", '"mounts"', "磁盘总览"),
            ("/ops/watchdog", '"items"', "服务看护"),
            ("/ops/remote", '"runner"', "远程采集"),
            ("/ops/files/list?dir=/tmp", "[", "文件管理器"),
            ("/ops/uptime/tasks", '"types"', "拨测任务目录"),
            ("/ops/alerts/rules", '"metrics"', "告警规则引擎"),
            ("/ops/hosts", '"roles"', "纳管主机台账"),
        ]:
            code, out, _ = run(c, f"curl -s {hdr} '{api}{path}'")
            check(label, needle in out, out[:90].replace("\n", " ") if needle not in out else "")

        # vendor 静态资源是从解包出来的 node_modules 里读的，能取到就证明依赖落位正确
        for path, label in [("/panel/vendor/chart.js", "vendor chart.js"),
                            ("/panel/vendor/xterm.js", "vendor xterm.js"),
                            ("/panel/vendor/xterm.css", "vendor xterm.css")]:
            code, out, _ = run(c, f"curl -s -o /dev/null -w '%{{http_code}} %{{size_download}}' http://127.0.0.1:{PORT}{path}")
            parts = out.split()
            check(label, parts and parts[0] == "200" and len(parts) > 1 and int(parts[1]) > 1000, out.strip())

        # WebSocket：实时指标与 Web 终端都依赖它，握手必须 101
        ws_cmd = (f"K=$(head -c 16 /dev/urandom | base64); "
                  f"curl -s --max-time 4 -o /dev/null -w '%{{http_code}}' "
                  f"-H 'Connection: Upgrade' -H 'Upgrade: websocket' "
                  f"-H 'Sec-WebSocket-Version: 13' -H \"Sec-WebSocket-Key: $K\" "
                  f"\"http://127.0.0.1:{PORT}/panel/ws?token={token}\"")
        code, out, _ = run(c, ws_cmd)
        check("WebSocket /panel/ws 握手 101", out.strip() == "101", "HTTP " + out.strip())

        print("== 5. 与生产隔离性复核 ==")
        _, prod2, _ = run(c, "systemctl is-active cloud-monitor; echo ---; md5sum /opt/cloud-monitor/config.json")
        check("生产服务与生产 config 未被触碰", prod2.strip() == prod.strip(),
              (prod2.strip()[:60] + " != " + prod.strip()[:60]) if prod2.strip() != prod.strip() else "")

    if keep:
        print(f"\n--keep：面板仍在跑（{PREFIX}，端口 {PORT}）。"
              f"停止：kill $(ss -ltnp | sed -n 's/.*:{PORT} .*pid=\\([0-9]*\\).*/\\1/p' | head -1)")
        return 0

    print("== 6. 清理 ==")
    _, out, _ = kill_by_port(c, PORT, PREFIX)
    print("   进程:", out.strip() or "无")
    time.sleep(1)
    run(c, f"rm -rf {PREFIX} {DATA} {REMOTE_PKG} {REMOTE_PKG}.tar.gz {BOOT_LOG}")
    _, out, _ = run(c, f"ls -d {PREFIX} {DATA} {REMOTE_PKG} 2>/dev/null | wc -l; ss -ltn | grep -c ':{PORT} '")
    lines = [x.strip() for x in out.split()]
    gone = len(lines) >= 2 and lines[0] == "0" and lines[1] == "0"
    check("测试目录与进程已清除（端口已释放）", gone, out.strip()[:120])
    _, prod3, _ = run(c, "systemctl is-active cloud-monitor; echo ---; md5sum /opt/cloud-monitor/config.json")
    check("清理后生产仍与基线一致", prod3.strip() == prod.strip(), prod3.strip()[:80])

    print("\n==== " + ("全部符合预期" if not FAILS else "有 %d 项不符合预期: %s" % (len(FAILS), ", ".join(FAILS))) + " ====")
    return 0 if not FAILS else 1


if __name__ == "__main__":
    sys.exit(main())
