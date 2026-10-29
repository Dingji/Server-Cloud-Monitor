#!/usr/bin/env python
"""Verify (without printing any secret) whether a candidate password matches the
stored hashes on the live host.  Read-only: DB opened mode=ro, code fed over stdin.
"""
import json
import os
import sys

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
LEAK = os.environ["LEAKED"]            # candidate password; never hardcoded

PROBE = r'''
import hashlib, json, sqlite3, sys
cand = sys.argv[1]
out = {}

# --- panel: scrypt$<salt>$<hex32> (see server/config.js hashPassword) ---
try:
    cfg = json.load(open("/opt/cloud-monitor/config.json", encoding="utf-8"))
    stored = cfg.get("passwordHash") or ""
    scheme, salt, digest = stored.split("$")
    calc = hashlib.scrypt(cand.encode(), salt=bytes.fromhex(salt), dklen=32,
                          n=2**14, r=8, p=1).hex()
    out["panel"] = {"scheme": scheme, "matches_candidate": calc == digest}
except Exception as e:
    out["panel"] = {"error": str(e)[:120]}

# --- notebox: pbkdf2-sha256$<salt>$<hex> ---
try:
    con = sqlite3.connect("file:/opt/notebox/data/notebox.db?mode=ro", uri=True)
    res = []
    for email, role, stored in con.execute("SELECT email, role, password FROM users ORDER BY id"):
        ok = False
        try:
            _s, salt, digest = stored.split("$")
            ok = hashlib.pbkdf2_hmac("sha256", cand.encode(), bytes.fromhex(salt), 200000).hex() == digest
        except Exception:
            pass
        res.append({"email": email, "role": role, "matches_candidate": ok})
    out["notebox_users"] = res
except Exception as e:
    out["notebox_users"] = {"error": str(e)[:120]}

# --- relayhub panel admin ---
try:
    rc = json.load(open("/opt/relayhub/panel/config.json", encoding="utf-8"))
    ok = False
    try:
        ok = hashlib.pbkdf2_hmac("sha256", cand.encode(),
                                 bytes.fromhex(rc.get("admin_password_salt", "")), 60000).hex() \
            == rc.get("admin_password_hash")
    except Exception:
        pass
    out["relayhub_admin"] = {"matches_candidate": ok, "plaintext_admin_pw_stored": bool(rc.get("admin_password"))}
except Exception as e:
    out["relayhub_admin"] = {"error": str(e)[:120]}

# --- does the panel's own scrypt use the same params? sanity: verify a known value round-trips ---
print(json.dumps(out, ensure_ascii=False))
'''

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username=USER, key_filename=KEY, timeout=25,
          look_for_keys=False, allow_agent=False)
ch = c.get_transport().open_channel("session", None, None, None)
ch.exec_command("python3 - %s" % json.dumps(LEAK))
ch.sendall(PROBE.encode("utf-8"))
ch.shutdown_write()
out = b"".join(iter(lambda: ch.recv(4096), b"")).decode("utf-8", "replace")
err = b"".join(iter(lambda: ch.recv_stderr(4096), b"")).decode("utf-8", "replace")
ch.recv_exit_status()
if err.strip():
    print("stderr:", err.strip()[:500])
try:
    d = json.loads(out)
    p = d["panel"]
    print("panel login password == leaked string ? %s   (scheme=%s)" % (p.get("matches_candidate"), p.get("scheme")))
    for u in d["notebox_users"]:
        print("notebox %-14s %-6s password == leaked string ? %s" % (u["email"], u["role"], u["matches_candidate"]))
    r = d["relayhub_admin"]
    print("relayhub admin password == leaked string ? %s  (plaintext stored in config: %s)" %
          (r.get("matches_candidate"), r.get("plaintext_admin_pw_stored")))
    for k, v in d.items():
        if isinstance(v, dict) and "error" in v:
            print(k, "ERROR:", v["error"])
except Exception as exc:
    print("(unparsed)", exc, out[:400])
c.close()
sys.exit(0)
