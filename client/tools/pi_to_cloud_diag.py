#!/usr/bin/env python
"""Diagnose Pi -> cloud SSH without changing anything.

Two independent channels:
  cloud  root@8.153.153.176:22      (authorised_keys, sshd effective config, bans, log)
  pi     dingji@:12838 via frp      (key files, then the real outbound attempt from inside it)

Every tunnel connection is retried because frps intermittently has no work connection
while RelayHub probes the mapping.
"""
import logging
import os
import time

import paramiko

logging.getLogger("paramiko").setLevel(logging.CRITICAL)

CLOUD = os.environ.get("SM_HOST", "8.153.153.176")
CLOUD_PW = os.environ.get("SM_PASS", "")
PI_PORT = int(os.environ.get("PI_PORT", "12838"))
PI_USER = os.environ.get("PI_USER", "dingji")
KEY = os.path.expanduser(os.environ.get("PUBKEY_FILE", "~/.ssh/oc_relay_ed25519"))


def connect(host, port, user, pw=None, tries=20):
    last = None
    for i in range(1, tries + 1):
        c = paramiko.SSHClient()
        c.load_system_host_keys()
        c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        kw = dict(hostname=host, port=port, username=user, timeout=30)
        if pw:
            kw.update(password=pw, look_for_keys=False, allow_agent=False)
        else:
            kw.update(key_filename=KEY, look_for_keys=False, allow_agent=False)
        try:
            c.connect(**kw)
            return c
        except Exception as exc:
            last = "%s: %s" % (type(exc).__name__, str(exc).strip()[:90])
            c.close()
            time.sleep(2.5)
    raise RuntimeError("%s@%s:%d never connected (%s)" % (user, host, port, last))


def r(cli, cmd, t=60):
    _, o, e = cli.exec_command(cmd, timeout=t)
    o.channel.settimeout(t)
    rc = o.channel.recv_exit_status()
    out = o.read().decode("utf-8", "replace").strip()
    err = e.read().decode("utf-8", "replace").strip()
    return rc, (out or err or "(empty)")


def show(cli, title, cmd, sudo=False):
    full = ("sudo -n sh -c " + repr(cmd)) if sudo else cmd
    rc, text = r(cli, full)
    print("\n--- %s  (rc=%d)" % (title, rc))
    print(text[:1400])


print("=== A. cloud side: root@%s:22 ===" % CLOUD)
cloud = connect(CLOUD, 22, "root", pw=None)
show(cloud, "identity", "id -un; hostname")
show(cloud, "authorized_keys fingerprints",
     "ssh-keygen -lf /root/.ssh/authorized_keys 2>&1 | sed 's/ (/.(/' | tail -12")
show(cloud, "authorized_keys line count", "wc -l < /root/.ssh/authorized_keys")
show(cloud, "perms (StrictModes)", "ls -ld /root /root/.ssh /root/.ssh/authorized_keys")
show(cloud, "sshd effective", "sshd -T | grep -iE '^(permitrootlogin|passwordauthentication|pubkeyauthentication|maxauthtries|port) ' | tr '\\n' ' '")
show(cloud, "fail2ban", "systemctl is-active fail2ban 2>&1; fail2ban-client status 2>&1 | head -6")
show(cloud, "last 25 auth lines mentioning a non-loopback source",
     "journalctl -u ssh -u sshd --no-pager -n 400 2>/dev/null | grep -E 'Accepted|Failed|Invalid|Connection closed|maximum auth|ban' | tail -25")
cloud.close()

print("\n\n=== B. pi side: dingji@%s:%d (through frp) ===" % (CLOUD, PI_PORT))
pi = connect(CLOUD, PI_PORT, PI_USER, pw=None)
show(pi, "identity", "id -un; hostname; ip route get 8.153.153.176 2>&1 | head -2")
show(pi, "~/.ssh contents", "ls -la ~/.ssh/")
show(pi, "pi key fingerprints", "for f in ~/.ssh/*.pub; do ssh-keygen -lf $f; done 2>&1")
show(pi, "egress ip the cloud will see",
     "timeout 8 curl -s https://api.ipify.org 2>&1 || echo '(curl failed)'; "
     "ip -4 addr show scope global 2>/dev/null | grep -oE 'inet [0-9.]+' | tr '\\n' ' '")
show(pi, "raw TCP 22 to cloud + banner",
     "timeout 8 bash -c 'exec 3<>/dev/tcp/8.153.153.176/22; head -c 40 <&3' 2>&1; echo rc=$?")
show(pi, "known_hosts entries for the cloud",
     "ssh-keygen -F 8.153.153.176 2>&1 | head -6; echo ---; grep -c . ~/.ssh/known_hosts 2>/dev/null || echo 'no known_hosts'")

print("\n--- C. the attempt itself, from inside the Pi ---")
for label, cmd in [
    ("with -i id_cloud_ed25519 (IdentitiesOnly)",
     "ssh -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=no -o IdentitiesOnly=yes "
     "-i ~/.ssh/id_cloud_ed25519 root@8.153.153.176 'echo PI_TO_CLOUD_OK; hostname' 2>&1 | tail -12"),
    ("plain  ssh root@8.153.153.176  (what you probably typed)",
     "ssh -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=no "
     "root@8.153.153.176 'echo PI_TO_CLOUD_OK' 2>&1 | tail -12"),
    ("verbose first attempt (auth order)",
     "ssh -v -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=no -o IdentitiesOnly=yes "
     "-i ~/.ssh/id_cloud_ed25519 root@8.153.153.176 true 2>&1 | grep -E 'Offering|Trying private|Authentications that can continue|Next|Server accepts|Authenticated|Permission denied|No such|Loaded|will attempt|peer has' | tail -14"),
]:
    rc, text = r(pi, cmd)
    print("\n[%s] rc=%d\n%s" % (label, rc, text[:1200]))
pi.close()
