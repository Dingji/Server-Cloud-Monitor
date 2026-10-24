#!/usr/bin/env python3
"""Fix }" + and )" + patterns from repair damage."""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        t = path.read_text(encoding="utf-8")
        nt = re.sub(r'\{ "-" \}" \+', r'{ "-" } +', t)
        nt = re.sub(r'\)" \+ if', r') + if', nt)
        nt = re.sub(r'(\))" \+ if', r'\1 + if', nt)
        nt = re.sub(r'joinToString\(", "\)" \+', r'joinToString(", ") +', nt)
        nt = re.sub(r'it == "全部标签\) "', r'it == L["全部标签_ffd92f5524"]) "', nt)
        nt = re.sub(r'Regex\("\[\\\\\[\\\\\]\(\) \]\),', r'Regex("[\\\\[\\\\]() ]"),', nt)
        if nt != t:
            path.write_text(nt, encoding="utf-8")
            n += 1
    print(n)


if __name__ == "__main__":
    main()
