#!/usr/bin/env python
"""Swap this machine's relay credential from the legacy master token to a
distributed key.

Two places hold the old token:
  1. HKCU\\Environment -> OPENCODE_RELAY_KEY (what the running opencode reads)
  2. opencode.db credential row (integration `opencode-go`) -- the auth-store
     fallback; leaving a stale key there makes attribution drift back to it.

Both are backed up next to this script before anything is written. The key is
NEVER hardcoded here and never printed -- pass it on the command line only if
your shell history policy allows, otherwise export RELAY_KEY first.

  RELAY_KEY=rk_xxx python tools/swap_local_relay_key.py   # do the swap
  python tools/swap_local_relay_key.py --rollback         # restore both from backup
"""
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import time
import winreg

ENV_NAME = "OPENCODE_RELAY_KEY"
DB = os.path.expanduser(r"~/.local/share/opencode/opencode.db")
INTEGRATION = "opencode-go"
# Backups hold live credentials: keep them OUTSIDE any repository.
BACKUP_DIR = os.path.expanduser(r"~/.config/opencode/relay")
BACKUP = os.path.join(BACKUP_DIR, "relay_key_swap_backup_%s.json" % time.strftime("%Y%m%d-%H%M%S"))


def new_key():
    k = os.environ.get("RELAY_KEY", "").strip()
    if not k.startswith("rk_"):
        raise SystemExit("FAIL: export RELAY_KEY=rk_... first (the key is never hardcoded here)")
    return k


def read_env_value():
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as k:
        return winreg.QueryValueEx(k, ENV_NAME)[0]


def mask(s):
    return (s[:8] + "...(%d chars)" % len(s)) if s else "(empty)"


def db_credential(con):
    cur = con.execute("SELECT id, value FROM credential WHERE integration_id=?", (INTEGRATION,))
    row = cur.fetchone()
    if not row:
        raise SystemExit("FAIL: no credential row for integration %s" % INTEGRATION)
    return row


def main():
    rollback = "--rollback" in sys.argv
    old_env = read_env_value()
    con = sqlite3.connect(DB)
    cred_id, cred_value = db_credential(con)
    old_store_key = json.loads(cred_value).get("key", "")

    if rollback:
        cands = sorted(
            f for f in os.listdir(BACKUP_DIR)
            if f.startswith("relay_key_swap_backup_")
        )
        if not cands:
            raise SystemExit("FAIL: no backup file to roll back to")
        with open(os.path.join(BACKUP_DIR, cands[-1]), encoding="utf-8") as f:
            bak = json.load(f)
        subprocess.run(["setx", ENV_NAME, bak["env"]], check=True, capture_output=True)
        con.execute("UPDATE credential SET value=? WHERE id=?", (bak["credential_value"], bak["credential_id"]))
        con.commit()
        print("rolled back: env=%s store=%s" % (mask(bak["env"]), mask(json.loads(bak["credential_value"])["key"])))
        return 0

    nk = new_key()
    print("env     : %s -> %s" % (mask(old_env), mask(nk)))
    print("store   : %s -> %s (row %s)" % (mask(old_store_key), mask(nk), cred_id))
    if old_env == nk and old_store_key == nk:
        print("nothing to do")
        return 0

    with open(BACKUP, "w", encoding="utf-8") as f:
        json.dump({"env": old_env, "credential_id": cred_id, "credential_value": cred_value,
                   "db": DB, "at": time.strftime("%Y-%m-%d %H:%M:%S")}, f, indent=1)
    print("backup  : %s" % os.path.basename(BACKUP))

    subprocess.run(["setx", ENV_NAME, nk], check=True, capture_output=True)
    new_value = json.dumps({"type": "key", "key": nk})
    con.execute("UPDATE credential SET value=? WHERE id=?", (new_value, cred_id))
    con.commit()

    print("verify  : env=%s store=%s" % (mask(read_env_value()), mask(json.loads(db_credential(con)[1])["key"])))
    print("NOTE    : running opencode processes still hold the OLD value -- restart them to pick this up")
    return 0


if __name__ == "__main__":
    sys.exit(main())
