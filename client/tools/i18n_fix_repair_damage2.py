#!/usr/bin/env python3
"""Second pass: fix merged listOf pairs and broken .int(field\") calls."""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"


def fix_merged_to_pairs(s: str) -> str:
    # "10m" to "10分钟, "1h" -> "10m" to "10分钟", "1h"
    prev = None
    while prev != s:
        prev = s
        s = re.sub(r'( to ")([^"]+)(, ")', r'\1\2", "', s)
    return s


def fix_json_accessors(s: str) -> str:
    s = re.sub(r'\.(int|str|long|num)\(([a-zA-Z_][a-zA-Z0-9_]*)"\)', r'.\1("\2")', s)
    return s


def fix_long_sessions(s: str) -> str:
    s = s.replace('ru.long(sessions"))', 'ru.long("sessions"))')
    return s


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        orig = path.read_text(encoding="utf-8")
        new = fix_long_sessions(fix_json_accessors(fix_merged_to_pairs(orig)))
        if new != orig:
            path.write_text(new, encoding="utf-8")
            n += 1
    print(f"Fixed {n} files")


if __name__ == "__main__":
    main()
