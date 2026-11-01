#!/usr/bin/env python
"""Ship a new public/js/pages/relay.js with the panel's own gates, then cache-bust it.

Static file only: no service restarts. Validation happens on a .js-suffixed copy in
/var/tmp because `node --check` on a suffix-less path fails without explaining itself.
Every failure restores the timestamped backup taken at the start.
"""
import os
import sys
import time

import paramiko

CLOUD = os.environ.get("SM_HOST", "8.153.153.176")
KEY = os.path.expanduser(os.environ.get("PUBKEY_FILE", "~/.ssh/oc_relay_ed25519"))
LOCAL = os.environ.get("LOCAL_JS", "upstream/live-now/relay.js")
RELAY = "/opt/cloud-monitor/public/js/pages/relay.js"
APPJS = "/opt/cloud-monitor/public/js/app.js"
INDEX = "/opt/cloud-monitor/public/index.html"
APPLY = os.environ.get("DEPLOY_APPLY", "") == "1"
import hashlib


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
            last = "%s: %s" % (type(exc).__name__, str(exc).strip()[:90])
            c.close()
            time.sleep(2.5)
    raise RuntimeError("never connected (%s)" % last)


def r(cli, cmd, t=90):
    _, o, e = cli.exec_command(cmd, timeout=t)
    o.channel.recv_exit_status()
    return (o.read().decode("utf-8", "replace").strip() or e.read().decode("utf-8", "replace").strip())


cli = connect()
sftp = cli.open_sftp()
ts = r(cli, "TZ=Asia/Shanghai date +%Y%m%d-%H%M%S")
build = r(cli, "TZ=Asia/Shanghai date +%Y%m%d-%H%M")
data = open(LOCAL, "rb").read()
want = hashlib.md5(data).hexdigest()
print("local %s  %d bytes  md5=%s" % (LOCAL, len(data), want))
print("live   :", r(cli, "md5sum %s | cut -d' ' -f1" % RELAY)[0:32], "| build",
      r(cli, "grep -o 'CM.BUILD = \"[^\"]*\"' %s" % APPJS))
print("services:", r(cli, "systemctl is-active relay-stats relay-usagetap cloud-monitor 2>&1 | tr '\\n' ' '"))
if not APPLY:
    print("\nDRY RUN. Re-run with DEPLOY_APPLY=1.")
    sys.exit(0)

print("\n=== backups ===")
for p in (RELAY, APPJS, INDEX):
    print("  ", r(cli, "cp -a %s %s.bak.%s && echo backed_up" % (p, p, ts)))

print("\n=== syntax gate on a .js copy, then place ===")
with sftp.file("/var/tmp/relay.new.js", "wb") as f:
    f.write(data)
gate = r(cli, "node --check /var/tmp/relay.new.js && echo SYNTAX_OK || echo SYNTAX_FAIL")
print("  node --check:", gate[:300])
if "SYNTAX_OK" not in gate:
    r(cli, "rm -f /var/tmp/relay.new.js")
    sys.exit("syntax gate failed; nothing placed")
r(cli, "mv /var/tmp/relay.new.js %s" % RELAY)
got = r(cli, "md5sum %s | cut -d' ' -f1" % RELAY)
print("  placed md5:", got)
if got != want:
    r(cli, "cp -a %s.bak.%s %s" % (RELAY, ts, RELAY))
    sys.exit("md5 mismatch (%s) -- restored backup" % got)
print("  md5 matches:", got == want)

print("\n=== cache-bust (python replace, utf-8 safe) ===")
old = r(cli, "grep -o 'CM.BUILD = \"[^\"]*\"' %s | sed 's/.*= \"//; s/\"//'" % APPJS)
patch = (
    "import sys\n"
    "app, idx, old, new = sys.argv[1:5]\n"
    "for path, cur, nxt in ((app, 'CM.BUILD = \"'+old+'\"', 'CM.BUILD = \"'+new+'\"'),\n"
    "                       (idx, '?v='+old, '?v='+new)):\n"
    "    b = open(path, encoding='utf-8').read()\n"
    "    n = b.count(cur)\n"
    "    if n:\n"
    "        open(path, 'w', encoding='utf-8').write(b.replace(cur, nxt))\n"
    "    print('   %s: %d replaced' % (path, n))\n"
)
with sftp.file("/var/tmp/bust2.py", "wb") as f:
    f.write(patch.encode("utf-8"))
print(r(cli, "python3 /var/tmp/bust2.py %s %s %s %s" % (APPJS, INDEX, old, build)))

print("\n=== verify what the browser will get ===")
print("  served HTTP:", r(cli, "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/panel/public/js/pages/relay.js"))
print("  hero markup left:", r(cli, "curl -s http://127.0.0.1/panel/public/js/pages/relay.js | grep -c 'bp-hero'"))
print("  policy handlers present:", r(cli, "curl -s http://127.0.0.1/panel/public/js/pages/relay.js | grep -c 'async function previewPolicy'"))
print("  new build token:", r(cli, "grep -o 'CM.BUILD = \"[^\"]*\"' %s" % APPJS))
print("  services:", r(cli, "systemctl is-active relay-stats relay-usagetap cloud-monitor 2>&1 | tr '\\n' ' '"))
print("\nrollback:  cp -a %s.bak.%s %s   (plus app.js/index.html same suffix)" % (RELAY, ts, RELAY))
cli.close()
print("DONE ts=%s" % ts)
