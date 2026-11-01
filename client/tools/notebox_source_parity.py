#!/usr/bin/env python
"""比对仓库留档的 notebox 源码与线上 /opt/notebox/app/notebox 是否一致。

只比 md5，不下载内容；不一致的文件列出来，免得照着旧契约写面板页面。
  python tools/notebox_source_parity.py
"""
import io
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCAL = os.path.join(REPO, "notebox-server", "notebox")
HOST = "root@8.153.153.176"
KEY = os.path.expanduser("~/.ssh/panel_ed25519")
REMOTE_DIR = "/opt/notebox/app/notebox"


def remote_md5():
    cmd = ["ssh", "-i", KEY, "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=15", HOST,
           "cd %s && md5sum *.py" % REMOTE_DIR]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        print("远端不可达:", out.stderr.strip()[:200])
        return None
    d = {}
    for line in out.stdout.splitlines():
        parts = line.split()
        if len(parts) == 2:
            d[parts[1]] = parts[0]
    return d


def local_md5():
    import hashlib
    d = {}
    for name in sorted(os.listdir(LOCAL)):
        if not name.endswith(".py"):
            continue
        with open(os.path.join(LOCAL, name), "rb") as f:
            d[name] = hashlib.md5(f.read()).hexdigest()
    return d


def main():
    r = remote_md5()
    if r is None:
        return 2
    l = local_md5()
    names = sorted(set(r) | set(l))
    same = diff = 0
    for n in names:
        if n not in l:
            print("  仅线上有  %s" % n)
            diff += 1
        elif n not in r:
            print("  仅仓库有  %s" % n)
            diff += 1
        elif r[n] != l[n]:
            print("  不一致    %s  live=%s local=%s" % (n, r[n][:8], l[n][:8]))
            diff += 1
        else:
            same += 1
    print("一致 %d 个，差异 %d 个" % (same, diff))
    return 0 if diff == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
