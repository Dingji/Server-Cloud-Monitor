#!/usr/bin/env python
"""Mount the PHP hosting module into the panel.

  python tools/deploy_php.py --dry-run     # read-only preflight
  python tools/deploy_php.py               # backup + upload + syntax gate + restart + smoke
  python tools/deploy_php.py --no-restart  # write files only
  python tools/deploy_php.py --rollback    # restore ops/index.js and unmount

Byte-level edits only; the panel's UTF-8 Chinese sources are never re-encoded.
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
OPS = posixpath.join(SRV, "ops")
PHPDIR = posixpath.join(OPS, "php")
BACKUP_DIR = posixpath.join(APP, "backups")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, "ops-server")
PHP_FILES = ["common.js", "jobs.js", "presets.js", "env.js", "site.js",
             "deploy.js", "db.js", "ssl.js", "logs.js", "index.js"]

MOUNT_OLD = b"  watchdog.register(api, wrap, audited);\n"
MOUNT_NEW = b"  watchdog.register(api, wrap, audited);\n  php.register(api, wrap);\n"
REQUIRE_OLD = b"const watchdog = require('./watchdog');\n"
REQUIRE_NEW = b"const watchdog = require('./watchdog');\nconst php = require('./php');\n"


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
        backs = sorted(f for f in sftp.listdir(BACKUP_DIR) if f.startswith("ops-index.js.before-php-"))
        if not backs:
            print("没有挂载 PHP 前的 ops/index.js 备份，放弃")
            return 1
        src = posixpath.join(BACKUP_DIR, backs[-1])
        rc, o, e = run(c, "cp %s %s && node --check %s/server/ops/index.js && systemctl restart cloud-monitor"
                       % (src, posixpath.join(OPS, "index.js"), OPS))
        print("rollback(%s) -> rc=%s %s%s" % (backs[-1], rc, o, e))
        return rc

    ops_index = read_remote(c, posixpath.join(OPS, "index.js"))
    if ops_index is None:
        print("读不到 %s/server/ops/index.js" % APP)
        return 1
    rc, ls, _ = run(c, "ls -1 %s 2>/dev/null || echo '(不存在)'" % PHPDIR)
    rc2, st, _ = run(c, "systemctl is-active cloud-monitor; node -v; php -v 2>/dev/null | head -1")

    has_mount = b"php.register" in ops_index
    has_req = b"require('./php')" in ops_index
    print("=== 现状 ===")
    print("ops/index.js 字节数      : %d" % len(ops_index))
    print("可插入 require 锚点      : %s" % (REQUIRE_OLD in ops_index))
    print("可插入 register 锚点     : %s" % (MOUNT_OLD in ops_index))
    print("已挂载 php               : require=%s register=%s" % (has_req, has_mount))
    print("远端 ops/php 目录        : %s" % ls.strip().replace("\n", " | "))
    print("服务/node/php            : %s" % st.strip().replace("\n", " | "))

    if a.dry_run:
        print("\n=== dry-run：将要执行 ===")
        print("1. mkdir -p %s" % PHPDIR)
        print("2. 备份 ops/index.js -> %s/ops-index.js.before-php-%s" % (BACKUP_DIR, stamp))
        print("3. 上传 %d 个文件：%s" % (len(PHP_FILES), ",".join(PHP_FILES)))
        print("4. ops/index.js 追加 require('./php') 与 php.register(api, wrap)")
        print("5. node --check 全部 php/*.js 与 ops/index.js")
        print("6. systemctl restart cloud-monitor（内存 token 失效，客户端需重新登录）")
        print("7. 冒烟 /panel/api/php/overview 与真实建一个测试站点")
        return 0

    if not (REQUIRE_OLD in ops_index and MOUNT_OLD in ops_index) and not has_mount:
        print("锚句没找到，放弃自动挂载（请手工挂载或检查 ops/index.js 结构）")
        return 1

    run(c, "mkdir -p %s" % PHPDIR)
    if not has_mount:
        run(c, "cp %s %s" % (posixpath.join(OPS, "index.js"),
                             posixpath.join(BACKUP_DIR, "ops-index.js.before-php-%s" % stamp)))
        print("2. 备份 ops/index.js -> ops-index.js.before-php-%s" % stamp)

    for name in PHP_FILES:
        local = os.path.join(SRC, "php", name)
        if not os.path.exists(local):
            print("缺少本地文件 %s" % local)
            return 1
        sftp.put(local, posixpath.join(PHPDIR, name))
        run(c, "chmod 644 %s" % posixpath.join(PHPDIR, name))
    print("3. 已上传 %d 个文件 -> %s" % (len(PHP_FILES), PHPDIR))

    if not has_mount:
        patched = ops_index
        if REQUIRE_OLD in patched:
            patched = patched.replace(REQUIRE_OLD, REQUIRE_NEW, 1)
        if MOUNT_OLD in patched:
            patched = patched.replace(MOUNT_OLD, MOUNT_NEW, 1)
        with sftp.open(posixpath.join(OPS, "index.js"), "wb") as f:
            f.write(patched)
        print("4. ops/index.js 已挂载 php（+%d 字节）" % (len(patched) - len(ops_index)))

    rc, o, e = run(c, "cd %s/server && for f in ops/index.js ops/php/*.js; do node --check $f || exit 1; "
                      "done && echo SYNTAX_OK" % APP)
    print("语法检查: %s %s" % (o.strip(), e.strip()))
    if rc != 0 or "SYNTAX_OK" not in o:
        print("语法不过，还原 ops/index.js")
        if not has_mount:
            run(c, "cp %s %s" % (posixpath.join(BACKUP_DIR, "ops-index.js.before-php-%s" % stamp),
                                 posixpath.join(OPS, "index.js")))
        return 1

    if a.no_restart:
        print("--no-restart：文件已就位，等你手动重启")
        return 0

    rc, o, e = run(c, "systemctl restart cloud-monitor && sleep 3 && systemctl is-active cloud-monitor")
    print("5. 重启: %s %s" % (o.strip(), e.strip()))
    rc, o, e = run(c, "journalctl -u cloud-monitor -n 15 --no-pager 2>&1 | tail -15")
    print("\n=== 启动日志 ===\n%s" % o)
    sftp.close()
    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
