#!/usr/bin/env python3
"""Fix merged StatTile / SelectField / TableCol params from bind damage."""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"


def fix(s: str) -> str:
    s = re.sub(
        r'StatTile\("([^"]+)", "([^"]+), sub = ',
        r'StatTile("\1", "\2", sub = ',
        s,
    )
    s = re.sub(
        r'StatTile\("([^"]+), ',
        r'StatTile("\1", ',
        s,
    )
    s = re.sub(
        r'SelectField\("([^"]+), \(',
        r'SelectField("\1", (',
        s,
    )
    s = re.sub(r'(\d+(?:\.\d+)?f)", align', r'\1, align', s)
    s = re.sub(r'toString\(\)", sub', r'toString(), sub', s)
    s = re.sub(
        r'"([^"]+ \(ACL\)), "([^"]+)"',
        r'"\1", "\2"',
        s,
    )
    s = re.sub(r'"true, "1"', '"true", "1"', s)
    s = re.sub(
        r'\$\{if \(maxPage != null\)  / \$\{maxPage \+ 1\}L\["else_97dccf7b6e"\]"\}页',
        r'${if (maxPage != null) " / ${maxPage + 1}" else ""}页',
        s,
    )
    s = re.sub(
        r'listOf\("关闭 \(0\), "仅慢查询 \(1\)"',
        r'listOf("关闭 (0)", "仅慢查询 (1)"',
        s,
    )
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
