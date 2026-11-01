#!/usr/bin/env python
"""Download the live panel source tree (read-only on the server).

Targets upstream/live-<date>/ preserving server/ public/ and top-level files.
Secrets (config.json, keys/) are skipped: only code is synced.
"""
import fnmatch
import os
import posixpath
import stat
import re
import sys

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
APP = "/opt/cloud-monitor"

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STAMP = os.environ.get("STAMP", "live")
DEST = os.path.join(REPO, "upstream", STAMP)

SKIP_DIRNAME = {"node_modules", "backups", "keys", "logs", "tmp", "runtime",
                "data", "__pycache__", ".git", "venv", "uploads", "releases"}
# code only: never pull host secrets/state
SKIP_FILE_RE = re.compile(r"(config\.json$|\.bak(\.|$)|-bak\.|\.old(\.|$)|\.before-|\.after-|\.(pem|key|db|sqlite|sqlite3|log|tar\.gz)$|^\.env)")
ALLOW_ROOT = ["agent.py", "package.json", "package-lock.json", "install.sh",
              "uninstall.sh", "deploy-remote.sh", "README.md"]


def connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, key_filename=KEY, timeout=25,
              look_for_keys=False, allow_agent=False)
    return c


def run(c, cmd, timeout=180):
    _, out, err = c.exec_command(cmd, timeout=timeout)
    rc = out.channel.recv_exit_status()
    return rc, out.read().decode("utf-8", "replace"), err.read().decode("utf-8", "replace")


def walk(sftp, base, rel, files):
    for e in sftp.listdir_attr(base):
        name = e.filename
        r = posixpath.join(rel, name) if rel else name
        p = posixpath.join(base, name)
        if stat.S_ISDIR(e.st_mode):
            if name in SKIP_DIRNAME:
                continue
            walk(sftp, p, r, files)
            continue
        if not stat.S_ISREG(e.st_mode):
            continue
        if name in ALLOW_ROOT and rel == "":
            pass
        elif rel == "":
            continue
        if SKIP_FILE_RE.search(r):
            continue
        if not (r.startswith("server/") or r.startswith("public/") or r in ALLOW_ROOT):
            continue
        files.append((p, r, e.st_size))


def main():
    os.makedirs(DEST, exist_ok=True)
    c = connect()
    sftp = c.open_sftp()
    files = []
    walk(sftp, APP, "", files)
    # also the ops php dir (under server/, already covered)
    print("found %d files" % len(files))
    total = 0
    for remote, rel, size in sorted(files, key=lambda x: x[1]):
        local = os.path.join(DEST, *rel.split("/"))
        os.makedirs(os.path.dirname(local), exist_ok=True)
        sftp.get(remote, local)
        total += size
        print("  %-70s %8d" % (rel, size))
    print("downloaded %d files, %d bytes -> %s" % (len(files), total, DEST))

    # manifest with md5 for later diffing
    rc, out, _ = run(c, "cd %s && find server public -type f \\( -name '*.js' -o -name '*.py' "
                        "-o -name '*.html' -o -name '*.css' \\) "
                        "-not -path '*node_modules*' -not -path '*backups*' "
                        "-not -name '*.bak*' -not -name '*~' | sort | xargs md5sum" % APP)
    with open(os.path.join(DEST, "MANIFEST.live-md5.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print("manifest lines: %d" % len(out.splitlines()))

    # route inventory: real patterns used by the panel
    # route inventory is done locally after download (see tools/panel_diff.py)

    # top-level dirs of /opt/cloud-monitor, to know what is not code
    rc, out, _ = run(c, "ls -la %s; echo '--- ops dir ---'; ls -la %s/server/ops; "
                        "echo '--- package.json ---'; cat %s/package.json 2>/dev/null || echo NONE; "
                        "echo '--- node -v ---'; node -v; echo '--- vendor? ---'; ls %s | head" % (APP, APP, APP, APP))
    with open(os.path.join(DEST, "ENV.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print(out[:3000])

    sftp.close()
    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
