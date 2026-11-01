#!/usr/bin/env python
"""Fix the tag-overflow template wording (binder kept my underscore separator).

The key slug is opaque; only the zh/en values matter. The other 16 locales hold
Chinese placeholders by repo convention and are left alone.

  python tools/i18n_fix_tag_overflow.py [--check]
"""
import io
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
KEY = "0_个标签_51e24f49b7"
VALUES = {"zh": "+{0} 个标签", "en": "+{0} more"}


def main():
    check = "--check" in sys.argv
    changed = 0
    for lang, want in VALUES.items():
        for base in ("composeApp/src/commonMain/resources/i18n", "composeApp/src/desktopMain/resources/i18n"):
            p = REPO / base / (lang + ".json")
            if not p.exists():
                continue
            d = json.load(io.open(p, encoding="utf-8"))
            if KEY not in d:
                print("FAIL: %s missing key %s" % (p.name, KEY))
                return 2
            if d[KEY] == want:
                continue
            if check:
                print("  %s: %r -> %r" % (p, d[KEY], want))
            else:
                d[KEY] = want
                with io.open(p, "w", encoding="utf-8", newline="\n") as f:
                    json.dump(d, f, ensure_ascii=False, indent=2, sort_keys=True)
                    f.write("\n")
            changed += 1
    print("done, %d file(s) rewritten" % changed if not check else "check: %d to rewrite" % changed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
