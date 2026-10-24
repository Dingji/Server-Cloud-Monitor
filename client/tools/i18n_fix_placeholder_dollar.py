#!/usr/bin/env python3
"""Fix stray '$' before numeric placeholders: `${0}` -> `{0}`.

StringRepository.format() replaces `{0}`..`{n}`; a leftover `$` from the original
Kotlin template made values render like '用户列表 ($3)'. Only pure-digit braces
are touched, so stale code-fragment values with `${expr}` are unaffected.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "composeApp/src/commonMain/resources/i18n"
I18N_DESK = ROOT / "composeApp/src/desktopMain/resources/i18n"

FIX = re.compile(r"\$\{(\d+)\}")


def main() -> None:
    total = 0
    for f in sorted(I18N.glob("*.json")):
        data = json.loads(f.read_text(encoding="utf-8"))
        n = 0
        for k, v in data.items():
            if isinstance(v, str) and FIX.search(v):
                data[k] = FIX.sub(r"{\1}", v)
                n += 1
        if n:
            f.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        total += n
        print(f"{f.stem}: fixed {n}")
    I18N_DESK.mkdir(parents=True, exist_ok=True)
    for f in I18N.glob("*.json"):
        (I18N_DESK / f.name).write_text(f.read_text(encoding="utf-8"), encoding="utf-8")
    print("total fixed:", total, "| desktop mirrored")


def corrupt_value(v: str) -> bool:
    if not v or len(v) > 900:
        return False
    return bool(
        re.search(
            r"\)\s*\?:|\.num\(|\.obj\(|Fmt\.|\$\{|\bL\[|listOf\(|FormField|trf\(",
            v,
        )
    )


if __name__ == "__main__":
    main()
