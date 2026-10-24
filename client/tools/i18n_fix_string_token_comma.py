#!/usr/bin/env python3
"""Restore `"token, "next` -> `"token", "next` after overly broad comma-quote fix."""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"
TOKEN = re.compile(
    r'"((?:[a-zA-Z0-9_][a-zA-Z0-9_.-]*|[\u4e00-\u9fff]+|/[^"]*?|#[0-9a-fA-F]{3,8})), "'
)


def fix_bool_arg(s: str) -> str:
    s = re.sub(r'\b(false|true)", "', r'\1, "', s)
    s = re.sub(r'"" \}", "', r'"" }, "', s)
    s = re.sub(r' to "([^"]+), "value"', r' to "\1", "value"', s)
    s = re.sub(r'\}", "', r'}, "', s)
    s = s.replace('listOf(", "', 'listOf("", "')
    s = re.sub(r'"([\u4e00-\u9fff]+), L\[', r'"\1", L[', s)
    s = re.sub(r'setOf\("true, "false, "null"\)', 'setOf("true", "false", "null")', s)
    s = s.replace('(ACL), "', '(ACL)", "')
    s = re.sub(
        r'FormField\.(Text|Password|Select)\("([^"]+), "',
        r'FormField.\1("\2", "',
        s,
    )
    s = re.sub(
        r'"dbbackup-\$\{st\.engineId\}, "schedule"',
        '"dbbackup-${st.engineId}", "schedule"',
        s,
    )
    s = s.replace('listOf("关闭 (0), "仅慢查询 (1)"', 'listOf("关闭 (0)", "仅慢查询 (1)"')
    s = s.replace('"true, "1"', '"true", "1"')
    s = re.sub(r'EmptyState\("([^"]+), icon =', r'EmptyState("\1", icon =', s)
    return s


def fix_nav(s: str) -> str:
    s = re.sub(r'PageId\.(\w+)", "', r'PageId.\1, "', s)
    s = re.sub(r'"(nav\.[a-z0-9_.]+), "', r'"\1", "', s)
    s = s.replace('"list, "nav.section.app"', '"list", "nav.section.app"')
    return s


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        t = path.read_text(encoding="utf-8")
        nt = t
        for _ in range(16):
            nt = TOKEN.sub(r'"\1", "', nt)
            if nt == t:
                break
            t = nt
        nt = fix_bool_arg(fix_nav(nt))
        if nt != path.read_text(encoding="utf-8"):
            path.write_text(nt, encoding="utf-8")
            n += 1
    print(n)


if __name__ == "__main__":
    main()
