#!/usr/bin/env python3
"""Fix when branches like \"正常 -> L[\"key\"] missing closing quote on label."""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"


def fix(s: str) -> str:
    # Only fix when label wrongly starts with a quote: "正常 -> L[...]
    s = re.sub(
        r'"([^"]+) -> (L\["[^"]+"\])',
        r'"\1" -> \2',
        s,
    )
    # Undo mistaken fix on bare identifiers: today" ->  -> today ->
    s = re.sub(r'\b(today|tomorrow|else)" ->', r"\1 ->", s)
    return s


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        orig = path.read_text(encoding="utf-8")
        new = fix(orig)
        if new != orig:
            path.write_text(new, encoding="utf-8")
            n += 1
    print(n)


if __name__ == "__main__":
    main()
