#!/usr/bin/env python
"""Minimal SSH runner for recon. Usage: python sh.py "command" """
import sys, os
import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
PORT = int(os.environ.get("SM_PORT", "22"))
USER = os.environ.get("SM_USER", "root")
PASS = os.environ.get("SM_PASS", "Ladridibiciclette!")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))

def run(cmd, timeout=180):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        c.connect(HOST, port=PORT, username=USER, key_filename=KEY,
                  timeout=25, banner_timeout=25, auth_timeout=25,
                  look_for_keys=False, allow_agent=False)
    except paramiko.ssh_exception.SSHException:
        c.connect(HOST, port=PORT, username=USER, password=PASS,
                  timeout=25, banner_timeout=25, auth_timeout=25,
                  look_for_keys=False, allow_agent=False)
    stdin, stdout, stderr = c.exec_command(cmd, timeout=timeout, get_pty=False)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    rc = stdout.channel.recv_exit_status()
    c.close()
    return rc, out, err

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "echo hi"
    rc, out, err = run(cmd)
    sys.stdout.write(out)
    if err.strip():
        sys.stdout.write("\n=== STDERR ===\n" + err)
    sys.stdout.write(f"\n=== rc={rc} ===\n")
