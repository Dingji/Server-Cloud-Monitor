#!/usr/bin/env python3
"""Fix fallout from i18n_manual_fixes.py's non-greedy {k:...} regex:
stray braces ended up inside two keys (code + JSON values truncated)."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "composeApp/src/commonMain/resources/i18n"
I18N_DESK = ROOT / "composeApp/src/desktopMain/resources/i18n"

BAD_KEYS = ["中继采集器不可用_0_b4f6a730be", "文本文件_0_c835938868"]
FIXES_JSON = {
    "中继采集器不可用_0_d01f86c7fd": "中继采集器不可用：{0}",
    "文本文件_0_69e2147621": "文本文件 ({0})",
}
CODE_FIXES = [
    (
        ROOT
        / "composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/RelayPage.kt",
        '"中继采集器不可用_0_b4f6a730be}"',
        '"中继采集器不可用_0_d01f86c7fd"',
    ),
    (
        ROOT / "composeApp/src/desktopMain/kotlin/com/servermaintance/panel/Platform.desktop.kt",
        '"文本文件_0_c835938868)}"',
        '"文本文件_0_69e2147621"',
    ),
]


def main() -> None:
    for path, old, new in CODE_FIXES:
        t = path.read_text(encoding="utf-8")
        assert old in t, f"missing in {path}"
        path.write_text(t.replace(old, new), encoding="utf-8")
        print("code fixed:", path.name)
    for f in sorted(I18N.glob("*.json")):
        data = json.loads(f.read_text(encoding="utf-8"))
        removed = [k for k in BAD_KEYS if data.pop(k, None) is not None]
        added = [k for k in FIXES_JSON if k not in data]
        data.update(FIXES_JSON)
        f.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"{f.name}: -{len(removed)} +{len(added)}")
    I18N_DESK.mkdir(parents=True, exist_ok=True)
    for f in I18N.glob("*.json"):
        (I18N_DESK / f.name).write_text(f.read_text(encoding="utf-8"), encoding="utf-8")
    print("desktop mirrored")


if __name__ == "__main__":
    main()
