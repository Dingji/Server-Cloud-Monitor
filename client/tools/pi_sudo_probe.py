#!/usr/bin/env python
"""Ask the Pi what sudo can do for the dingji account, without changing anything.

Two questions: (1) is `sudo -n` (no password) usable, (2) if not, does sudo accept the
login password over stdin. The password is written to the exec channel's stdin only --
it never appears in argv, in history, or in any file on the Pi.
"""
import logging
import os
import sys
import time

import paramiko

logging.getLogger("paramiko").setLevel(logging.CRITICAL)

CLOUD = os.environ.get("SM_HOST", "8.153.153.176")
PI_PORT = int(os.environ.get("PI_PORT", "12838"))
PI_USER = os.environ.get("PI_USER", "dingji")
PI_PW = os.environ.get("PI_PW", "")
PUBKEY = os.path.expanduser(os.environ.get("PUBKEY_FILE", "~/.ssh/oc_relay_ed25519"))


def connect(tries=24):
    last = None
    for i in range(1, tries + 1):
        c = paramiko.SSHClient()
        c.load_system_host_keys()
        c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        try:
            c.connect(hostname=CLOUD, port=PI_PORT, username=PI_USER, key_filename=PUBKEY,
                      look_for_keys=False, allow_agent=False, timeout=30)
            print("   (connected on attempt %d)" % i)
            return c
        except Exception as exc:
            last = "%s: %s" % (type(exc).__name__, str(exc).strip()[:100])
            c.close()
            time.sleep(2.5)
    raise RuntimeError("never connected (%s)" % last)


def run(cli, cmd, stdin=None, t=60):
    _, o, e = cli.exec_command(cmd, timeout=t)
    if stdin is not None:
        o.channel.sendall(stdin.encode("utf-8") + b"\n")
        o.channel.shutdown_write()
    rc = o.channel.recv_exit_status()
    return rc, o.read().decode("utf-8", "replace").strip(), e.read().decode("utf-8", "replace").strip()


cli = connect()
print("connected by key")
for label, cmd, si in [
    ("whoami/id", "id", None),
    ("groups", "id -nG", None),
    ("sudo -n true", "sudo -n true 2>&1; echo rc=$?", None),
    ("sudo -n -l", "sudo -n -l 2>&1 | head -20; echo rc=$?", None),
    ("sudo -S via stdin", "sudo -S -p '' id -un 2>&1; echo rc=$?", PI_PW),
    ("sshd -T under sudo", "sudo -n sshd -T 2>/dev/null | grep -iE '^(passwordauthentication|pubkeyauthentication|kbdinteractiveauthentication|maxauthtries) ' | tr '\\n' ' '", None),
    ("service name", "systemctl list-units --type=service --no-pager 2>/dev/null | grep -iE '\\b(ssd|ssh)\\b' | head -5", None),
]:
    rc, out, err = run(cli, cmd, stdin=si)
    print("\n--- %s (rc=%d)" % (label, rc))
    print((out or err or "(empty)")[:600])
cli.close()
