#!/usr/bin/env python
"""Revert the Pi back to key+password SSH, and prove both work afterwards.

Order: connect with the key (password is still off at this point) -> park the drop-in
under a non-.conf name so sshd stops reading it but it is one rename away from coming back
-> sshd -t -> systemctl reload ssh (never restart) -> verify from this workstation
that the password method is accepted again AND key login still works.

Nothing is deleted: the drop-in is renamed, so rollback is one `mv`. Run with PI_DRYRUN=1
to only show what would change.

The tunnel intermittently fails ("Not allowed at this time" / banner read errors) while
RelayHub probes the mapping, so every connection here is retried with a backoff.
"""
import os
import subprocess
import sys
import time

import paramiko

import logging as _logging
_logging.getLogger("paramiko").setLevel(_logging.CRITICAL)

CLOUD = os.environ.get("SM_HOST", "8.153.153.176")
PI_PORT = int(os.environ.get("PI_PORT", "12838"))
PI_USER = os.environ.get("PI_USER", "dingji")
PI_PW = os.environ.get("PI_PW", "")
PUBKEY = os.path.expanduser(os.environ.get("PUBKEY_FILE", "~/.ssh/oc_relay_ed25519"))
DROPIN = "/etc/ssh/sshd_config.d/00-key-only.conf"
SERVICE = "ssh"
SUDO = "sudo -n "  # verified passwordless on the Pi: dingji may run (ALL : ALL) ALL
DRYRUN = os.environ.get("PI_DRYRUN", "") == "1"
AUTH_PATTERNS = "^(passwordauthentication|pubkeyauthentication|kbdinteractiveauthentication|maxauthtries) "

if not PI_PW:
    sys.exit("set PI_PW in the environment (never argv)")


def open_pi(auth, tries=24):
    """auth: 'key' or 'pw'. Retries the flaky tunnel."""
    last = None
    for i in range(1, tries + 1):
        c = paramiko.SSHClient()
        c.load_system_host_keys()
        c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        kw = dict(hostname=CLOUD, port=PI_PORT, username=PI_USER, timeout=30)
        if auth == "key":
            kw.update(key_filename=PUBKEY, look_for_keys=False, allow_agent=False)
        else:
            kw.update(password=PI_PW, look_for_keys=False, allow_agent=False)
        try:
            c.connect(**kw)
            return c, i
        except Exception as exc:
            last = "%s: %s" % (type(exc).__name__, str(exc).strip()[:110])
            c.close()
            time.sleep(2.5)
    raise RuntimeError("%s auth never connected in %d tries (last: %s)" % (auth, tries, last))


def r(cli, cmd, t=90):
    _, o, e = cli.exec_command(cmd, timeout=t)
    rc = o.channel.recv_exit_status()
    return rc, o.read().decode("utf-8", "replace").rstrip(), e.read().decode("utf-8", "replace").rstrip()


def effective(cli):
    return r(cli, "%ssshd -T 2>/dev/null | grep -iE '%s' | tr '\\n' ' '" % (SUDO, AUTH_PATTERNS))[1]


def ssh_probe(method, tries=8):
    """One-shot ssh from this box with only the given method; returns (rc, text)."""
    cmd = ["ssh", "-o", "ConnectTimeout=12",
           "-o", "StrictHostKeyChecking=no", "-o", "IdentitiesOnly=yes",
           "-o", "PreferredAuthentications=%s" % method,
           "-o", "NumberOfPasswordPrompts=1"]
    if method == "publickey":
        cmd = cmd + ["-o", "BatchMode=yes", "-i", PUBKEY]
    else:
        cmd += ["-o", "BatchMode=no"]
    cmd += ["-p", str(PI_PORT), "%s@%s" % (PI_USER, CLOUD), "echo PROBE_OK; hostname"]
    env = dict(os.environ)
    if method == "password":
        # sshpass is not on this box; use an askpass helper so the value never hits argv
        helper = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_askpass.sh")
        pwfile = os.path.join(os.path.dirname(helper), "_askpass.txt")
        with open(helper, "w", encoding="utf-8", newline="\n") as f:
            f.write("#!/bin/sh\ncat '%s'\n" % pwfile)
        os.chmod(helper, 0o600)
        with open(pwfile, "w", encoding="utf-8", newline="\n") as f:
            f.write(PI_PW)
        os.chmod(pwfile, 0o600)
        env["SSH_ASKPASS"] = helper
        env["SSH_ASKPASS_REQUIRE"] = "force"
        env["DISPLAY"] = ":0"
    out = ""
    for i in range(1, tries + 1):
        try:
            p = subprocess.run(cmd, capture_output=True, timeout=60, text=True, env=env)
            out = (p.stdout + p.stderr).strip()
            if p.returncode == 0 or "Permission denied" in out:
                return p.returncode, out
            # anything else ("Connection closed", "Not allowed at this time", banner
            # errors) is the flaky frps tunnel, not an auth verdict -> retry
        except Exception as exc:
            out = str(exc)
        time.sleep(2.5)
    return 1, out


if os.environ.get("PI_PROBE_ONLY", "") == "1":
    print("=== probe only (config already reverted) ===")
    rc, out = ssh_probe("password")
    print("   password method  :", "ACCEPTED" if rc == 0 and "PROBE_OK" in out else "BLOCKED -> " + out[:200])
    rc2, out2 = ssh_probe("publickey")
    print("   key method       :", "ACCEPTED" if rc2 == 0 and "PROBE_OK" in out2 else "BROKEN -> " + out2[:200])
    for tmp in ("_askpass.sh", "_askpass.txt"):
        try:
            os.remove(os.path.join(os.path.dirname(os.path.abspath(__file__)), tmp))
        except OSError:
            pass
    sys.exit(0 if rc == 0 and rc2 == 0 else 1)

print("=== 0. current state (reached by key, since passwords are still off) ===")
cli, n = open_pi("key")
print("   connected via KEY on attempt %d" % n)
print("    effective:", effective(cli) or "(unreadable)")
print("   ", r(cli, "ls -l %s 2>/dev/null || echo 'drop-in already gone'" % DROPIN)[1])
print("   ", r(cli, "cat %s 2>/dev/null | grep -vE '^\\s*(#|$)'" % DROPIN)[1].replace("\n", " | "))

if DRYRUN:
    print("\nDRY RUN: would run")
    print("   %smv %s %s.disabled-<ts>   (park, not delete)" % (SUDO, DROPIN, DROPIN))
    print("   %ssshd -t                  (gate)" % SUDO)
    print("   %ssystemctl reload %s      (reload, never restart)" % (SUDO, SERVICE))
    cli.close()
    sys.exit(0)

print("\n=== 1. park the drop-in (kept, one rename away from coming back) ===")
rc, out, err = r(cli, "ts=$(date +%%s); %smv %s %s.disabled-$ts && echo MOVED-$ts && %sls -l /etc/ssh/sshd_config.d/"
                 % (SUDO, DROPIN, DROPIN, SUDO))
print("   ", out or err)
if "MOVED" not in out:
    cli.close()
    sys.exit("could not park the drop-in -- nothing was reloaded")

print("\n=== 2. validate, then reload (not restart) ===")
rc, out, err = r(cli, "%ssshd -t && echo SSHD_TEST_OK || echo SSHD_TEST_FAILED" % SUDO)
print("   ", out or err)
if "SSHD_TEST_OK" not in out:
    # move the parked file back under its real name; do NOT delete anything here
    r(cli, "for f in %s.disabled-*; do [ -e \"$f\" ] && %smv \"$f\" %s && break; done"
          % (DROPIN, SUDO, DROPIN))
    print("   sshd -t failed -> drop-in restored, nothing reloaded:",
          r(cli, "%sls -l %s 2>/dev/null || echo MISSING" % (SUDO, DROPIN))[1])
    cli.close()
    sys.exit("sshd -t failed; restored the drop-in and reloaded nothing")
rc, out, err = r(cli, "%ssystemctl reload %s && sleep 1 && %ssystemctl is-active %s" % (SUDO, SERVICE, SUDO, SERVICE))
print("   reload:", out or err)
eff = effective(cli)
print("    effective now:", eff or "(unreadable)")
if "passwordauthentication yes" not in eff:
    print("   !! PasswordAuthentication did not flip to yes -- passwords are still off")
cli.close()

print("\n=== 3. prove both doors work ===")
rc, out = ssh_probe("password")
print("   password method  :", "ACCEPTED" if rc == 0 and "PROBE_OK" in out else "STILL BLOCKED -> " + out[:200])
rc2, out2 = ssh_probe("publickey")
print("   key method       :", "ACCEPTED" if rc2 == 0 and "PROBE_OK" in out2 else "BROKEN -> " + out2[:200])
try:
    cli3, n3 = open_pi("pw", tries=24)
    print("   paramiko password login: OK (attempt %d) ->" % n3, r(cli3, "hostname; id -un")[1].replace("\n", " | "))
    cli3.close()
except Exception as exc:
    rc = 1
    print("   paramiko password login: FAILED", exc)

for tmp in ("_askpass.sh", "_askpass.txt"):
    try:
        os.remove(os.path.join(os.path.dirname(os.path.abspath(__file__)), tmp))
    except OSError:
        pass

if rc != 0 or rc2 != 0:
    print("\n!! one door did not verify -- check: sudo ls /etc/ssh/sshd_config.d/  and  journalctl -u ssh -n 40")
    sys.exit(1)
print("\nDone. 007911 works again over SSH, and the key still works.")
print("Re-harden any time:  sudo mv /etc/ssh/sshd_config.d/00-key-only.conf.disabled-* \\")
print("                        /etc/ssh/sshd_config.d/00-key-only.conf && sudo systemctl reload ssh")
