#!/usr/bin/env python
"""Deploy the busy-hours policy fixes: collector calendar + the three lost page functions.

Order matters. The collector is not golden-guarded (only usagetap.py / nginx / allowlist
are), and the tap is never touched here -- relay-usagetap uptime is asserted unchanged.

Every file is backed up with a timestamp first; each gate failure restores that backup.
Validation happens on a correctly-suffixed copy in /var/tmp, because `node --check` on a
suffix-less path exits 1 without saying why.
"""
import hashlib
import json
import os
import posixpath
import sys
import time

import paramiko

CLOUD = os.environ.get("SM_HOST", "8.153.153.176")
KEY = os.path.expanduser(os.environ.get("PUBKEY_FILE", "~/.ssh/oc_relay_ed25519"))
LOCAL_COLLECTOR = "upstream/live-now/collector.py"
LOCAL_RELAY = "upstream/live-now/relay.js"
REMOTE_COLLECTOR = "/opt/relay/collector.py"
REMOTE_RELAY = "/opt/cloud-monitor/public/js/pages/relay.js"
REMOTE_APPJS = "/opt/cloud-monitor/public/js/app.js"
REMOTE_INDEX = "/opt/cloud-monitor/public/index.html"
APPLY = os.environ.get("DEPLOY_APPLY", "") == "1"

LOCAL = {}
for p in (REMOTE_COLLECTOR, REMOTE_RELAY, REMOTE_APPJS, REMOTE_INDEX):
    LOCAL[p] = None
LOCAL[REMOTE_COLLECTOR] = LOCAL_COLLECTOR
LOCAL[REMOTE_RELAY] = LOCAL_RELAY


def connect():
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
    raise RuntimeError("cloud ssh never connected (%s)" % last)


def r(cli, cmd, t=120):
    _, o, e = cli.exec_command(cmd, timeout=t)
    rc = o.channel.recv_exit_status()
    out = o.read().decode("utf-8", "replace").strip()
    err = e.read().decode("utf-8", "replace").strip()
    return rc, (out if out else err)


def md5b(b):
    return hashlib.md5(b).hexdigest()


cli = connect()
sftp = cli.open_sftp()
ts = r(cli, "TZ=Asia/Shanghai date +%Y%m%d-%H%M%S")[1]
build = r(cli, "TZ=Asia/Shanghai date +%Y%m%d-%H%M")[1]
print("ts=%s  new build token=%s  apply=%s" % (ts, build, APPLY))


def stat_mode(path):
    return r(cli, "stat -c '%%a %%U:%%G %%s' %s" % path)[1]


print("\n=== 0. pre-state ===")
for p in (REMOTE_COLLECTOR, REMOTE_RELAY, REMOTE_APPJS, REMOTE_INDEX):
    print("   %-56s %s" % (p, stat_mode(p)))
print("   old build token:", r(cli, "grep -o 'CM.BUILD = \"[^\"]*\"' %s" % REMOTE_APPJS)[1])
tap_before = r(cli, "systemctl show relay-usagetap -p ActiveEnterTimestamp --value")[1]
print("   relay-usagetap since:", tap_before, "(must not change)")
collector_md5_before = r(cli, "md5sum %s | cut -d' ' -f1" % REMOTE_COLLECTOR)[1]
print("   collector md5:", collector_md5_before)

if not APPLY:
    print("\nDRY RUN. Nothing written. Re-run with DEPLOY_APPLY=1.")
    sys.exit(0)


def backup(path):
    rc, out = r(cli, "cp -a %s %s.bak.%s && chmod 600 %s.bak.%s && echo OK" % (path, path, ts, path, ts))
    print("   backup %s -> %s.bak.%s : %s" % (path, posixpath.basename(path), ts, out))
    return "OK" in out


def restore(path):
    rc, out = r(cli, "cp -a %s.bak.%s %s && echo RESTORED" % (path, ts, path))
    print("   RESTORED %s from backup: %s" % (path, out))


print("\n=== 1. backups ===")
for p in (REMOTE_COLLECTOR, REMOTE_RELAY, REMOTE_APPJS, REMOTE_INDEX):
    if not backup(p):
        sys.exit("backup failed for %s -- aborting before any write" % p)


def upload(local_path, remote_tmp, mode_ref):
    data = open(local_path, "rb").read()
    with sftp.file(remote_tmp, "wb") as f:
        f.write(data)
    r(cli, "chmod --reference=%s %s" % (mode_ref, remote_tmp))
    return md5b(data), len(data)


print("\n=== 2. collector.py: compile gate, then place, then restart relay-stats ===")
m, n = upload(LOCAL_COLLECTOR, "/var/tmp/collector.new.py", REMOTE_COLLECTOR)
rc, out = r(cli, "python3 -m py_compile /var/tmp/collector.new.py && echo COMPILE_OK || echo COMPILE_FAIL")
print("   py_compile:", out[:400])
if "COMPILE_OK" not in out:
    restore(REMOTE_COLLECTOR)
    sys.exit("collector did not compile; nothing was moved into place")
rc, out = r(cli, "mv /var/tmp/collector.new.py %s && md5sum %s | cut -d' ' -f1 && stat -c '%%a %%U:%%G' %s"
             % (REMOTE_COLLECTOR, REMOTE_COLLECTOR, REMOTE_COLLECTOR))
print("   placed:", out.replace("\n", " | "), "| local md5:", m)
if not out.startswith(m):
    restore(REMOTE_COLLECTOR)
    sys.exit("md5 mismatch after placing collector")
rc, out = r(cli, "systemctl restart relay-stats && sleep 2 && systemctl is-active relay-stats && "
                 "ss -ltn | grep -c ':8787'")
print("   relay-stats:", out.replace("\n", " / "))
rc, out = r(cli, "curl -s --max-time 10 http://127.0.0.1:8787/api/busy-hours")
try:
    data = json.loads(out)
except Exception:
    restore(REMOTE_COLLECTOR)
    r(cli, "systemctl restart relay-stats")
    sys.exit("busy-hours API did not answer after restart: " + out[:200])
cal = data.get("calendar") or {}
if not cal:
    restore(REMOTE_COLLECTOR)
    r(cli, "systemctl restart relay-stats")
    sys.exit("no calendar in the payload -- collector change had no effect")
print("   calendar: year=%s available=%s off=%s makeup=%s days=%s" % (
    cal.get("year"), cal.get("available"), cal.get("off_count"), cal.get("makeup_count"),
    len(cal.get("days") or [])))
print("   calendar_years:", json.dumps(data.get("calendar_years"), ensure_ascii=False))
ph = data.get("phase") or {}
if not ph.get("label"):
    restore(REMOTE_COLLECTOR)
    r(cli, "systemctl restart relay-stats")
    sys.exit("no phase in the payload -- 忙/闲 badge would render nothing")
print("   phase now: %s in_effect=%s | %s" % (ph.get("label"), ph.get("in_effect"), (ph.get("reason") or "")[:90]))
rc, out2 = r(cli, "curl -s --max-time 10 'http://127.0.0.1:8787/api/busy-hours?at=2027-01-01%2010%3A30'")
c27 = (json.loads(out2).get("calendar") or {}) if out2.startswith("{") else {}
print("   2027 note:", (c27.get("note") or "(none)")[:160])

print("\n=== 3. relay.js: node --check gate, then place ===")
if os.environ.get("SKIP_JS", "") == "1":
    print("   skipped (SKIP_JS=1; only the collector changed)")
else:
    m, n = upload(LOCAL_RELAY, "/var/tmp/relay.new.js", REMOTE_RELAY)
    rc, out = r(cli, "node --check /var/tmp/relay.new.js && echo SYNTAX_OK || echo SYNTAX_FAIL")
    print("   node --check:", out[:400])
    if "SYNTAX_OK" not in out:
        restore(REMOTE_RELAY)
        sys.exit("relay.js failed the syntax gate; not placed")
    rc, out = r(cli, "mv /var/tmp/relay.new.js %s && md5sum %s | cut -d' ' -f1 && stat -c '%%a %%U:%%G' %s"
                % (REMOTE_RELAY, REMOTE_RELAY, REMOTE_RELAY))
    print("   placed:", out.replace("\n", " | "), "| local md5:", m)
    if not out.startswith(m):
        restore(REMOTE_RELAY)
        sys.exit("md5 mismatch after placing relay.js")

print("\n=== 4. cache-bust: CM.BUILD + index.html ?v= (python edit, utf-8 safe) ===")
patch = r'''
import io, sys
app, idx, old, new = sys.argv[1:5]
def rewrite(path, cur, nxt):
    b = open(path, encoding="utf-8").read()
    n = b.count(cur)
    if n == 0:
        print("   SKIP %s: token %r not found" % (path, cur)); return 0
    open(path, "w", encoding="utf-8").write(b.replace(cur, nxt))
    print("   %s: %d occurrence(s) %s -> %s" % (path, n, cur, nxt)); return n
a = rewrite(app, 'CM.BUILD = "%s"' % old, 'CM.BUILD = "%s"' % new)
i = rewrite(idx, "?v=%s" % old, "?v=%s" % new)
print("   total replaced: %d" % (a + i))
'''
with sftp.file("/var/tmp/bust.py", "wb") as f:
    f.write(patch.encode("utf-8"))
old_build = r(cli, "grep -o 'CM.BUILD = \"[^\"]*\"' %s | head -1 | sed 's/.*= \"//; s/\"//'" % REMOTE_APPJS)[1]
rc, out = r(cli, "python3 /var/tmp/bust.py %s %s %s %s" % (REMOTE_APPJS, REMOTE_INDEX, old_build, build))
print(out)
if "0 occurrence" in out or "SKIP" in out:
    print("   !! token replacement looked wrong -- check %s and %s" % (REMOTE_APPJS, REMOTE_INDEX))

print("\n=== 5. final checks ===")
print("   relay-stats:", r(cli, "systemctl is-active relay-stats")
      [1], "| relay-usagetap:", r(cli, "systemctl is-active relay-usagetap")[1])
tap_after = r(cli, "systemctl show relay-usagetap -p ActiveEnterTimestamp --value")[1]
print("   tap uptime unchanged:", "YES" if tap_after == tap_before else "NO -> %s (was %s)" % (tap_after, tap_before))
rc, out = r(cli, "grep -c 'function previewPolicy\\|function savePolicy\\|function togglePolicySwitch' %s" % REMOTE_RELAY)
print("   new function definitions present in placed file:", out)
rc, out = r(cli, "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/panel/public/js/pages/relay.js")
print("   served over nginx :80 -> HTTP", out, "(200 = browser will pick up the new file)")
print("   served copy contains the fix:", r(cli, "curl -s http://127.0.0.1/panel/public/js/pages/relay.js | grep -c 'async function previewPolicy'")[1])
print("\nrollback if needed:")
for p in (REMOTE_COLLECTOR, REMOTE_RELAY, REMOTE_APPJS, REMOTE_INDEX):
    print("   cp -a %s.bak.%s %s" % (p, ts, p))
print("   systemctl restart relay-stats")
cli.close()
print("\nDONE ts=%s build=%s" % (ts, build))
