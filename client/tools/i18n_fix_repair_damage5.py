#!/usr/bin/env python3
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PANEL = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"
ZH = json.loads((ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8"))
REV = {v: k for k, v in ZH.items() if v and len(v) < 80}


def lit(t: str) -> str:
    k = REV.get(t.strip())
    return f'L["{k}"]' if k else f'"{t}"'


def fix(s: str) -> str:
    s = re.sub(
        r'value = L\["(s_[^"]+)"\]([a-z_]+)"\)',
        r'value = "${\1("\2")}"',
        s,
    )
    # wrong key used as expression — restore int/str access
    s = s.replace(
        'value = L["s_int_697d2ec582"]total") ?: 0}"',
        'value = "${s?.int("total") ?: 0}"',
    )
    s = s.replace(
        'value = L["s_int_7edd34ee87"]held")}"',
        'value = "${s.int("held")}"',
    )
    s = re.sub(r'(\d+)} Mbps else "', r'\1} Mbps") else "', s)
    s = re.sub(r'(\d+) 项失败 else "', r'\1 项失败" else "', s)
    s = re.sub(r'(\d+) 项 else "', r'\1 项" else "', s)
    s = re.sub(r'\(if \(op == "授予"\) "grant" else "revoke"\)" \+', r'(if (op == "授予") "grant" else "revoke") +', s)
    s = re.sub(
        r'StatTile\(label = "([^"]+?), value = (L\["[^"]+"\]|"[^"]*"|if [^,]+)',
        lambda m: f'StatTile(label = {lit(m.group(1))}, value = {m.group(2)}',
        s,
    )
    s = s.replace('StatTile(label = "维护, value = "—")', 'StatTile(label = L["维护_72527e2f0e"], value = "—")')
    s = s.replace(
        'L["s_num_feb2a6a726"]tables").toInt()} / ${s.num("indexes").toInt()}"',
        '"${s.num("tables").toInt()} / ${s.num("indexes").toInt()}"',
    )
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
