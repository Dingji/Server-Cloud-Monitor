#!/usr/bin/env python
"""Put the per-distribution client key feature onto the relay host.

    python tools/deploy_client_keys.py            # read-only preflight, nothing written
    python tools/deploy_client_keys.py --apply    # backups + place + gate + restart + smoke
    python tools/deploy_client_keys.py --rollback --stamp 20261030-201500

What lands:
  /opt/relay/clientkeys.py            the key store (issue / revoke / ceilings / nginx list)
  /opt/relay/usagetap.py              + CLIENT_KEYS_MARK: per-key auth and quota on the request path
  /opt/relay/collector.py             + GET /api/client-keys and POST client-keys/<action>
  /etc/relay/client-keys.json         0600 store, seeded with the old shared token as "主令牌"
  /etc/relay/relay-auth.conf          GENERATED: the credential list nginx accepts at the edge
  the two /models-*.json locations    inline single-token check -> include relay-auth.conf
  /opt/cloud-monitor/...              the panel page (分发 Key tab) and its relay proxy entry

Every write is backed up first with a Beijing-time stamp and every gate restores from that
backup. The nginx site is reloaded only after `nginx -t` passes. Golden copies are promoted at
the end on purpose: without that, relay-guard restores the pre-change tap and site within two
minutes.
"""
import argparse
import hashlib
import io
import logging
import os
import posixpath
import sys
import time

import paramiko

logging.getLogger("paramiko").setLevel(logging.CRITICAL)

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.path.expanduser(os.environ.get("SM_KEY", "~/.ssh/oc_relay_ed25519"))   # local path, used locally only

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELAY = "/opt/relay"
ETC = "/etc/relay"
NGINX_SITE = "/etc/nginx/sites-available/opencode-relay"
STORE = posixpath.join(ETC, "client-keys.json")
EDGE = posixpath.join(ETC, "relay-auth.conf")
APP = "/opt/cloud-monitor"
PANEL_APP_JS = posixpath.join(APP, "public/js/app.js")
PANEL_INDEX = posixpath.join(APP, "public/index.html")

# (local source, remote path, language)
FILES = [
    ("relay-server/clientkeys.py", posixpath.join(RELAY, "clientkeys.py"), "py"),
    ("relay-server/usagetap.py", posixpath.join(RELAY, "usagetap.py"), "py"),
    ("relay-server/collector.py", posixpath.join(RELAY, "collector.py"), "py"),
    ("panel-web/relay.js", posixpath.join(APP, "public/js/pages/relay.js"), "js"),
    ("panel-server/index.js", posixpath.join(APP, "server/index.js"), "js"),
]
ALL_PATHS = [remote for _r, remote, _k in FILES] + [NGINX_SITE, EDGE]

# The single-token gate in front of the two static model reports. Both copies are replaced --
# otherwise a client holding a distribution key can call the models but cannot fetch the
# catalog its own configuration is generated from.
GATE_OLD = '''        if ($http_authorization != "Bearer $relay_token") {
            return 401;
        }
'''
GATE_NEW = "        include /etc/relay/relay-auth.conf;\n"

# Uploaded, never echoed through a shell: the two literals above contain $ and ".
GATE_PATCH = '''import io
import sys

site, old_p, new_p = sys.argv[1:4]
with io.open(site, encoding="utf-8") as f:
    text = f.read()
with io.open(old_p, encoding="utf-8") as f:
    old = f.read()
with io.open(new_p, encoding="utf-8") as f:
    new = f.read()
had, has = text.count(old), text.count(new)
if had == 0 and has:
    print("already patched (%d included)" % has)
elif had == 2:
    with io.open(site, "w", encoding="utf-8", newline="\\n") as f:
        f.write(text.replace(old, new))
    print("patched 2 locations")
else:
    print("FAIL: expected 2 old blocks, found %d (new blocks already: %d)" % (had, has))
    raise SystemExit(1)
'''

# The cache-bust logic is its own file (tools/bust_build.py) rather than a string
# literal here: nested quoting through ssh is how it kept breaking.
BUST_LOCAL = os.path.join(REPO, "tools", "bust_build.py")
BUST_REMOTE = "/var/tmp/bust_build.py"


SERVICES = ("relay-usagetap", "relay-stats", "cloud-monitor")


def connect():
    last = None
    for _ in range(12):
        c = paramiko.SSHClient()
        c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        try:
            c.connect(HOST, username=USER, key_filename=KEY, timeout=30,
                      banner_timeout=60, auth_timeout=60, look_for_keys=False, allow_agent=False)
            return c
        except Exception as exc:
            last = "%s: %s" % (type(exc).__name__, str(exc).strip()[:100])
            time.sleep(2.5)
    raise SystemExit("ssh never established: %s" % last)


def run(c, cmd, timeout=180):
    _, out, err = c.exec_command(cmd, timeout=timeout)
    rc = out.channel.recv_exit_status()
    return rc, out.read().decode("utf-8", "replace").strip(), err.read().decode("utf-8", "replace").strip()


def sh(c, cmd, timeout=180):
    rc, o, e = run(c, cmd, timeout)
    return (o or e or "(empty rc=%d)" % rc).replace("\n", " | ")


def put(c, remote_text_path, text):
    with c.open_sftp().file(remote_text_path, "wb") as f:
        f.write(text.encode("utf-8"))


def md5_local(rel):
    with open(os.path.join(REPO, rel), "rb") as f:
        return hashlib.md5(f.read()).hexdigest()


def services(c, label):
    print("  %-14s %s" % (label, sh(c, "systemctl is-active %s 2>&1 | tr '\\n' ' '" % " ".join(SERVICES))))


def preflight(c):
    print("== host ==")
    print("  ", sh(c, "hostname; TZ=Asia/Shanghai date '+%F %T %Z'"))
    print("== files (local md5 vs live) ==")
    for rel, remote, _kind in FILES:
        live = sh(c, "test -f %s && md5sum %s | cut -d' ' -f1 || echo ABSENT" % (remote, remote))
        want = md5_local(rel)
        print("  %-34s local=%s live=%s %s" % (rel, want[:10], live[:10],
                                               "same" if live == want else "WILL REPLACE"))
    print("== is the feature already in place? ==")
    print("  tap CLIENT_KEYS_MARK :", sh(c, "grep -c CLIENT_KEYS_MARK %s/usagetap.py" % RELAY))
    print("  collector client-keys:", sh(c, "grep -c client-keys %s/collector.py" % RELAY))
    print("  nginx gate patched   :", sh(c, "n=$(grep -c 'http_authorization != ' %s); if [ \"$n\" = 0 ]; then echo yes; else echo \"no ($n inline single-token checks left)\"; fi" % NGINX_SITE))
    print("  clientkeys.py present:", sh(c, "test -f %s/clientkeys.py && echo yes || echo no" % RELAY))
    print("== current state ==")
    print("  store   :", sh(c, "test -f %s && ls -la %s || echo 'not created yet'" % (STORE, STORE)))
    print("  edge if-count:", sh(c, "grep -c 'http_authorization' %s" % EDGE))
    print("  keys    :", sh(c, "test -f %s/clientkeys.py && python3 %s/clientkeys.py list 2>&1 | head -8 || echo 'no module yet'" % (RELAY, RELAY)))
    print("  nginx -t:", sh(c, "nginx -t 2>&1 | tail -2"))
    print("  disk    :", sh(c, "df -h / | tail -1"))
    print("  rows    :", sh(c, "sqlite3 /var/lib/relay/relay.db 'select count(*) from usage_events' 2>&1"))
    services(c, "services")
    print("== golden copies (relay-guard restores these every 120s) ==")
    print("  ", sh(c, "ls -la %s/golden/ 2>&1 | tail -3" % RELAY))
    # A second --apply stamps backups of files that are already changed, so the
    # newest stamp is not the pre-change state. Show them all before anyone rolls back.
    print("== backup stamps on /opt/relay/usagetap.py (oldest = pre-change) ==")
    print("  ", sh(c, "ls -1 %s/usagetap.py.bak-* 2>/dev/null | sed 's/.*bak.//' | tr '\\n' ' '" % RELAY))


def backup(c, ts):
    made = []
    for p in ALL_PATHS:
        rc, o, e = run(c, "test -f %s && cp -a %s %s.bak.%s" % (p, p, p, ts))
        if rc == 0:
            made.append(p)
            print("   backed up %-50s -> .bak.%s" % (p, ts))
        else:
            print("   %-50s absent (created fresh)" % p)
    return made


def place(c, rel, remote, kind):
    """Upload, syntax-gate a copy that keeps its real suffix, then mv into position."""
    with open(os.path.join(REPO, rel), "rb") as f:
        data = f.read()
    want = hashlib.md5(data).hexdigest()
    tmp = "/var/tmp/%s.new.%s" % (posixpath.basename(remote).replace(".js", "").replace(".py", ""), kind)
    with c.open_sftp().file(tmp, "wb") as f:
        f.write(data)
    if sh(c, "md5sum %s | cut -d' ' -f1" % tmp) != want:
        run(c, "rm -f %s" % tmp)
        return "FAIL transfer md5 mismatch"
    if kind == "py":
        gate = sh(c, "python3 -c \"import ast,io;ast.parse(io.open('%s',encoding='utf-8').read());print('SYNTAX_OK')\" 2>&1" % tmp)
    else:
        gate = sh(c, "node --check %s 2>&1; echo rc=$?" % tmp)
    if kind == "py" and "SYNTAX_OK" not in gate:
        run(c, "rm -f %s" % tmp)
        return "FAIL syntax: " + gate[-300:]
    if kind == "js" and "rc=0" not in gate:
        run(c, "rm -f %s" % tmp)
        return "FAIL syntax: " + gate[-300:]
    sh(c, "mv %s %s && chmod 644 %s" % (tmp, remote, remote))
    got = sh(c, "md5sum %s | cut -d' ' -f1" % remote)
    return "placed" if got == want else "FAIL md5 after place: %s != %s" % (got[:12], want[:12])


def restore(c, ts, paths):
    print("== restoring the %s backup set ==" % ts)
    for p in paths:
        print("   %-50s %s" % (p, sh(c, "test -f %s.bak.%s && cp -a %s.bak.%s %s && echo restored || echo 'no backup for this path'"
                                       % (p, ts, p, ts, p))))
    print("   services:", sh(c, "systemctl restart relay-usagetap relay-stats cloud-monitor 2>&1; sleep 3;"
                                " systemctl is-active relay-usagetap relay-stats cloud-monitor | tr '\\n' ' '", timeout=120))


def apply_all(c, ts):
    print("\n== 1. backups (stamp %s) ==" % ts)
    placed = backup(c, ts)

    print("\n== 2. place files ==")
    for rel, remote, kind in FILES:
        print("   %-34s %s" % (rel, place(c, rel, remote, kind)))
    imp = sh(c, "cd %s && python3 -c 'import clientkeys;print(\"import_ok\", clientkeys.STORE_PATH)' 2>&1" % RELAY)
    print("   import clientkeys:", imp)
    if "import_ok" not in imp:
        restore(c, ts, placed)
        raise SystemExit("clientkeys.py does not import -- restored backups, nothing reloaded")

    print("\n== 3. nginx site: the static reports share the credential list ==")
    put(c, "/var/tmp/gate_old.txt", GATE_OLD)
    put(c, "/var/tmp/gate_new.txt", GATE_NEW)
    put(c, "/var/tmp/patch_gate.py", GATE_PATCH)
    print("   ", sh(c, "python3 /var/tmp/patch_gate.py %s /var/tmp/gate_old.txt /var/tmp/gate_new.txt 2>&1" % NGINX_SITE))
    test = sh(c, "nginx -t 2>&1 | tail -3")
    print("   nginx -t:", test)
    if "successful" not in test:
        restore(c, ts, placed)
        raise SystemExit("nginx config test failed -- site restored, nothing reloaded")

    print("\n== 4. seed the store, then issue the credential list ==")
    print("   seed :", sh(c, "python3 %s/clientkeys.py seed-master 2>&1" % RELAY))
    print("   apply:", sh(c, "python3 %s/clientkeys.py apply 2>&1" % RELAY))
    print("   store:", sh(c, "ls -la %s 2>&1" % STORE))
    print("   modes:", sh(c, "stat -c '%%a %%U:%%G %%n' %s %s" % (STORE, EDGE)))
    print("   edge :", sh(c, "grep -c 'set .relay_ok 1' %s" % EDGE))

    print("\n== 5. restart the relay services (a tap restart drops in-flight streams) ==")
    print("   ", sh(c, "systemctl restart relay-usagetap relay-stats && sleep 2;"
                       " systemctl is-active relay-usagetap relay-stats | tr '\\n' ' '", timeout=120))
    print("   tap log  :", sh(c, "tail -2 /var/log/relay-usagetap.log 2>&1"))
    print("   stats log:", sh(c, "tail -2 /var/log/relay-stats.log 2>&1"))

    print("\n== 6. panel: restart and cache-bust the page ==")
    build = sh(c, "TZ=Asia/Shanghai date +%Y%m%d-%H%M")
    put(c, BUST_REMOTE, io.open(BUST_LOCAL, encoding="utf-8").read())
    print("   cache bust:", sh(c, "python3 /var/tmp/bust_build.py %s %s %s 2>&1" % (PANEL_APP_JS, PANEL_INDEX, build)))
    print("   ", sh(c, "systemctl restart cloud-monitor && sleep 3 && systemctl is-active cloud-monitor", timeout=120))
    services(c, "after restart")


def smoke(c):
    """Credentials never leave the host: this runs there and prints codes plus excerpts."""
    script = '''import json
import subprocess
import sys
import time

sys.path.insert(0, "/opt/relay")
import clientkeys

ASK = json.dumps({"model": "mimo-v2.6-flash",
                  "messages": [{"role": "user", "content": "reply with one word: ok"}],
                  "max_tokens": 8, "stream": False})


def raw(args, tries=4):
    """curl, keeping the body and the rewrite headers. Retries only a 401, which is
    what a not-yet-settled nginx reload looks like from the outside."""
    for i in range(tries):
        subprocess.run(["curl", "-s", "-m", "90", "-D", "/var/tmp/relay.hdr",
                        "-o", "/var/tmp/relay.body"] + args, capture_output=True, text=True)
        code = ""
        try:
            with open("/var/tmp/relay.hdr", encoding="utf-8", errors="replace") as f:
                head = f.read()
            code = head.splitlines()[0].split()[1]
            hdrs = {k.lower(): v.strip() for k, v in
                    (l.split(":", 1) for l in head.splitlines()[1:] if ":" in l)}
        except Exception:
            hdrs = {}
        if code != "401" or i == tries - 1:
            body = ""
            try:
                with open("/var/tmp/relay.body", encoding="utf-8", errors="replace") as f:
                    body = f.read()[:180].replace("\\n", " ")
            except Exception:
                pass
            return code, body, hdrs
        time.sleep(2)
    return "???", "", {}


def post(cred, scheme="bearer", extra=None):
    args = ["-X", "POST", "http://127.0.0.1/v1/chat/completions",
            "-H", "Content-Type: application/json", "-d", ASK]
    if cred:
        args += ["-H", ("x-api-key: " + cred) if scheme == "x-api-key"
                 else ("Authorization: Bearer " + cred)]
    for h in (extra or []):
        args += ["-H", h]
    return raw(args)


def note(label, got, want):
    code, body, hdrs = got
    flag = "" if code == want else "  <<< UNEXPECTED (want %s)" % want
    rw = ("  [rewritten %s -> %s (%s)]" % (hdrs.get("x-relay-rewritten-from"),
                                           hdrs.get("x-relay-rewritten-to"),
                                           hdrs.get("x-relay-rewritten-policy"))
          if hdrs.get("x-relay-rewritten-from") else "")
    print("%-26s %s %s%s%s" % (label + ":", code, ("(expect %s)" % want), rw, flag))
    if code not in (want, "200") and body:
        print("      body:", body)


master = clientkeys.legacy_token()
note("master via bearer", post(master), "200")
note("master via x-api-key", post(master, "x-api-key"), "200")
note("master on catalog", raw(["http://127.0.0.1/models-catalog.json",
                               "-H", "Authorization: Bearer " + master]), "200")
note("unknown credential", post("rk_" + "0" * 32), "401")
note("no credential", post(None), "401")
note("forged pass-through", post(master, extra=["X-Relay-Client-Auth: Bearer rk_" + "0" * 32]), "200")

probe = [r for r in (clientkeys.read_doc() or {"keys": []}).get("keys", [])
         if r.get("label") == "__quota-probe__"]
if probe:
    print("a previous probe key is still there:", probe[0]["id"], "-- leaving it")
else:
    rec, _ = clientkeys.create("__quota-probe__", "临时验证额度闸门", {"hour": 1})
    print("probe key               :", rec["id"], "ceiling 1 token/hour, fp", rec["fingerprint"])
    print("  edge list             :", clientkeys.apply_include())
    time.sleep(2)
    note("probe first request", post(rec["key"]), "200")
    rows = subprocess.run(["sqlite3", "/var/lib/relay/relay.db",
                           "select count(*), coalesce(sum(coalesce(total_tokens,0)),0)"
                           " from usage_events where client_key='%s'" % rec["fingerprint"]],
                          capture_output=True, text=True).stdout.strip()
    print("  rows billed to this key:", rows, "(expect a count > 0: attribution is per key)")
    print("  tap caches a key's usage for 15s -- waiting it out")
    time.sleep(17)
    note("probe second request", post(rec["key"]), "429")
    g = clientkeys.gate(rec["key"])
    if g.deny:
        win = (g.detail or {}).get("window")
        st = clientkeys.quota_state(rec)[win]
        print("  gate says             :", g.deny[0], win, "%s/%s" % (st.get("used"), st.get("limit")),
              "releases at", st.get("resets_at"))
    else:
        print("  gate says             : still allowed -- first request recorded no tokens?")
    print("  disable               :", clientkeys.update(rec["id"], {"enabled": False})[0],
          clientkeys.apply_include()[0])
    time.sleep(2)
    note("disabled key request", post(rec["key"]), "401")
    print("  remove                :", clientkeys.remove(rec["id"])[0], clientkeys.apply_include()[0])
'''
    put(c, "/var/tmp/smoke_client_keys.py", script)
    print("\n== end-to-end smoke (runs on the host) ==")
    print(sh(c, "python3 /var/tmp/smoke_client_keys.py 2>&1", timeout=600))
    print("   tap gate in health:",
          sh(c, "python3 -c \"import json;print(json.load(open('/var/lib/relay/public/usagetap.json')).get('keys'))\" 2>&1 | head -1"))
    print("   collector /api/client-keys:",
          sh(c, "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8787/api/client-keys"), "(expect 200)")
    print("   panel proxy route          :",
          sh(c, "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9000/panel/api/relay/client-keys"),
          "(401 = route exists, needs a panel session; 404 = not registered)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="write to the host")
    ap.add_argument("--rollback", action="store_true", help="restore a stamped backup set")
    ap.add_argument("--stamp", default="", help="backup stamp for --rollback")
    ap.add_argument("--no-smoke", action="store_true")
    ap.add_argument("--smoke-only", action="store_true", help="run just the end-to-end checks")
    args = ap.parse_args()

    c = connect()
    try:
        if args.smoke_only:
            smoke(c)
            return
        if args.rollback:
            ts = args.stamp or sh(c, "ls %s 2>/dev/null | grep -o '[0-9]\\{8\\}-[0-9]\\{6\\}' | sort | tail -1"
                                  % posixpath.join(RELAY, "usagetap.py.bak-*"))
            restore(c, ts, ALL_PATHS)
            return
        preflight(c)
        if not args.apply:
            print("\nDRY RUN -- nothing was written. Re-run with --apply.")
            return
        ts = sh(c, "TZ=Asia/Shanghai date +%Y%m%d-%H%M%S")
        apply_all(c, ts)
        if not args.no_smoke:
            smoke(c)
        print("\n== 8. promote the golden copies (or relay-guard restores the old tap and site) ==")
        print(sh(c, "/opt/relay/update_golden.sh 2>&1 | tail -4"))
        print("\nrollback with:  python tools/deploy_client_keys.py --rollback --stamp %s" % ts)
    finally:
        c.close()


if __name__ == "__main__":
    main()
