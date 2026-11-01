#!/usr/bin/env python
"""Fill the English catalogue for the note editor (分栏 / 滚动同步 / 未保存).

The binder seeds every locale with the Chinese source when the translation cache
has no entry; en.json is the one we keep hand-curated because the screenshots and
the docs are compared against it.

  python tools/i18n_fill_en_notes_editor.py           # write
  python tools/i18n_fill_en_notes_editor.py --check   # report only
"""
import io
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
EN = REPO / "composeApp/src/commonMain/resources/i18n/en.json"
EN2 = REPO / "composeApp/src/desktopMain/resources/i18n/en.json"

TEXT = {
    "分栏_d195fd0f16": "Split",
    "滚动同步_c4f6f6710e": "Sync scroll",
    "未保存_4123f1faaa": "Unsaved",
}


def load(p):
    return json.load(io.open(p, encoding="utf-8"))


def main():
    check = "--check" in sys.argv
    zh = load(REPO / "composeApp/src/commonMain/resources/i18n/zh.json")
    bad = [k for k in TEXT if k not in zh]
    if bad:
        print("FAIL: these keys are missing from zh.json (run the binder first):", ", ".join(bad))
        return 2
    for path in (EN, EN2):
        if not path.exists():
            print("skip (no such catalogue):", path)
            continue
        d = load(path)
        missing = [k for k in TEXT if k not in d]
        if missing:
            print("FAIL: %s missing keys %s" % (path.name, ", ".join(missing)))
            return 3
        changed = 0
        for k, v in TEXT.items():
            if d.get(k) != v:
                if check:
                    print("  %s: %r -> %r" % (k, d.get(k), v))
                else:
                    d[k] = v
                changed += 1
        if check:
            print("%s: %d to rewrite" % (path, changed))
            continue
        if changed:
            with io.open(path, "w", encoding="utf-8", newline="\n") as f:
                json.dump(d, f, ensure_ascii=False, indent=2, sort_keys=True)
                f.write("\n")
        print("%s: wrote %d" % (path, changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
