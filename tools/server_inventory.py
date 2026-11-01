#!/usr/bin/env python
"""Read-only inventory of the live panel on the server.

No writes, no service restarts. Emits a JSON + text report under tools/_out/.
"""
import json
import os
import posixpath
import sys

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
APP = "/opt/cloud-monitor"

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(REPO, "tools", "_out")


def connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, key_filename=KEY, timeout=25,
              look_for_keys=False, allow_agent=False)
    return c


def run(c, cmd, timeout=120):
    _, out, err = c.exec_command(cmd, timeout=timeout)
    rc = out.channel.recv_exit_status()
    return rc, out.read().decode("utf-8", "replace"), err.read().decode("utf-8", "replace")


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    c = connect()
    report = {}

    cmds = {
        "os": "cat /etc/os-release | head -3; uname -m; node -v; npm -v",
        "tree": "find %s -maxdepth 3 -not -path '*/node_modules/*' -not -path '*/.git/*' | sort" % APP,
        "server_files": "ls -la %s/server %s/server/ops %s/server/ops/php 2>/dev/null" % (APP, APP, APP),
        "public_files": "find %s/public -type f 2>/dev/null | sort" % APP,
        "routes": "grep -rhoE \"api\\.(get|post|put|delete|patch)\\('[^']+'\\)\" %s/server 2>/dev/null | sort -u" % APP,
        "registers": "grep -rnE \"\\.(register|mount)\\(api\" %s/server 2>/dev/null" % APP,
        "systemd": "systemctl cat cloud-monitor.service 2>/dev/null",
        "nginx": "grep -RnihE 'location|proxy_pass|listen|server_name' /etc/nginx/sites-enabled/ /etc/nginx/conf.d/ 2>/dev/null | sed 's/^[[:space:]]*//'",
        "services": "systemctl list-units --type=service --state=running --no-pager --no-legend | head -40",
        "pkg": "ls %s/package.json >/dev/null 2>&1 && cat %s/package.json" % (APP, APP),
        "deps": "ls %s/node_modules 2>/dev/null | head -40" % APP,
        "size": "du -sh %s 2>/dev/null; du -sh %s/node_modules 2>/dev/null" % (APP, APP),
        "php_web": "ls -la %s/public/js/pages 2>/dev/null" % APP,
        "index_html": "grep -oE 'js/pages/[a-zA-Z0-9_.-]+\\.js' %s/public/index.html 2>/dev/null | sort -u" % APP,
        "nav": "grep -nE 'NAV_ITEMS|id:|label:' %s/public/js/app.js 2>/dev/null | head -80" % APP,
        "git": "cd %s && git log --oneline -5 2>/dev/null; git remote -v 2>/dev/null; git status --short 2>/dev/null | head" % APP,
        "other_apps": "ls -la /opt /srv 2>/dev/null",
        "relay": "ls -la /opt/relayhub /opt/relay 2>/dev/null",
    }
    for name, cmd in cmds.items():
        rc, o, e = run(c, cmd)
        report[name] = {"rc": rc, "out": o, "err": e}

    for name in ("tree", "routes", "registers", "public_files", "nav", "index_html",
                 "systemd", "nginx", "server_files", "pkg", "php_web", "other_apps", "git"):
        r = report[name]
        text = r["out"] if r["rc"] == 0 or r["out"] else ("ERR " + r["err"])
        path = os.path.join(OUT_DIR, "server_%s.txt" % name)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("---- %s (rc=%d, %d bytes) ----" % (name, r["rc"], len(text)))
        print(text[:4000])
    c.close()


if __name__ == "__main__":
    sys.exit(main())
