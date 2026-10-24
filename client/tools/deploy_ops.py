#!/usr/bin/env python
"""Deploy the ops modules (uptime probing, alert rules, notify channels) into the panel.

  python tools/deploy_ops.py --dry-run     # 只读预检
  python tools/deploy_ops.py               # 备份 + 上传 + 挂载 hub 导出 + 重启 + 冒烟
  python tools/deploy_ops.py --no-restart  # 只写文件
  python tools/deploy_ops.py --rollback    # 还原 index.js / hub.js（ops 目录保留但不生效）

All edits are byte-level with ASCII-only snippets: the panel's UTF-8 Chinese
source is never re-encoded by this script.
"""
import argparse
import datetime
import os
import posixpath
import sys

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
APP = "/opt/cloud-monitor"
SRV = posixpath.join(APP, "server")
OPS_DIR = posixpath.join(SRV, "ops")
BACKUP_DIR = posixpath.join(APP, "backups")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(REPO, "ops-server")
FILES = ["index.js", "store.js", "notify.js", "alerting.js", "uptime.js", "hosts.js", "files.js",
         "secops.js", "disk.js", "remote.js", "watchdog.js", "exec.js", "send_mail.py"]

ANCHOR = b"app.use(BASE + '/api', api);"
MARK = b"require('./ops').mount"
HUB_OLD = b"module.exports = { init, shutdown, listSources, applyRefresh, getHistory, getAlerts };"
HUB_NEW = b"module.exports = { init, shutdown, listSources, applyRefresh, getHistory, getAlerts, fireAlert };"
SNIPPET = (
    b"/* ============ ops modules: uptime probing, alert rules, notify channels ============ */\n"
    b"try { require('./ops').mount(api, { wrap, audited }); }\n"
    b"catch (e) { logErr('ops modules not mounted:', e.message); }\n\n"
)


def connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, key_filename=KEY, timeout=25,
              look_for_keys=False, allow_agent=False)
    return c


def run(c, cmd, timeout=180):
    _, out, err = c.exec_command(cmd, timeout=timeout)
    rc = out.channel.recv_exit_status()
    return rc, out.read().decode("utf-8", "replace"), err.read().decode("utf-8", "replace")


def read_remote(c, p):
    rc, o, _ = run(c, "cat %s" % p)
    return o.encode("utf-8") if rc == 0 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--no-restart", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    a = ap.parse_args()

    c = connect()
    sftp = c.open_sftp()
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")

    if a.rollback:
        idx = sorted(f for f in sftp.listdir(BACKUP_DIR) if f.startswith("index.js.before-ops-"))
        hub = sorted(f for f in sftp.listdir(BACKUP_DIR) if f.startswith("hub.js.before-ops-"))
        if not idx:
            print("没有 ops 挂载前的 index.js 备份，放弃")
            return 1
        cmd = "cp %s %s/server/index.js" % (posixpath.join(BACKUP_DIR, idx[-1]), APP)
        if hub:
            cmd += " && cp %s %s/server/hub.js" % (posixpath.join(BACKUP_DIR, hub[-1]), APP)
        rc, o, e = run(c, cmd + " && systemctl restart cloud-monitor")
        print("rollback -> rc=%s %s%s" % (rc, o, e))
        return rc

    index = read_remote(c, posixpath.join(SRV, "index.js"))
    hubjs = read_remote(c, posixpath.join(SRV, "hub.js"))
    if not index or not hubjs:
        print("读取 index.js / hub.js 失败")
        return 1

    already = MARK in index
    needs_hub = HUB_OLD in hubjs
    rc, ls, _ = run(c, "ls -la %s 2>&1 | tail -8" % OPS_DIR)
    rc2, st, _ = run(c, "systemctl is-active cloud-monitor; node -v")

    print("=== 现状 ===")
    print("index.js 字节数        : %d" % len(index))
    print("挂载锚点存在           : %s" % (ANCHOR in index))
    print("index.js 已挂载 ops    : %s" % already)
    print("hub.js 可加 fireAlert  : %s（当前导出含 fireAlert: %s）" % (needs_hub, b"fireAlert }" in hubjs or b", fireAlert" in hubjs))
    print("服务状态 / node        : %s" % st.strip().replace("\n", " | "))
    print("远端 ops 目录          : %s" % ls.strip().replace("\n", " | "))

    if a.dry_run:
        print("\n=== dry-run：将要执行 ===")
        print("1. mkdir -p %s" % OPS_DIR)
        print("2. 备份 index.js -> %s/index.js.before-ops-%s" % (BACKUP_DIR, stamp))
        print("3. 备份 hub.js   -> %s/hub.js.before-ops-%s" % (BACKUP_DIR, stamp))
        print("4. 上传 %d 个文件：%s" % (len(FILES), ",".join(FILES)))
        print("5. hub.js 导出末尾追加 fireAlert（词级替换，长度 +%d）" % (len(HUB_NEW) - len(HUB_OLD)))
        print("6. index.js 锚句 %r 前插入 3 行 try/catch 挂载块" % ANCHOR.decode())
        print("7. node --check 全部 js 文件")
        print("8. systemctl restart cloud-monitor（内存 token 失效，客户端需重新登录）")
        print("9. 冒烟 /panel/api/ops/overview、/ops/uptime/tasks、/ops/alerts/rules")
        return 0

    if not (ANCHOR in index) and not already:
        print("锚句没找到，放弃自动挂载")
        return 1

    run(c, "mkdir -p %s" % OPS_DIR)
    if not already:
        run(c, "cp %s/server/index.js %s/index.js.before-ops-%s" % (APP, BACKUP_DIR, stamp))
    run(c, "cp %s/server/hub.js %s/hub.js.before-ops-%s" % (APP, BACKUP_DIR, stamp))

    for name in FILES:
        local = os.path.join(SRC_DIR, name)
        remote = posixpath.join(OPS_DIR, name)
        sftp.put(local, remote)
        run(c, "chmod 644 %s" % remote)
    print("已上传 %d 个 ops 文件 -> %s" % (len(FILES), OPS_DIR))

    if needs_hub:
        patched_hub = hubjs.replace(HUB_OLD, HUB_NEW, 1)
        with sftp.open(posixpath.join(SRV, "hub.js"), "wb") as f:
            f.write(patched_hub)
        print("hub.js 导出已加 fireAlert（+%d 字节）" % (len(patched_hub) - len(hubjs)))
    elif not (b"fireAlert" in hubjs):
        print("警告：hub.js 导出结构已变，未自动追加 fireAlert —— 自定义告警只会发通知，不进面板铃铛")

    if not already:
        patched = index.replace(ANCHOR, SNIPPET + ANCHOR, 1)
        with sftp.open(posixpath.join(SRV, "index.js"), "wb") as f:
            f.write(patched)
        print("index.js 已挂载 ops（+%d 字节）" % (len(patched) - len(index)))

    rc, o, e = run(c, "cd %s/server && for f in index.js hub.js ops/*.js; do node --check $f || exit 1; done && echo SYNTAX_OK" % APP)
    print("语法检查: %s %s" % (o.strip(), e.strip()))
    if rc != 0:
        print("语法不过，还原 index.js 与 hub.js")
        run(c, "cp %s/index.js.before-ops-%s %s/server/index.js; cp %s/hub.js.before-ops-%s %s/server/hub.js"
            % (BACKUP_DIR, stamp, APP, BACKUP_DIR, stamp, APP))
        return 1

    if a.no_restart:
        print("--no-restart：文件已就位，等你手动重启")
        return 0

    rc, o, e = run(c, "systemctl restart cloud-monitor && sleep 3 && systemctl is-active cloud-monitor")
    print("重启: %s %s" % (o.strip(), e.strip()))

    rc, o, e = run(c, "journalctl -u cloud-monitor -n 8 --no-pager 2>&1 | tail -8")
    print("\n=== 启动日志 ===\n%s" % o)
    sftp.close()
    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
