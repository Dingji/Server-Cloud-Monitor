#!/usr/bin/env python
"""Restore locale entries that collapsed when i18n_bind_one.py rewrote the files.

The locale JSONs contain duplicate keys left over from earlier merges; loading
them with json.load keeps only the last occurrence, so a read-modify-write
silently drops the earlier ones. This re-adds every key that exists in HEAD but
is missing from the working copy, without touching anything else.

  python tools/i18n_restore_lost.py --dry-run
  python tools/i18n_restore_lost.py
"""
import json
import os
import shutil
import subprocess
import sys

REPO = "."
DIRS = [
    "composeApp/src/commonMain/resources/i18n",
    "composeApp/src/desktopMain/resources/i18n",
]
DOT = sys.argv[1:] == ["--dry-run"]


def head(path):
    p = subprocess.run(["git", "show", "HEAD:" + path.replace("\\", "/")],
                       capture_output=True)
    if p.returncode != 0:
        return None
    try:
        return json.loads(p.stdout.decode("utf-8"))
    except Exception:
        return None


def main():
    total = 0
    for d in DIRS:
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if not f.endswith(".json") or f == "template.json":
                continue
            path = d + "/" + f
            cur = json.loads(open(path, encoding="utf-8").read())
            old = head(path)
            if not old:
                continue
            lost = {k: v for k, v in old.items() if k not in cur}
            if not lost:
                continue
            total += len(lost)
            print("%-12s 恢复 %d 条: %s" % (f, len(lost), ", ".join(list(lost)[:6])))
            if DOT:
                continue
            merged = dict(cur)
            merged.update(lost)
            open(path, "w", encoding="utf-8", newline="\n").write(
                json.dumps(merged, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    print(("dry-run：" if DOT else "") + "共需恢复 %d 条词条" % total)
    if not DOT and total:
        # keep the desktop mirror identical to commonMain
        for f in sorted(os.listdir(DIRS[0])):
            if f.endswith(".json"):
                shutil.copyfile(DIRS[0] + "/" + f, DIRS[1] + "/" + f)
        print("已同步 desktopMain 镜像")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
