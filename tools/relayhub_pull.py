#!/usr/bin/env python
"""Pull the RelayHub service source from the live box into upstream/live-<STAMP>/relayhub/.

Code and static assets only: frps binary, frp downloads, config.json, mappings.json,
frps.toml and *.bak* stay on the server (secrets / host state / large binaries).
"""
import os
import posixpath
import stat
import sys

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
SRC = "/opt/relayhub"

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STAMP = os.environ.get("STAMP", "live")
DEST = os.path.join(REPO, "upstream", STAMP, "relayhub")

WANT = [
    "panel/app.py",
    "panel/install.sh",
    "panel/static/app.js",
    "panel/static/index.html",
    "panel/static/style.css",
    "panel/static/echarts.min.js",
    "deploy.sh",
    "cm-integrate.py",
    "relayhub-nginx.py",
]
SKIP = {"config.json", "mappings.json", "frps.toml"}


def main():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, key_filename=KEY, timeout=25,
              look_for_keys=False, allow_agent=False)
    sftp = c.open_sftp()
    os.makedirs(DEST, exist_ok=True)
    for rel in WANT:
        if posixpath.basename(rel) in SKIP:
            continue
        remote = posixpath.join(SRC, rel)
        try:
            a = sftp.stat(remote)
        except IOError:
            print("MISSING %s" % rel)
            continue
        if not stat.S_ISREG(a.st_mode):
            continue
        local = os.path.join(DEST, *rel.split("/"))
        os.makedirs(os.path.dirname(local), exist_ok=True)
        sftp.get(remote, local)
        print("  %-32s %9d" % (rel, a.st_size))
    # what exists remotely that we did not ask for (so the manifest is honest)
    _, out, _ = c.exec_command(
        "cd %s && find . -type f -not -path './downloads/*' -not -path '*/__pycache__/*' "
        "-not -name '*.bak*' -not -name 'frps' | sort" % SRC, timeout=120)
    out.channel.recv_exit_status()
    with open(os.path.join(DEST, "MANIFEST.remote.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(out.read().decode("utf-8", "replace"))
    sftp.close()
    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
