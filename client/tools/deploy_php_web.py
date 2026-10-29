#!/usr/bin/env python
"""Add the "PHP 站点" page to the panel's own web UI.

  python tools/deploy_php_web.py --dry-run     # read-only preflight
  python tools/deploy_php_web.py               # backup + upload php.js + patch app.js/index.html
  python tools/deploy_php_web.py --rollback    # restore app.js / index.html (php.js stays, unreferenced)

app.js and index.html are patched in place on the server, never from a local
copy: the local upstream/ snapshot is older than what is running, and
re-uploading it would silently revert other people's work. Insertions are
byte-level; existing UTF-8 Chinese text is never re-encoded.
"""
import argparse
import datetime
import io
import os
import posixpath
import re
import sys

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
APP = "/opt/cloud-monitor"
PUB = posixpath.join(APP, "public")
BACKUP_DIR = posixpath.join(APP, "backups")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAGE_SRC = os.path.join(REPO, "panel-web", "php.js")

NAV_ANCHOR = b"{ id: 'web',"
LAZY_ANCHOR = b"web: 'js/pages/web.js',"
NAV_LABEL = "PHP 站点".encode("utf-8")
BUILD_RE = re.compile(rb"(CM\.BUILD\s*=\s*)(['\"])([^'\"]*)\2")


def connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, key_filename=KEY, timeout=25,
              look_for_keys=False, allow_agent=False)
    return c


def run(c, cmd, timeout=120):
    _, out, err = c.exec_command(cmd, timeout=timeout)
    rc = out.channel.recv_exit_status()
    return rc, out.read().decode("utf-8", "replace"), err.read().decode("utf-8", "replace")


def read_remote(sftp, p):
    try:
        with sftp.open(p, "rb") as f:
            return f.read()
    except IOError:
        return None


def end_of_line(buf, idx):
    j = buf.find(b"\n", idx)
    return len(buf) if j < 0 else j


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    a = ap.parse_args()

    if not os.path.exists(PAGE_SRC):
        print("找不到本地页面源码 %s" % PAGE_SRC)
        return 1

    c = connect()
    sftp = c.open_sftp()
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M")

    if a.rollback:
        for name in ("app.js", "index.html"):
            backs = sorted(f for f in sftp.listdir(BACKUP_DIR) if f.startswith(name + ".before-php-"))
            if not backs:
                print("%s 没有回滚备份" % name)
                continue
            src = posixpath.join(BACKUP_DIR, backs[-1])
            rc, o, e = run(c, "cp %s %s/js/%s" % (src, PUB, name) if name.endswith(".js")
                           else "cp %s %s/%s" % (src, PUB, name))
            print("restored %s from %s (rc=%s)%s" % (name, backs[-1], rc, e))
        return 0

    appjs = read_remote(sftp, posixpath.join(PUB, "js", "app.js"))
    index = read_remote(sftp, posixpath.join(PUB, "index.html"))
    if appjs is None or index is None:
        print("读取 app.js / index.html 失败")
        return 1

    has_nav = b"id: 'php'," in appjs
    has_lazy = b"php: 'js/pages/php.js'" in appjs
    m = BUILD_RE.search(appjs)
    old_build = m.group(3).decode() if m else None
    new_build = stamp
    rc, live, _ = run(c, "ls -1 %s/js/pages/ | tr '\\n' ' '" % PUB)

    print("=== 现状 ===")
    print("app.js 字节数            : %d" % len(appjs))
    print("NAV 里 web 锚点          : %s" % (NAV_ANCHOR in appjs))
    print("lazyPages 里 web 锚点    : %s" % (LAZY_ANCHOR in appjs))
    print("已插入 php 导航 / 懒加载 : %s / %s" % (has_nav, has_lazy))
    print("CM.BUILD                 : %s -> %s" % (old_build, new_build))
    print("远端 pages/              : %s" % live.strip()[:400])

    if a.dry_run:
        print("\n=== dry-run：将要执行 ===")
        print("1. 备份 app.js / index.html -> %s/<name>.before-php-%s" % (BACKUP_DIR, stamp))
        print("2. 上传 %s (%d 字节) -> %s/js/pages/php.js" % (PAGE_SRC, os.path.getsize(PAGE_SRC), PUB))
        print("3. app.js: 在 { id: 'web', ... } 之后插入 { id: 'php', label: 'PHP 站点', icon: 'code' }")
        print("4. app.js: 在 lazyPages 的 web 条目之后插入 php: 'js/pages/php.js',")
        print("5. app.js: CM.BUILD %s -> %s（浏览器立即拿到新页面代码）" % (old_build, new_build))
        print("6. index.html: 同步把 ?v=%s 改成 ?v=%s" % (old_build, new_build))
        print("7. node --check app.js php.js，然后 curl 面板首页确认未被破坏（无需重启服务）")
        return 0

    run(c, "mkdir -p %s" % BACKUP_DIR)
    if not has_nav or not has_lazy:
        run(c, "cp %s/js/app.js %s/app.js.before-php-%s" % (PUB, BACKUP_DIR, stamp))
        run(c, "cp %s/index.html %s/index.html.before-php-%s" % (PUB, BACKUP_DIR, stamp))
        print("1. 已备份 app.js / index.html")
    else:
        print("1. 导航已存在，跳过备份（幂等）")

    with sftp.open(posixpath.join(PUB, "js", "pages", "php.js"), "wb") as f:
        f.write(io.open(PAGE_SRC, "rb").read())
    run(c, "chmod 644 %s/js/pages/php.js" % PUB)
    print("2. 已上传 pages/php.js")

    buf = appjs
    if not has_nav:
        i = buf.find(NAV_ANCHOR)
        if i < 0:
            print("找不到 NAV 锚点，放弃")
            return 1
        eol = end_of_line(buf, i)
        entry = ("\n    { id: 'php', label: 'PHP 站点', icon: 'code' },").encode("utf-8")
        buf = buf[:eol] + entry + buf[eol:]
        print("3. 已插入导航项（+%d 字节）" % len(entry))
    if not has_lazy:
        i = buf.find(LAZY_ANCHOR)
        if i < 0:
            print("找不到 lazyPages 锚点，放弃")
            return 1
        eol = end_of_line(buf, i)
        line = b"\n    php: 'js/pages/php.js',"
        buf = buf[:eol] + line + buf[eol:]
        print("4. 已插入懒加载映射（+%d 字节）" % len(line))
    if old_build:
        buf = BUILD_RE.sub(lambda mm: mm.group(1) + mm.group(2) + new_build.encode() + mm.group(2), buf, count=1)
        print("5. CM.BUILD -> %s" % new_build)
    with sftp.open(posixpath.join(PUB, "js", "app.js"), "wb") as f:
        f.write(buf)

    if old_build:
        idx = index.replace(old_build.encode(), new_build.encode())
        with sftp.open(posixpath.join(PUB, "index.html"), "wb") as f:
            f.write(idx)
        print("6. index.html 的 ?v= 已同步")

    rc, o, e = run(c, "cd %s/js && node --check app.js && node --check pages/php.js && echo SYNTAX_OK" % PUB)
    print("语法检查: %s %s" % (o.strip(), e.strip()))
    if rc != 0 or "SYNTAX_OK" not in o:
        print("语法不过，还原 app.js 与 index.html")
        run(c, "cp %s/app.js.before-php-%s %s/js/app.js" % (BACKUP_DIR, stamp, PUB))
        run(c, "cp %s/index.html.before-php-%s %s/index.html" % (BACKUP_DIR, stamp, PUB))
        return 1

    rc, o, e = run(c, "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9000/panel/public/index.html; "
                      "echo -n ' html '; curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9000/panel/public/js/pages/php.js; "
                      "echo -n ' php.js '; curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9000/panel/public/js/app.js; echo -n ' app.js'")
    print("静态资源可达性: %s" % o.strip())
    print("\n完成。浏览器需要重新登录一次（面板重启过），然后左侧「应用」分组里会出现「PHP 站点」。")
    sftp.close()
    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
