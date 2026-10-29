#!/usr/bin/env python
"""Compare the live panel tree against the target repo's app/ tree.

Line-ending agnostic (LF-normalised md5) so a CRLF checkout does not look like
a rewrite. Reports: only-live (missing from repo), only-repo, and changed.
"""
import hashlib
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIVE = os.path.join(REPO, "upstream", "live-20261029")
TARGET = r"D:\Project\ms\_sync\Server-Cloud-Monitor\app"

SKIP = {"MANIFEST.live-md5.txt", "ENV.txt", "page_test.js"}


def norm_md5(path):
    with open(path, "rb") as f:
        data = f.read()
    data = data.replace(b"\r\n", b"\n")
    return hashlib.md5(data).hexdigest(), len(data)


def scan(root):
    out = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in {"node_modules", "__pycache__", ".git"}]
        for fn in filenames:
            if fn in SKIP:
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, root).replace("\\", "/")
            out[rel] = norm_md5(full)
    return out


def main():
    live = scan(LIVE)
    repo = scan(TARGET)
    only_live = sorted(set(live) - set(repo))
    only_repo = sorted(set(repo) - set(live))
    changed = sorted(k for k in set(live) & set(repo) if live[k][0] != repo[k][0])

    print("=== ONLY ON SERVER (missing from repo) : %d ===" % len(only_live))
    for k in only_live:
        print("  + %-55s %9d  (local repo copy: %s)" % (
            k, live[k][1], "yes" if os.path.exists(os.path.join(REPO, *k.split("/"))) else "NO"))
    print("\n=== ONLY IN REPO : %d ===" % len(only_repo))
    for k in only_repo:
        print("  - %-55s %9d" % (k, repo[k][1]))
    print("\n=== CHANGED (content differs) : %d ===" % len(changed))
    for k in changed:
        print("  ~ %-55s live=%9d repo=%9d delta=%+d" % (k, live[k][1], repo[k][1], live[k][1] - repo[k][1]))
    print("\n=== IDENTICAL : %d ===" % (len(set(live) & set(repo)) - len(changed)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
