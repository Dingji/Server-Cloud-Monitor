#!/usr/bin/env python
"""Read-only: what account names exist where, and can the Pi reach the cloud.

Answers three things before any config change:
  1. does the cloud box even have a 'dingji' account (it only allows key logins for root)
  2. what account / keys does the Pi have locally
  3. what does an ssh from the Pi to the cloud actually get back
"""
import os

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
# the password is supplied by the operator for THIS diagnosis only, via env, never argv
PI_PW = os.environ.get("PI_PW", "")
PORT = "12838"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", key_filename=KEY, timeout=25,
          look_for_keys=False, allow_agent=False)


def r(cmd, t=90):
    _, o, e = c.exec_command(cmd, timeout=t)
    rc = o.channel.recv_exit_status()
    return rc, o.read().decode("utf-8", "replace").rstrip(), e.read().decode("utf-8", "replace").rstrip()


print("=== 1. cloud box accounts ===")
print(r("getent passwd dingji || echo 'no dingji account here'")[1])
print("  login users with shells:", r("grep -E '/(ba|z|)sh$' /etc/passwd | cut -d: -f1 | tr '\\n' ' '")[1])
print("  PermitRootLogin / PasswordAuthentication:",
      r("sshd -T | grep -iE '^(permitrootlogin|passwordauthentication) ' | tr '\\n' ' '")[1])

print("\n=== 2. reaching the Pi through the tunnel from the cloud box ===")
rc, out, _ = r("command -v sshpass || echo 'sshpass NOT installed'")
print(" ", out)
if not PI_PW:
    print("  PI_PW not supplied -> skipping password login (will not put it in argv)")
else:
    # -e reads SSHPASS from the environment: never argv, never a shell history line
    rc, out, err = r("SSHPASS='%s' sshpass -e ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 "
                     "-p %s dingji@127.0.0.1 'echo IN; id -un; hostname; ls -1 ~/.ssh/ 2>/dev/null' 2>&1 | tail -12"
                     % (PI_PW, PORT), t=60)
    print(out or err)

print("\n=== 3. what the Pi would get when it dials the cloud on port 22 ===")
print(r("timeout 8 bash -c 'exec 3<>/dev/tcp/127.0.0.1/22; head -1 <&3'")[1] or "  (no banner)")
print("  known_hosts entries mentioning this host:",
      r("grep -c 8.153.153.176 /root/.ssh/known_hosts 2>/dev/null || echo 0")[1])
c.close()
