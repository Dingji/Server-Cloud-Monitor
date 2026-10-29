#!/usr/bin/env python
"""Read-only audit of everything that depends on the leaked string.

Prints only booleans, lengths and names -- never a secret value.
"""
import json
import os
import sys

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
LEAK = os.environ["LEAKED"]            # the value under investigation; never hardcoded

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username=USER, key_filename=KEY, timeout=25,
          look_for_keys=False, allow_agent=False)


def run(cmd, timeout=90):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    rc = o.channel.recv_exit_status()
    return rc, o.read().decode("utf-8", "replace"), e.read().decode("utf-8", "replace")


def show(label, cmd):
    rc, out, err = run(cmd)
    print("%-34s rc=%d %s" % (label, rc, out.strip().replace("\n", " | ")[:220] or err.strip()[:120]))


# --- sshd: how would I even get locked out? ---
show("sshd PasswordAuthentication",
     "sshd -T 2>/dev/null | grep -iE '^(passwordauthentication|pubkeyauthentication|permitrootlogin|kbdinteractiveauthentication|usepam) '")
show("sshd DropIn overrides", "grep -RhE '^(PasswordAuthentication|PermitRootLogin|AuthenticationMethods)' /etc/ssh/sshd_config.d/ 2>/dev/null || echo none")
show("authorized_keys present", "wc -l < /root/.ssh/authorized_keys 2>/dev/null || echo NONE")
show("fail2ban sshd jail", "fail2ban-client status sshd 2>/dev/null | tr '\\n' ' ' || echo 'no sshd jail / not active'")

# --- panel config.json: which fields equal the leaked string? ---
show("config.json keys", "python3 -c \"import json;print(sorted(json.load(open('/opt/cloud-monitor/config.json')).keys()))\"")
print()
rc, out, _ = run("cat /opt/cloud-monitor/config.json")
try:
    cfg = json.loads(out)
except Exception as e:
    cfg = {}
    print("cannot parse config.json:", e)


def probe(path, value):
    if isinstance(value, str) and value:
        same = (value == LEAK)
        print("  %-28s len=%-4d matches_leaked=%s" % (path, len(value), same))
    else:
        print("  %-28s %s" % (path, "empty" if value in ("", None) else type(value).__name__))


for k in ("password", "passwordHash", "sshPasswordHint"):
    if k in cfg:
        probe("config.json." + k, cfg[k])
for k, v in (cfg.get("ssh") or {}).items():
    probe("config.json.ssh." + k, v)
for k, v in (cfg.get("db") or {}).items():
    if isinstance(v, dict):
        for kk, vv in v.items():
            if "pass" in kk.lower() or kk in ("user", "host"):
                probe("config.json.db.%s.%s" % (k, kk), vv)
print()

# --- ops state: remote creds / notify channels may hold the same string ---
show("ops dir", "ls -la /var/lib/cloud-panel/ops 2>/dev/null | tail -20")
rc, out, _ = run("grep -Rl -- %s /var/lib/cloud-panel /opt/cloud-monitor/config.json /opt/notebox 2>/dev/null || echo NONE"
                 % json.dumps(LEAK))
print("\nfiles on host still containing the leaked string:\n  " + out.strip().replace("\n", "\n  "))

# --- mariadb / notebox ---
show("mariadb root auth plugin",
     "mariadb -N -e \"SELECT user,host,plugin FROM mysql.user WHERE user='root'\" 2>&1 | head -4")
show("notebox admin users", "python3 -c \"import sqlite3,glob;p=glob.glob('/opt/notebox/data/*.db')+glob.glob('/var/lib/notebox/*.db');print(p[:2])\" 2>&1")

c.close()
sys.exit(0)
