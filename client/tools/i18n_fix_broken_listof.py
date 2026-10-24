#!/usr/bin/env python3
"""Fix listOf(\"a, L[\"k\"], \"b) corruption inside SelectField/formats."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PANEL = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"
ZH = json.loads((ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8"))


def fix_listof_inner(inner: str) -> str:
    prev = None
    while prev != inner:
        prev = inner
        inner = re.sub(r'"([^"]+), (L\["[^"]+"\])', r'"\1", \2', inner)
        inner = re.sub(r'"([^"]+), "', r'"\1", "', inner)
        inner = re.sub(r'"([^"]+)\)', r'"\1")', inner)
        # L["key"] that should be plain literal (extensions)
        for key, val in ZH.items():
            if val.startswith(".") and len(val) < 12:
                inner = inner.replace(f'L["{key}"]', f'"{val}"')
    return inner


def fix(s: str) -> str:
    def repl(m: re.Match) -> str:
        return "listOf(" + fix_listof_inner(m.group(1)) + ")"

    return re.sub(r"listOf\(([^)]*(?:\([^)]*\)[^)]*)*)\)", repl, s)


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        orig = path.read_text(encoding="utf-8")
        if "listOf(" not in orig or ', L["' not in orig:
            continue
        new = fix(orig)
        if new != orig:
            path.write_text(new, encoding="utf-8")
            n += 1
    print(n)


if __name__ == "__main__":
    main()
