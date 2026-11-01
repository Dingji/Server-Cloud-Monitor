#!/usr/bin/env python
"""Read-only diagnosis of the Raspberry Pi reachable through frp port 12838.

Tries the keys already present on the cloud box (BatchMode: never prompts, never
tries a password), then reports the Pi's sshd policy and account state.
"""
import os

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
PI = "127.0.0.1"
PORT = os.environ.get("PROBE_PORT", "12838")
USER = os.environ.get("PROBE_USER", "dingji")

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", key_filename=KEY, timeout=25,
          look_for_keys=False, allow_agent=False)


def r(cmd, t=60):
    _, o, e = c.exec_command(cmd, timeout=t)
    rc = o.channel.recv_exit_status()
    return rc, o.read().decode("utf-8", "replace").rstrip(), e.read().decode("utf-8", "replace").rstrip()


print("=== private keys available on the cloud box ===")
print(r("ls -1 /root/.ssh/ | grep -vE 'known_hosts|authorized_keys|\\.pub$'")[0] or "  (none)")

print("\n=== can any of them log into the Pi as %s ? ===" % USER)
rc, names, _ = r("ls -1 /root/.ssh/ | grep -vE 'known_hosts|authorized_keys|\\.pub$|config'")
hit = None
for k in [n for n in names.split() if n]:
    probe = ("ssh -o BatchMode=yes -o ConnectTimeout=8 -o StrictHostKeyChecking=accept-new "
             "-o IdentitiesOnly=yes -i /root/.ssh/%s -p %s %s@%s 'echo OK; hostname; id -un' 2>&1"
             % (k, PORT, USER, PI))
    rc2, out2, _ = r(probe, t=30)
    first = out2.splitlines()[-1] if out2 else ""
    print("  %-18s rc=%-3s %s" % (k, rc2, (out2.replace("\n", " | ")[:150])))
    if rc2 == 0 and "OK" in out2:
        hit = k
print("usable key:", hit or "none -- password-only path on the Pi")

if hit:
    base = ("ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o IdentitiesOnly=yes "
            "-i /root/.ssh/%s -p %s %s@%s " % (hit, PORT, USER, PI))
    q = "sudo -n sh -c %s 2>/dev/null || sh -c %s"
    print("\n=== Pi: sshd policy that matters ===")
    print(r(base + "'grep -REn \"^[[:space:]]*(PasswordAuthentication|KbdInteractiveAuthentication|PermitRootLogin|UsePAM|AllowUsers|DenyUsers|AllowGroups|PubkeyAuthentication)\" /etc/ssh/sshd_config /etc/ssh/sshd_config.d/ 2>/dev/null'")[1] or "  (cannot read; needs root)")
    print("\n=== Pi: effective sshd -T (needs root) ===")
    print(r(base + "'sshd -T 2>/dev/null | grep -iE \"^(passwordauthentication|permitrootlogin|usepam|pubkeyauthentication|kbdinteractiveauthentication|allowusers|denyusers) \" || echo ROOT-REQUIRED'")[1])
    print("\n=== Pi: account state for %s ===" % USER)
    print(r(base + "'passwd -S %s 2>&1; getent passwd %s | cut -d: -f1,3,6,7'" % (USER, USER))[1])
    print("\n=== Pi: authorized_keys ===")
    print(r(base + "'wc -l < ~/.ssh/authorized_keys 2>/dev/null; ssh-keygen -lf ~/.ssh/authorized_keys 2>/dev/null | head'")[1] or "  (none)")
    print("\n=== Pi: recent sshd auth failures ===")
    print(r(base + "'journalctl -u ssh -u sshd --no-pager -n 25 2>/dev/null | grep -iE \"failed|invalid|not allowed|Connection closed|Disconnected\" | tail -12'")[1] or "  (nothing / needs root)")
else:
    print("\nNo cloud key reaches the Pi, so its sshd config cannot be read from here.")
    print("The Pi accepts the password method (server advertised 'publickey,password'),")
    print("so the failure is credential-level, not policy-level.")

c.close()
