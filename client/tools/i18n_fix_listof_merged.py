#!/usr/bin/env python3
"""Fix listOf(\"a, \"b, \"c) merged by bind_literals (listOf scope only)."""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"


def fix_listof_calls(s: str) -> str:
    out = []
    i = 0
    while i < len(s):
        m = re.search(r"\blistOf\(", s[i:])
        if not m:
            out.append(s[i:])
            break
        start = i + m.start()
        out.append(s[i:start])
        j = start + len("listOf(")
        depth = 1
        while j < len(s) and depth:
            if s[j] == "(":
                depth += 1
            elif s[j] == ")":
                depth -= 1
            j += 1
        inner = s[start + len("listOf(") : j - 1]
        fixed = inner
        for _ in range(24):
            nf = re.sub(r'("(?:[^"\\]|\\.)*), "', r'\1", "', fixed)
            if nf == fixed:
                break
            fixed = nf
        out.append("listOf(" + fixed + ")")
        i = j
    return "".join(out)


def fix_starts_with(s: str) -> str:
    return re.sub(
        r'\.startsWith\("([\u4e00-\u9fffA-Za-z0-9]+)\)',
        r'.startsWith("\1")',
        s,
    )


def fix_quote_runs(s: str) -> str:
    return re.sub(r'"{3,}", "', '", "', s)


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        if path.name == "Nav.kt":
            continue
        t = path.read_text(encoding="utf-8")
        nt = fix_quote_runs(fix_starts_with(fix_listof_calls(t)))
        if nt != t:
            path.write_text(nt, encoding="utf-8")
            n += 1
    print(n)


if __name__ == "__main__":
    main()
