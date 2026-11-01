#!/usr/bin/env python
"""Harden the Pi to key-only SSH -- with a hard gate that refuses to disable
password auth until a key login is proven to work end-to-end.

Order of operations (each step must pass before the next):
  1. install an operator-held public key into ~/.ssh/authorized_keys on the Pi
     (backup first; additive; correct modes for StrictModes)
  2. PROVE key-only login works, from this workstation, through the same tunnel
     (BatchMode + PreferredAuthentications=publickey: no password fallback possible)
  3. write sshd drop-in 00-key-only.conf, sshd -t, then `systemctl reload ssh`
     (reload, never restart: restart would drop every live session)
  4. re-verify: key login still works AND the password method is now refused

Any failed gate leaves the Pi exactly as it was, with the drop-in removed.

  python tools/pi_key_only.py --dry-run
  python tools/pi_key_only.py
"""
import os
import subprocess
import sys
import time

import paramiko

CLOUD = os.environ.get("SM_HOST", "8.153.153.176")
PI_PORT = int(os.environ.get("PI_PORT", "12838"))
PI_USER = os.environ.get("PI_USER", "dingji")
PI_PW = os.environ.get("PI_PW", "")
PUBKEY_FILE = os.path.expanduser(os.environ.get("PUBKEY_FILE", "~/.ssh/oc_relay_ed25519"))
DROPIN = "/etc/ssh/sshd_config.d/00-key-only.conf"
SERVICE = "ssh"

if not PI_PW:
    sys.exit("set PI_PW=<the Pi password> in the environment (never argv)")
if not os.path.exists(PUBKEY_FILE + ".pub"):
    sys.exit("no public key at %s.pub" % PUBKEY_FILE)

PUBKEY = open(PUBKEY_FILE + ".pub", encoding="utf-8").read().strip()
if not PUBKEY.startswith(("ssh-ed25519 ", "ssh-rsa ", "ecdsa-")):
    sys.exit("unexpected public key format")

def connect_pi(tries=10):
    """The RelayHub panel probes this mapping at 0.5s intervals and frps only keeps
    transport.maxPoolCount work connections, so a real ssh handshake intermittently
    gets 'Not allowed at this time'. Retry rather than treat that as a dead tunnel.
    """
    last = None
    for attempt in range(1, tries + 1):
        client = paramiko.SSHClient()
        client.load_system_host_keys()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        try:
            client.connect(CLOUD, port=PI_PORT, username=PI_USER, password=PI_PW, timeout=30,
                           look_for_keys=False, allow_agent=False)
            if attempt > 1:
                print("   connected on attempt %d" % attempt)
            return client
        except Exception as exc:
            last = "%s: %s" % (type(exc).__name__, str(exc).strip()[:120])
            print("   attempt %d/%d failed: %s" % (attempt, tries, last))
            client.close()
            time.sleep(2.0)
    sys.exit("could not reach the Pi through the tunnel after %d tries (%s)" % (tries, last))


cli = connect_pi()


def r(cmd, t=60):
    _, o, e = cli.exec_command(cmd, timeout=t)
    rc = o.channel.recv_exit_status()
    return rc, o.read().decode("utf-8", "replace").rstrip(), e.read().decode("utf-8", "replace").rstrip()


def sudo(cmd, t=90):
    """sudo -S with the password on stdin: never argv, never /proc/<pid>/cmdline."""
    _, o, e = cli.exec_command("sudo -S -p '' sh -c %s" % _q(cmd), timeout=t)
    o.channel.sendall((PI_PW + "\n").encode())
    rc = o.channel.recv_exit_status()
    out = o.read().decode("utf-8", "replace").rstrip()
    err = e.read().decode("utf-8", "replace").rstrip()
    return rc, out, err


def _q(s):
    return "'" + s.replace("'", "'\\''") + "'"


def key_login(tries=6):
    """Key-only login from THIS workstation through the tunnel, retried: the tunnel
    intermittently answers 'Not allowed at this time' while RelayHub probes it, and a
    single attempt would misread that as a broken key."""
    cmd = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
           "-o", "StrictHostKeyChecking=accept-new", "-o", "IdentitiesOnly=yes",
           "-o", "PreferredAuthentications=publickey",
           "-i", PUBKEY_FILE, "-p", str(PI_PORT),
           "%s@%s" % (PI_USER, CLOUD),
           "echo KEY_LOGIN_OK; hostname"]
    rc, out = 1, ""
    for attempt in range(1, tries + 1):
        try:
            p = subprocess.run(cmd, capture_output=True, timeout=60, text=True)
            rc, out = p.returncode, (p.stdout + p.stderr).strip()
        except Exception as exc:
            rc, out = 1, "%s: %s" % (type(exc).__name__, exc)
        if rc == 0 and "KEY_LOGIN_OK" in out:
            return rc, out + ("  (attempt %d)" % attempt if attempt > 1 else "")
        time.sleep(2.0)
    return rc, out


def bail(why):
    print("\n!! STOPPED: %s" % why)
    print("   password auth on the Pi was NOT changed. Nothing to revert except the key line.")
    cli.close()
    sys.exit(1)


print("=== 0. what we are talking to ===")
rc, out, _ = r("hostname; id -un; uname -m; ls -ld ~/.ssh ~/.ssh/authorized_keys 2>/dev/null; wc -c < ~/.ssh/authorized_keys 2>/dev/null || echo 0")
print(out)
if "dingjiraspberry" not in out:
    bail("tunnel did not land on the Pi")

# home/ssh dir modes matter: sshd StrictModes silently ignores keys if group-writable
print("\n=== 1. install the operator key (additive, backed up) ===")
rc, out, err = r("set -e; d=~/.ssh; mkdir -p $d; chmod 700 $d; touch $d/authorized_keys; "
                 "[ -s $d/authorized_keys ] && cp -a $d/authorized_keys $d/authorized_keys.bak.$(date +%%s) || true; "
                 "chmod 600 $d/authorized_keys; "
                 "grep -qxF %s $d/authorized_keys || printf '%%s\\n' %s >> $d/authorized_keys; "
                 "echo LINES=$(wc -l < $d/authorized_keys); ssh-keygen -lf $d/authorized_keys"
                 % (_q(PUBKEY), _q(PUBKEY)))
print(out or err)
if rc:
    bail("could not write authorized_keys")
rc, fp, _ = r("ssh-keygen -lf ~/.ssh/authorized_keys | awk '{print $2}'")
print("   installed fingerprint:", fp)

print("\n=== 2. GATE: prove key-only login works before touching anything ===")
rc, out = key_login()
print("   rc=%d %s" % (rc, out.replace("\n", " | ")[:200]))
if rc or "KEY_LOGIN_OK" not in out:
    # make the cause legible rather than guessing
    print("   sshd auth log tail:")
    print("     " + (sudo("journalctl -u %s --no-pager -n 12" % SERVICE)[1] or "").replace("\n", "\n     "))
    bail("key login did not work -- refusing to disable password auth")
print("   gate PASSED: a password-free login works through the same path")

if "--dry-run" in sys.argv:
    print("\n[dry-run] would now write %s (PasswordAuthentication no, KbdInteractiveAuthentication no)"
          "\n[dry-run] then: sshd -t && systemctl reload %s   (reload, not restart)" % (DROPIN, SERVICE))
    cli.close()
    sys.exit(0)

print("\n=== 3. write the drop-in, validate, reload ===")
body = ("# key-only SSH: the Pi is reachable from the internet through the frp tunnel on\n"
        "# port 12838, and its password was a 6-digit string. Written by tools/pi_key_only.py.\n"
        "# Revert: rm this file && systemctl reload ssh\n"
        "PasswordAuthentication no\n"
        "KbdInteractiveAuthentication no\n"
        "PubkeyAuthentication yes\n")
rc, out, err = sudo("cat > %s <<'EOF'\n%sEOF\nchmod 644 %s" % (DROPIN, body, DROPIN))
if rc:
    bail("could not write the drop-in: %s" % (err or out))
rc, out, err = sudo("sshd -t && echo SSHD_TEST_OK || echo SSHD_TEST_FAILED")
print("  ", out or err)
if "SSHD_TEST_OK" not in out:
    sudo("rm -f %s" % DROPIN)
    bail("sshd -t rejected the config; drop-in removed, nothing reloaded")
rc, out, err = sudo("systemctl reload %s && systemctl is-active %s" % (SERVICE, SERVICE))
print("   reload:", out or err)

print("\n=== 4. re-verify both directions ===")
rc, out = key_login()
print("   key login still works :", "YES" if (rc == 0 and "KEY_LOGIN_OK" in out) else "NO -> " + out[:200])
p = subprocess.run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
                    "-o", "StrictHostKeyChecking=accept-new",
                    "-o", "PreferredAuthentications=password",
                    "-p", str(PI_PORT), "%s@%s" % (PI_USER, CLOUD), "true"],
                   capture_output=True, timeout=60, text=True)
print("   password method refused:", "YES" if p.returncode != 0 else "NO -> password still works!")
print("     ssh said:", (p.stdout + p.stderr).strip()[:160])
print("   effective:", sudo("sshd -T | grep -iE '^(passwordauthentication|kbdinteractiveauthentication|pubkeyauthentication|permitrootlogin) '")[1].replace("\n", " | "))

if rc != 0:
    print("\n!! key login broke after the reload -- rolling back now")
    sudo("rm -f %s && systemctl reload %s" % (DROPIN, SERVICE))
    rc3, out3 = key_login()
    print("   rollback verified:", "OK" if rc3 == 0 else "STILL FAILING, use the Pi console/LAN")
    bail("rolled back")

print("\nDone. Revert any time (from the Pi or over the key):  sudo rm %s && sudo systemctl reload %s" % (DROPIN, SERVICE))
cli.close()
