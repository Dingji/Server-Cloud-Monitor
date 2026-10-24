#!/usr/bin/env python3
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        t = path.read_text(encoding="utf-8")
        nt = re.sub(r'L\["([^"]+)"\]"', r'L["\1"]', t)
        nt = re.sub(r'L\["([^"]+)"\]",', r'L["\1"],', nt)
        nt = re.sub(r'(to [a-zA-Z0-9_]+)", (L\[")', r'\1, \2', nt)
        nt = re.sub(r'(to [a-zA-Z0-9_]+)",', r'\1,', nt)
        nt = re.sub(r'\.([a-z]+)""', r'.\1"', nt)
        if nt != t:
            path.write_text(nt, encoding="utf-8")
            n += 1
    print(n)


if __name__ == "__main__":
    main()
