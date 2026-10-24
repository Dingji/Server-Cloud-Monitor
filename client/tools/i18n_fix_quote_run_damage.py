#!/usr/bin/env python3
"""Undo accidental global ', "' -> '", "' repair (collapsed into quote runs)."""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"


def fix(s: str) -> str:
    s = re.sub(r'"{3,}", "', '", "', s)
    s = re.sub(r'\.uppercase\(\)\"{2,}', ".uppercase(), ", s)
    s = re.sub(r'in arrayOf\("([^"]+)\"{3,}', r'in arrayOf("\1"', s)
    s = s.replace("substringAfterLast('.'\", \"\")", "substringAfterLast('.', \"\")")
    s = re.sub(r' to ([^"]+)", "', r' to \1, "', s)
    s = re.sub(r'\)\"", "', '), "', s)
    s = re.sub(r'\]\"", "', '], "', s)
    s = s.replace('.replace("\", "&quot;")', '.replace("\\"", "&quot;")')
    s = s.replace('.replace("\", "")', '.replace("\\\\", "")')
    s = re.sub(r'PageId\.(\w+)", "', r'PageId.\1, "', s)
    for old, new in (
        ('st", "', 'st, "'),
        ('e.id", "', 'e.id, "'),
        ('engine.id", "', 'engine.id, "'),
        ('PageId.Audit", "', 'PageId.Audit, "'),
        ('scope", "', 'scope, "'),
        ('createFolder(name", "', 'createFolder(name, "'),
    ):
        s = s.replace(old, new)
    s = re.sub(r'\.toInt\(\)", "', '.toInt(), "', s)
    return s


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        t = path.read_text(encoding="utf-8")
        nt = fix(t)
        if nt != t:
            path.write_text(nt, encoding="utf-8")
            n += 1
    print(n)


if __name__ == "__main__":
    main()
