#!/usr/bin/env python
"""Inspect the Pi's SSH server state through the frp tunnel (read-only).

Connects with the operator-supplied password via the environment (never argv, never
printed), and reports exactly what would have to change before password auth could
be turned off safely: is there any key that could still get in?
"""
import os
import sys

import paramiko

CLOUD = os.environ.get("SM_HOST", "8.153.153.176")
PI_PORT = int(os.environ.get("PI_PORT", "12838"))
PI_USER = os.environ.get("PI_USER", "dingji")
PI_PW = os.environ.get("PI_PW", "")

if not PI_PW:
    sys.exit("set PI_PW in the environment")

cli = paramiko.SSHClient()
cli.load_system_host_keys()
cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
try:
    cli.connect(CLOUD, port=PI_PORT, username=PI_USER, password=PI_PW, timeout=30,
                look_for_keys=True, allow_agent=True)
except Exception as exc:
    sys.exit("cannot open the Pi through the tunnel: %s: %s" % (type(exc).__name__, exc))


def r(cmd, t=60):
    _, o, e = cli.exec_command(cmd, timeout=t)
    rc = o.channel.recv_exit_status()
    return rc, o.read().decode("utf-8", "replace").rstrip(), e.read().decode("utf-8", "replace").rstrip()


print("=== identity of what we reached (must be the Pi, not the cloud) ===")
print(r("hostname; id -un; . /etc/os-release; echo $PRETTY_NAME; uname -m")[1])

print("\n=== effective auth decisions on the Pi ===")
rc, out, err = r("sudo -n sshd -T 2>/dev/null | grep -iE '^(passwordauthentication|kbdinteractiveauthentication|pubkeyauthentication|permitrootlogin|usepam|authorizedkeysfile|maxauthtries) ' || "
                 "sshd -T 2>/dev/null | grep -iE '^(passwordauthentication|kbdinteractiveauthentication|pubkeyauthentication|permitrootlogin|usepam|authorizedkeysfile|maxauthtries) ' || echo 'sshd -T needs root'")
print(out or err)

print("\n=== where those values come from ===")
print(r("grep -REn '^[[:space:]]*(PasswordAuthentication|KbdInteractiveAuthentication|PubkeyAuthentication|PermitRootLogin|UsePAM)' /etc/ssh/sshd_config /etc/ssh/sshd_config.d/ 2>/dev/null; ls -1 /etc/ssh/sshd_config.d/ 2>/dev/null")[1] or "(nothing explicit -> compiled defaults)")

print("\n=== does the Include directive come first in sshd_config? (decides drop-in precedence) ===")
print(r("grep -n '^[[:space:]]*Include' /etc/ssh/sshd_config")[1] or "(no Include)")

print("\n=== THE GATE: keys that could still log into the Pi ===")
rc, out, _ = r("for f in ~/.ssh/authorized_keys ~/.ssh/authorized_keys2; do [ -f \"$f\" ] && { echo \"-- $f\"; ssh-keygen -lf \"$f\" 2>&1 | sed 's|^|   |'; }; done; ls -ld ~/.ssh ~/.ssh/authorized_keys 2>/dev/null")
print(out or "   (no authorized_keys -> password is the ONLY way in; do NOT disable it)")

print("\n=== the Windows key we would install, for comparison ===")
pub = os.path.expanduser(os.environ.get("PUBKEY_FILE", "~/.ssh/oc_relay_ed25519.pub"))
if os.path.exists(pub):
    line = open(pub, encoding="utf-8").read().strip()
    print("   file:", pub)
    print("   line:", line[:60] + ("..." if len(line) > 60 else ""))
    import subprocess
    fp = subprocess.run(["ssh-keygen", "-lf", "-"], input=line.encode(), capture_output=True)
    print("   fingerprint:", fp.stdout.decode().split()[0] if fp.returncode == 0 else "unparsable")
else:
    print("   MISSING:", pub)

print("\n=== can the Pi still reach the cloud with its own key? (proof it is configured) ===")
print(r("timeout 12 ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o IdentitiesOnly=yes "
        "-i ~/.ssh/id_cloud_ed25519 root@8.153.153.176 'echo PI_TO_CLOUD_OK; hostname' 2>&1 | tail -3")[1])

print("\n=== sshd service name on this box (reload target) ===")
print(r("systemctl list-units --type=service --no-pager | grep -iE 'ssh(d?)\\.service' | head -3")[1])
cli.close()
