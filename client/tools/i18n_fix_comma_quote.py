#!/usr/bin/env python3
"""DEPRECATED — breaks string literals like "B", "KB". Use i18n_fix_string_token_comma.py instead."""
import sys
sys.exit("Do not run i18n_fix_comma_quote.py")
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"

# Closing quote of a string literal must not precede `", "` — only identifiers / ].
COMMA_QUOTE = re.compile(r'(?<=[a-zA-Z0-9_\])])", "')


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        t = path.read_text(encoding="utf-8")
        nt = COMMA_QUOTE.sub(', "', t)
        if nt != t:
            path.write_text(nt, encoding="utf-8")
            n += 1
    print(n)


if __name__ == "__main__":
    main()
