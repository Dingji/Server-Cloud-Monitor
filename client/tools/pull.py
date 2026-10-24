#!/usr/bin/env python
"""Download /opt/cloud-monitor (excluding node_modules/runtime/backups/logs) to local ./upstream"""
import os, stat, posixpath
import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
REMOTE = "/opt/cloud-monitor"
LOCAL = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "upstream")
SKIP = {"node_modules", "runtime", "backups", "logs", "tmp", ".git"}

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username=USER, key_filename=KEY, look_for_keys=False, allow_agent=False)
sftp = c.open_sftp()

count = 0
def walk(rdir, ldir):
    global count
    os.makedirs(ldir, exist_ok=True)
    for e in sftp.listdir_attr(rdir):
        if e.filename in SKIP:
            continue
        rp = posixpath.join(rdir, e.filename)
        lp = os.path.join(ldir, e.filename)
        if stat.S_ISDIR(e.st_mode):
            walk(rp, lp)
        else:
            try:
                sftp.get(rp, lp)
                count += 1
                if count % 25 == 0:
                    print(f"  {count} files...", flush=True)
            except Exception as ex:
                print(f"  FAIL {rp}: {ex}")

walk(REMOTE, LOCAL)
sftp.close(); c.close()
print(f"Done. {count} files -> {LOCAL}")
