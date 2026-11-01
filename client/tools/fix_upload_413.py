#!/usr/bin/env python
"""Deploy the streaming upload fix: ops/files.js + nginx client_max_body_size.

The measured facts this encodes: nginx /panel/ capped at 50m while the app allowed
64m, and the old handler buffered the whole file inside a --max-old-space-size=256
process. The new handler streams to a .part file and renames atomically (verified by
tools/test_streaming_upload.py BEFORE this touches anything live).

Backups first, gates in between, and every failure path restores what it replaced.
"""
import hashlib
import json
import os
import sys
import time

import paramiko

CLOUD = os.environ.get("SM_HOST", "8.153.153.176")
KEY = os.path.expanduser("~/.ssh/oc_relay_ed25519")
LOCAL = "upstream/fix413/files.js"
FILESJS = "/opt/cloud-monitor/server/ops/files.js"
NGINX_SITE = "/etc/nginx/sites-available/opencode-relay"
APPLY = os.environ.get("DEPLOY_APPLY", "") == "1"


def connect():
    last = None
    for i in range(12):
        c = paramiko.SSHClient()
        c.load_system_host_keys()
        c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        try:
            c.connect(CLOUD, 22, "root", key_filename=KEY, look_for_keys=False,
                      allow_agent=False, timeout=30)
            return c
        except Exception as exc:
            last = "%s: %s" % (type(exc).__name__, str(exc).strip()[:80])
            c.close()
            time.sleep(2.5)
    raise RuntimeError(last)


def r(cli, cmd, t=180):
    _, o, e = cli.exec_command(cmd, timeout=t)
    o.channel.settimeout(t)
    o.channel.recv_exit_status()
    return (o.read().decode("utf-8", "replace") + e.read().decode("utf-8", "replace")).strip()


def md5b(b):
    return hashlib.md5(b).hexdigest()


cli = connect()
sftp = cli.open_sftp()
ts = r(cli, "date +%Y%m%d-%H%M%S")
data = open(LOCAL, "rb").read()
want = md5b(data)
print("ts=%s  apply=%s  new files.js %d bytes md5=%s" % (ts, APPLY, len(data), want[:12]))
print("services now:", r(cli, "systemctl is-active cloud-monitor nginx | tr '\\n' ' '"))
if not APPLY:
    print("\nDRY RUN. Re-run with DEPLOY_APPLY=1.")
    sys.exit(0)

print("\n=== 1. backups ===")
for p in (FILESJS, NGINX_SITE):
    print("  ", r(cli, "cp -a %s %s.bak.%s && echo backed_up" % (p, p, ts)))

print("\n=== 2. files.js: syntax gate on a .js copy, then place, then restart panel ===")
with sftp.file("/var/tmp/files.new.js", "wb") as f:
    f.write(data)
gate = r(cli, "node --check /var/tmp/files.new.js && echo SYNTAX_OK || echo SYNTAX_FAIL")
print("  node --check:", gate[:200])
if "SYNTAX_OK" not in gate:
    sys.exit("syntax gate failed; nothing placed")
r(cli, "mv /var/tmp/files.new.js %s" % FILESJS)
got = r(cli, "md5sum %s | cut -d' ' -f1" % FILESJS)
print("  placed md5 match:", got == want)
if got != want:
    r(cli, "cp -a %s.bak.%s %s" % (FILESJS, ts, FILESJS))
    sys.exit("md5 mismatch after placing files.js -- restored backup")

print("  restarting cloud-monitor (面板会短暂掉线几秒)...")
rc = r(cli, "systemctl restart cloud-monitor && sleep 2 && systemctl is-active cloud-monitor")
print("  panel:", rc)
code = r(cli, "curl -s -o /dev/null -w '%{http_code}' -m 10 http://127.0.0.1:9000/panel/")
print("  panel answers on :9000 ->", code)
if code not in ("200", "301", "302", "401"):
    r(cli, "cp -a %s.bak.%s %s && systemctl restart cloud-monitor" % (FILESJS, ts, FILESJS))
    sys.exit("panel did not come back healthy -- files.js restored and restarted")

print("\n=== 3. nginx: /panel/ client_max_body_size 50m -> 1100m, then -t and reload ===")
patch = r'''
import sys
p, old, new = sys.argv[1:4]
b = open(p, encoding="utf-8").read()
n = b.count(old)
if n != 1:
    print("SKIP: %d occurrence(s) of %r -- not touching" % (n, old)); sys.exit(1)
open(p, "w", encoding="utf-8").write(b.replace(old, new))
print("replaced %d occurrence" % n)
'''
with sftp.file("/var/tmp/nginx_patch.py", "wb") as f:
    f.write(patch.encode("utf-8"))
rc = r(cli, "python3 /var/tmp/nginx_patch.py %s 'client_max_body_size 50m;' 'client_max_body_size 1100m;'" % NGINX_SITE)
print("  ", rc)
if "replaced" not in rc:
    print("  !! nginx 未改（上限仍是 50m），app 侧上限 1024MB 会被 nginx 先拒")
t = r(cli, "nginx -t 2>&1 && systemctl reload nginx && echo RELOADED")
print("  ", t.replace("\n", " | ")[:160])
if "RELOADED" not in t:
    r(cli, "cp -a %s.bak.%s %s && nginx -t && systemctl reload nginx" % (NGINX_SITE, ts, NGINX_SITE))
    sys.exit("nginx -t failed -- site restored and reloaded")

print("\n=== 4. 尺寸阶梯复测（未认证即可分辨两层）===")
for mb, expect in ((60, "401"), (1200, "413")):
    cmd = ("head -c %d /dev/zero | curl -s -o /dev/null -w '%%{http_code}' -m 120 --data-binary @- "
           "-H 'Content-Type: application/octet-stream' "
           "'http://127.0.0.1/panel/api/ops/files/upload?dir=%%2Fhome&name=t.bin'" % (mb * 1024 * 1024))
    code = r(cli, cmd, t=200)
    print("   %5d MB  -> %s (期望 %s)  %s" % (mb, code, expect, "OK" if code == expect else "MISMATCH"))

print("\nrollback:")
for p in (FILESJS, NGINX_SITE):
    print("   cp -a %s.bak.%s %s" % (p, ts, p))
print("   systemctl restart cloud-monitor && nginx -t && systemctl reload nginx")
print("DONE ts=%s" % ts)
