#!/usr/bin/env python3
"""Close string literals before ' else ' in corrupted if-expressions."""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"

REPLACEMENTS = [
    (
        r'\(if \(netLimit > 125000\) " · 上限 \$\{MonFmt\.fixed\(netLimit / 1e6, 1\)\} Mbps else " · 基准 1 Mbps"\)',
        '(if (netLimit > 125000) " · 上限 ${MonFmt.fixed(netLimit / 1e6, 1)} Mbps" else " · 基准 1 Mbps")',
    ),
    (
        'value = "${s_int_697d2ec582("total")}" ?: 0}"',
        'value = "${s?.int("total") ?: 0}"',
    ),
    (
        'value = "${s_int_7edd34ee87("held")}"',
        'value = "${s.int("held")}"',
    ),
    (r'if \(failing > 0\) "\$failing 项失败 else "全部正常"', 'if (failing > 0) "$failing 项失败" else "全部正常"'),
    (r'L\["需处理_ed5909bac1"\]" else', 'L["需处理_ed5909bac1"] else'),
]


def fix(s: str) -> str:
    for a, b in REPLACEMENTS:
        s = re.sub(a, b, s) if a.startswith("(") or "\\" in a else s.replace(a, b)
    # generic: "TEXT else " when TEXT ends with CJK or Mbps or 项
    s = re.sub(r'("(?:[^"\\]|\\.)*?)( Mbps) else "', r'\1\2") else "', s)
    s = re.sub(r'(\$\{[^}]+\} 项)( else ")', r'\1"\2', s)
    s = re.sub(r'(\d+ 项失败)( else ")', r'\1"\2', s)
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
