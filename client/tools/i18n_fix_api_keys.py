#!/usr/bin/env python3
"""Revert mistaken L["key"] replacements where the zh value is a JSON/API field name."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ZH = json.loads((ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8"))
KOTLIN = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"

API_FIELD = re.compile(r"^[a-zA-Z][a-zA-Z0-9]*$")
LIT = re.compile(r'L\["([^"]+)"\]')


def is_api_field(val: str) -> bool:
    if not val or len(val) > 40:
        return False
    if re.search(r"[\u4e00-\u9fff]", val):
        return False
    return bool(API_FIELD.match(val)) or val in {
        "byRoute", "hasPassword", "lastDay", "totalTokens", "sshPort",
    }


def fix_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    orig = text

    def repl(m: re.Match) -> str:
        key = m.group(1)
        val = ZH.get(key, "")
        if is_api_field(val):
            return f'"{val}"'
        return m.group(0)

    text = LIT.sub(repl, text)
    if text != orig:
        path.write_text(text, encoding="utf-8")
        return True
    return False


def main() -> None:
    n = 0
    for p in KOTLIN.rglob("*.kt"):
        if fix_file(p):
            n += 1
    print(f"Fixed {n} files")


if __name__ == "__main__":
    main()
