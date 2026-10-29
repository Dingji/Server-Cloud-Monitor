#!/usr/bin/env python
"""Open NoteBox admin users (read-only on the database)."""
import json
import os
import sys

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
LEAK = os.environ["LEAKED"]            # candidate password; never hardcoded

PROBE = r'''
import json, sqlite3, sys
leaked = sys.argv[1]
con = sqlite3.connect("file:/opt/notebox/data/notebox.db?mode=ro", uri=True)
cur = con.cursor()
tabs = [t[0] for t in cur.execute("SELECT name FROM sqlite_master WHERE type='table'")]
report = {"tables": tabs, "matches": [], "users": []}
for t in tabs:
    cols = [d[1] for d in cur.execute("PRAGMA table_info(%s)" % t)]
    for col in cols:
        try:
            rows = cur.execute(
                "SELECT COUNT(*) FROM \"%s\" WHERE CAST(\"%s\" AS TEXT)=?" % (t, col), (leaked,)
            ).fetchone()[0]
        except Exception:
            rows = "err"
        if rows not in (0, "err"):
            report["matches"].append({"table": t, "column": col, "rows": rows})
try:
    for uid, email, role, pw in cur.execute(
            'SELECT id, email, role, password FROM users ORDER BY id LIMIT 12'):
        kind = "plaintext-leaked" if pw == leaked else (
            "plaintext-other" if pw and not str(pw).startswith(("pbkdf2", "scrypt", "argon", "sha", "$"))
            else "hash")
        report["users"].append({"id": uid, "email": email, "role": role,
                                "len": len(pw or ""), "kind": kind})
except Exception as e:
    report["users_error"] = str(e)
print(json.dumps(report, ensure_ascii=False))
'''

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username=USER, key_filename=KEY, timeout=25,
          look_for_keys=False, allow_agent=False)
# stdin only: nothing is written to the host, and the DB is opened mode=ro
ch = c.get_transport().open_channel("session", None, None, None)
ch.exec_command("python3 - %s" % json.dumps(LEAK))
ch.sendall(PROBE.encode("utf-8"))
ch.shutdown_write()
out = b"".join(iter(lambda: ch.recv(4096), b"")).decode("utf-8", "replace")
err = b"".join(iter(lambda: ch.recv_stderr(4096), b"")).decode("utf-8", "replace")
ch.recv_exit_status()
print(err.strip()[:600])
try:
    d = json.loads(out)
    print("\ntables:", d["tables"])
    print("cells equal to leaked string:", d["matches"] or "NONE")
    for u in d["users"]:
        print("  user id=%-3s email=%-28s role=%-6s len=%-4s kind=%s" %
              (u["id"], u["email"], u["role"], u["len"], u["kind"]))
    if d.get("users_error"):
        print("users error:", d["users_error"])
except Exception as exc:
    print("(unparsed)", exc)
c.close()
sys.exit(0)
