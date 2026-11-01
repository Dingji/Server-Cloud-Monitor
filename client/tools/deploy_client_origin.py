#!/usr/bin/env python
"""Deploy the client-origin feature: collector + nginx log field + panel proxy + web page.

Order matters and each step gates on its own syntax check:
  collector (new table/endpoints) -> relay-stats restart -> /api/answers
  nginx log_format +field 19      -> nginx -t           -> reload -> update_golden.sh
  panel proxy (index.js/relay.js) -> cloud-monitor restart
  web page (relay.js)             -> node --check       -> place -> cache bust

The golden refresh is not optional: relay_guard restores /opt/relay/golden/opencode-relay.nginx
once the recently_edited window expires, so an unrefreshed edit silently reverts.
"""
import hashlib
import json
import os
import time

import paramiko

CLOUD = os.environ.get("SM_HOST", "8.153.153.176")
KEY = os.path.expanduser(os.environ.get("PUBKEY_FILE", "~/.ssh/oc_relay_ed25519"))
W = "D:/Project/ms/ServerMaintance"
APPLY = os.environ.get("DEPLOY_APPLY", "") == "1"

FILES = [
    (W + "/upstream/live-now/collector.py", "/opt/relay/collector.py"),
    (W + "/upstream/opt/relay/opencode-relay.sites-available", "/etc/nginx/sites-available/opencode-relay"),
    (W + "/upstream/live-20261029/server/index.js", "/opt/cloud-monitor/server/index.js"),
    (W + "/upstream/live-20261029/server/relay.js", "/opt/cloud-monitor/server/relay.js"),
    (W + "/upstream/live-now/relay.js", "/opt/cloud-monitor/public/js/pages/relay.js"),
]
# the nginx edit must sit on top of the CURRENT live file: the repo copy predates the
# 1100m upload limit, so deploying it would silently revert the 413 fix
FILES[1] = (W + "/upstream/live-now/opencode-relay.live", FILES[1][1])


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


cli = connect()
sftp = cli.open_sftp()
ts = r(cli, "date +%Y%m%d-%H%M%S")
build = r(cli, "TZ=Asia/Shanghai date +%Y%m%d-%H%M")
print("ts=%s build=%s apply=%s" % (ts, build, APPLY))
for local, remote in FILES:
    lb = open(local, "rb").read()
    rb = r(cli, "md5sum %s 2>/dev/null | cut -d' ' -f1" % remote)
    print("  %-52s local=%s live=%s %s" % (remote, hashlib.md5(lb).hexdigest()[:8], rb[:8],
                                           "CHANGED" if rb[:8] != hashlib.md5(lb).hexdigest()[:8] else "same"))
if not APPLY:
    print("\nDRY RUN. Re-run with DEPLOY_APPLY=1.")
    raise SystemExit(0)

print("\n=== 1. backups ===")
for _, remote in FILES:
    print("  ", r(cli, "cp -a %s %s.bak.%s && echo ok" % (remote, remote, ts)))

print("\n=== 2. collector.py ===")
loc = FILES[0][0]
with sftp.file("/var/tmp/collector.new.py", "wb") as f:
    f.write(open(loc, "rb").read())
g = r(cli, "python3 -m py_compile /var/tmp/collector.new.py && echo COMPILE_OK || echo COMPILE_FAIL")
print("  py_compile:", g[:200])
if "COMPILE_OK" not in g:
    raise SystemExit("collector failed to compile; nothing placed")
r(cli, "mv /var/tmp/collector.new.py /opt/relay/collector.py")
if r(cli, "md5sum /opt/relay/collector.py | cut -d' ' -f1") != hashlib.md5(open(loc, "rb").read()).hexdigest():
    r(cli, "cp -a /opt/relay/collector.py.bak.%s /opt/relay/collector.py" % ts)
    raise SystemExit("collector md5 mismatch; restored")
print("  placed. restarting relay-stats:", r(cli, "systemctl restart relay-stats && sleep 2 && systemctl is-active relay-stats"))
out = r(cli, "curl -s -m 10 http://127.0.0.1:8787/api/client-origins")
try:
    d = json.loads(out)
    print("  /api/clients ok=%s clients=%d by_class=%s" % (d.get("ok"), len(d.get("clients") or []), d.get("by_class")))
    print("  note:", (d.get("note") or "")[:70], "...")
except Exception:
    r(cli, "cp -a /opt/relay/collector.py.bak.%s /opt/relay/collector.py && systemctl restart relay-stats" % ts)
    raise SystemExit("/api/clients did not answer: " + out[:160])

print("\n=== 3. nginx log_format (+19th field), then reload, then refresh golden ===")
loc = FILES[1][0]
nb = open(loc, "rb").read()
# guard against the regression found while preparing this deploy: an older copy of the
# site would silently put the upload limit back to 50m
if b"client_max_body_size 1100m;" not in nb:
    raise SystemExit("refusing to deploy: the nginx source lacks the 1100m upload limit")
if nb.count(b"$http_x_forwarded_for") != 1:
    raise SystemExit("refusing to deploy: the 19th log field is not exactly one occurrence")
with sftp.file("/var/tmp/site.new", "wb") as f:
    f.write(nb)
r(cli, "cp /var/tmp/site.new /etc/nginx/sites-available/opencode-relay")
t = r(cli, "nginx -t 2>&1")
print("  nginx -t:", t.replace("\n", " | ")[:180])
if "successful" not in t:
    r(cli, "cp -a /etc/nginx/sites-available/opencode-relay.bak.%s /etc/nginx/sites-available/opencode-relay && nginx -t && systemctl reload nginx" % ts)
    raise SystemExit("nginx -t failed -- site restored and reloaded")
print("  reload:", r(cli, "systemctl reload nginx && echo RELOADED"))
print("  update_golden:", r(cli, "bash /opt/relay/update_golden.sh 2>&1 | tail -4").replace("\n", " | ")[:260])
print("  golden == live:", r(cli, "md5sum /opt/relay/golden/opencode-relay.nginx /etc/nginx/sites-available/opencode-relay | awk '{print $1}' | uniq | wc -l") == "1")

print("\n=== 4. panel proxy ===")
for loc, remote in FILES[2:4]:
    # the temp name must keep the .js suffix: `node --check /var/tmp/x.js.new` dies with
    # ERR_UNKNOWN_FILE_EXTENSION, which looks like broken code but is not
    tmp = "/var/tmp/" + os.path.basename(remote).replace(".js", "") + ".new.js"
    with sftp.file(tmp, "wb") as f:
        f.write(open(loc, "rb").read())
    g = r(cli, "node --check %s && echo SYNTAX_OK || echo SYNTAX_FAIL" % tmp)
    print("  %s: %s" % (os.path.basename(remote), g[:120]))
    if "SYNTAX_OK" not in g:
        raise SystemExit("panel proxy syntax gate failed")
    r(cli, "mv %s %s" % (tmp, remote))
for loc, remote in FILES[2:4]:
    if r(cli, "md5sum %s | cut -d' ' -f1" % remote) != hashlib.md5(open(loc, "rb").read()).hexdigest():
        raise SystemExit("md5 mismatch placing " + remote)
print("  restarting cloud-monitor:", r(cli, "systemctl restart cloud-monitor && sleep 2 && systemctl is-active cloud-monitor"))
print("  panel /relay/client-origins via :9000 ->", r(cli, "curl -s -o /dev/null -w '%{http_code}' -m 10 'http://127.0.0.1:9000/panel/api/relay/client-origins'"), "(401 = 路由存在且要求会话)")

print("\n=== 5. web page + cache bust ===")
loc, remote = FILES[4]
with sftp.file("/var/tmp/relay.new.js", "wb") as f:
    f.write(open(loc, "rb").read())
g = r(cli, "node --check /var/tmp/relay.new.js && echo SYNTAX_OK || echo SYNTAX_FAIL")
print("  node --check:", g[:160])
if "SYNTAX_OK" not in g:
    raise SystemExit("web relay.js gate failed")
r(cli, "mv /var/tmp/relay.new.js %s" % remote)
old = r(cli, "grep -o 'CM.BUILD = \"[^\"]*\"' /opt/cloud-monitor/public/js/app.js | sed 's/.*= \"//; s/\"//'")
patch = (
    "import sys\n"
    "app, idx, o, n = sys.argv[1:5]\n"
    "for p, cur, nxt in ((app, 'CM.BUILD = \"'+o+'\"', 'CM.BUILD = \"'+n+'\"'), (idx, '?v='+o, '?v='+n)):\n"
    "    b = open(p, encoding='utf-8').read(); c = b.count(cur)\n"
    "    if c: open(p,'w',encoding='utf-8').write(b.replace(cur,nxt))\n"
    "    print('   %s: %d replaced' % (p, c))\n"
)
with sftp.file("/var/tmp/bust3.py", "wb") as f:
    f.write(patch.encode("utf-8"))
r(cli, "cp -a /opt/cloud-monitor/public/js/app.js /opt/cloud-monitor/public/js/app.js.bak.%s" % ts)
r(cli, "cp -a /opt/cloud-monitor/public/index.html /opt/cloud-monitor/public/index.html.bak.%s" % ts)
print(r(cli, "python3 /var/tmp/bust3.py /opt/cloud-monitor/public/js/app.js /opt/cloud-monitor/public/index.html %s %s" % (old, build)))

print("\n=== 6. 端到端：带 X-Forwarded-For 的请求应被记下 ===")
print("  ", r(cli, "curl -s -o /dev/null -w 'relay probe http=%{http_code}\\n' -m 15 -H 'X-Forwarded-For: 192.168.10.77' "
                  "http://127.0.0.1/v1/models"))
time.sleep(4.0)
d = json.loads(r(cli, "curl -s -m 10 http://127.0.0.1:8787/api/client-origins"))
row = [c for c in d.get("clients") or [] if c.get("xff")]
print("   带 xff 的来源条目:", json.dumps(row[:2], ensure_ascii=False)[:300])
print("   分类计数:", json.dumps(d.get("by_class"), ensure_ascii=False))
print("\nrollback: cp -a <file>.bak.%s <file> 然后 systemctl restart relay-stats cloud-monitor; "
      "nginx -t && systemctl reload nginx; bash /opt/relay/update_golden.sh" % ts)
print("DONE ts=%s build=%s" % (ts, build))
