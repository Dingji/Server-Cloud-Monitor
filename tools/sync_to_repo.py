#!/usr/bin/env python
"""Sync the local workspace + live-server snapshot into Server-Cloud-Monitor.

  python tools/sync_to_repo.py --plan     # report only, no writes
  python tools/sync_to_repo.py            # write app/, relayhub/, client/

Byte-exact copies (shutil) so the panel's UTF-8 Chinese sources are never re-encoded.
Sources:
  app/      <- upstream/live-20261029/          (production panel, authoritative)
  relayhub/ <- upstream/live-20261029/relayhub/ (frps + device relay service)
  client/   <- this workspace root              (KMP Compose app + tooling)
"""
import argparse
import hashlib
import os
import shutil
import sys

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIVE = os.path.join(WS, "upstream", "live-20261029")
REPO = os.environ.get("TARGET_REPO", r"D:\Project\ms\_sync\Server-Cloud-Monitor")

# ---------------------------------------------------------------- app/
APP_SRC_DIRS = ["server", "public"]
APP_SRC_FILES = ["agent.py", "package.json", "package-lock.json"]
APP_SKIP_NAMES = {"__pycache__", ".git"}

# ---------------------------------------------------------------- client/
CLIENT_EXCLUDE_DIRS = {
    ".git", ".cursor", ".gradle", ".idea", ".kotlin", ".toolchain", ".qoder",
    "build", "api-dump", "upstream", "node_modules", "__pycache__", ".venv", "venv",
    "captures", "DerivedData", "xcuserdata", ".build", ".swiftpm", "Pods",
    "_out", "bin", "obj", ".vs", "TestResults", ".local-notebox", "dist",
    "publish", "publish-sc", "publish-staging", "_publish_test",
    # Windows/.NET panel port: never part of this repo, build outputs dominate it
    "cloud-monitor-dotnet", "notebox-windows",
}
CLIENT_EXCLUDE_FILES = {
    "local.properties", "tasks.txt", "errors.json", "gradle-out.txt", "Keystone",
    "a.txt", "a1.txt", "a2.txt", "a3.txt", "r.txt", "tmp_patch_relay.py",
}
CLIENT_EXCLUDE_SUFFIX = (".log", ".apk", ".aab", ".msi", ".deb", ".dmg", ".p12",
                         ".pfx", ".jks", ".keystore", ".pem", ".key", ".token",
                         ".tar.gz", ".pyc", ".dSYM", ".before-i18n", ".orig", ".rej")
CLIENT_EXCLUDE_PREFIX = ("build-",)
# panel-web/ ops-server/ live code is mirrored under app/ already; the workspace
# copies stay in client/ as the editing source of truth (as in previous syncs).


def is_excluded_file(name):
    if name in CLIENT_EXCLUDE_FILES:
        return True
    if name.startswith(CLIENT_EXCLUDE_PREFIX) and name.endswith((".txt", ".log")):
        return True
    if name.endswith(CLIENT_EXCLUDE_SUFFIX):
        return True
    return False


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def plan_copy(pairs, dst_root):
    """pairs = iterable of (src, rel); classify each against dst_root."""
    out = []
    for src, rel in pairs:
        dst = os.path.join(dst_root, *rel.split("/"))
        if not os.path.exists(dst):
            out.append((src, dst, "new"))
        elif md5(src) != md5(dst):
            out.append((src, dst, "changed"))
        else:
            out.append((src, dst, "same"))
    return out


def walk_app(root):
    for name in APP_SRC_FILES:
        p = os.path.join(root, name)
        if os.path.isfile(p):
            yield p, name
    for d in APP_SRC_DIRS:
        base = os.path.join(root, d)
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [x for x in dirnames if x not in APP_SKIP_NAMES]
            for fn in filenames:
                full = os.path.join(dirpath, fn)
                yield full, os.path.relpath(full, root).replace("\\", "/")


def walk_client(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in CLIENT_EXCLUDE_DIRS)
        for fn in sorted(filenames):
            if is_excluded_file(fn):
                continue
            full = os.path.join(dirpath, fn)
            yield full, os.path.relpath(full, root).replace("\\", "/")


def walk_relayhub(root):
    base = os.path.join(root, "relayhub")
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = sorted(d for d in dirnames if d not in APP_SKIP_NAMES)
        for fn in filenames:
            if fn.startswith("MANIFEST."):
                continue
            full = os.path.join(dirpath, fn)
            yield full, os.path.relpath(full, base).replace("\\", "/")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", action="store_true")
    args = ap.parse_args()

    jobs = [
        ("app", os.path.join(REPO, "app"), plan_copy(walk_app(LIVE), os.path.join(REPO, "app"))),
        ("relayhub", os.path.join(REPO, "relayhub"), plan_copy(walk_relayhub(LIVE), os.path.join(REPO, "relayhub"))),
        ("client", os.path.join(REPO, "client"), plan_copy(walk_client(WS), os.path.join(REPO, "client"))),
    ]

    grand = {"new": 0, "changed": 0, "same": 0}
    for label, dst_root, plan in jobs:
        counts = {"new": 0, "changed": 0, "same": 0}
        for src, dst, kind in plan:
            counts[kind] += 1
            if kind == "same":
                continue
            rel = os.path.relpath(dst, dst_root).replace("\\", "/")
            print("%-9s %-8s %s" % (label, kind, rel))
        # stale files in repo that production/workspace no longer has
        if os.path.isdir(dst_root):
            keep = {os.path.relpath(d, dst_root).replace("\\", "/") for _, d, _ in plan}
            for dirpath, dirnames, filenames in os.walk(dst_root):
                dirnames[:] = [x for x in dirnames if x not in APP_SKIP_NAMES]
                for fn in filenames:
                    full = os.path.join(dirpath, fn)
                    rel = os.path.relpath(full, dst_root).replace("\\", "/")
                    if label == "client" and rel.replace("\\", "/") in ("README.md", ".gitignore"):
                        continue
                    if rel not in keep:
                        print("%-9s %-8s %s" % (label, "STALE", rel))
        print(">>> %s: new=%d changed=%d same=%d\n" % (label, counts["new"], counts["changed"], counts["same"]))
        for k in grand:
            grand[k] += counts[k]

    print("TOTAL new=%d changed=%d same=%d" % (grand["new"], grand["changed"], grand["same"]))

    if args.plan:
        return 0
    for label, dst_root, plan in jobs:
        for src, dst, kind in plan:
            if kind == "same":
                continue
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
    print("written.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
