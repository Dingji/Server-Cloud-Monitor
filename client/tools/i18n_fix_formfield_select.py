#!/usr/bin/env python3
"""Fix FormField.Select merged label/options: \"label, listOf(\" -> \"label\", listOf(\"."""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"
PAT = re.compile(
    r'(FormField\.Select\("[^"]+", )"([^"]+), listOf\(',
)
FIELD_KEY = re.compile(
    r'FormField\.(Text|Password|Select)\("([^"]+), "',
)


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        t = path.read_text(encoding="utf-8")
        nt = PAT.sub(r'\1"\2", listOf(', t)
        nt = FIELD_KEY.sub(r'FormField.\1("\2", "', nt)
        if nt != t:
            path.write_text(nt, encoding="utf-8")
            n += 1
    print(n)


if __name__ == "__main__":
    main()
