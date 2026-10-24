#!/usr/bin/env python3
"""Undo i18n_repair_broken_L.py merging L[] with following Kotlin into one string literal."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PANEL = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"
ZH: dict[str, str] = json.loads(
    (ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8")
)

# value -> preferred key
REV: dict[str, str] = {}
for k, v in ZH.items():
    if not v or len(v) > 120:
        continue
    if k not in REV or re.match(r"^[a-z][a-z0-9_.]*$", k):
        REV.setdefault(v, k)


def key_for(text: str) -> str:
    t = text.strip()
    if t in REV:
        return REV[t]
    return f'LIT_{t[:20]}'


def lit(text: str) -> str:
    k = REV.get(text.strip())
    if k:
        return f'L["{k}"]'
    esc = text.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{esc}"'


def fix_page_scaffold(s: str) -> str:
    return re.sub(
        r'PageScaffold\(title = "([^"]+), desc = (L\["[^"]+"\])\)',
        lambda m: f'PageScaffold(title = {lit(m.group(1))}, desc = {m.group(2)})',
        s,
    )


def fix_panel_card_title(s: str) -> str:
    return re.sub(
        r'PanelCard\(title = "([^"]+), right = (\{[^}]+\})\)',
        lambda m: f'PanelCard(title = {lit(m.group(1))}, right = {m.group(2)})',
        s,
    )


def fix_btn_text(s: str) -> str:
    for name in ("GhostBtn", "PrimaryBtn", "DangerBtn", "BlockButton", "SmallButton"):
        s = re.sub(
            rf'{name}\(text = "([^"]+), onClick = ',
            lambda m, n=name: f'{n}(text = {lit(m.group(1))}, onClick = ',
            s,
        )
        s = re.sub(
            rf'{name}\(text = "([^"]+), icon = "([^"]+)", onClick = ',
            lambda m, n=name: f'{n}(text = {lit(m.group(1))}, icon = "{m.group(2)}", onClick = ',
            s,
        )
    return s


def fix_stat_tile(s: str) -> str:
    s = re.sub(
        r'StatTile\(label = "([^"]+), value = (L\["[^"]+"\]|"[^"]*"|Fmt\.[^,]+|[^,]+), sub = ',
        lambda m: f'StatTile(label = {lit(m.group(1))}, value = {m.group(2)}, sub = ',
        s,
    )
    s = re.sub(
        r'StatTile\(label = "([^"]+), value = (L\["[^"]+"\]|"[^"]*"|[^,]+), sub = ([^,]+), icon = "([^"]+)"\)',
        lambda m: f'StatTile(label = {lit(m.group(1))}, value = {m.group(2)}, sub = {m.group(3)}, icon = "{m.group(4)}")',
        s,
    )
    s = re.sub(
        r'label = "([^"]+), value = (L\["[^"]+"\][^,]*), sub = ',
        lambda m: f'label = {lit(m.group(1))}, value = {m.group(2)}, sub = ',
        s,
    )
    return s


def fix_field(s: str) -> str:
    return re.sub(
        r'label = "([^"]+), hint = (L\["[^"]+"\])',
        lambda m: f'label = {lit(m.group(1))}, hint = {m.group(2)}',
        s,
    )


def fix_ranges_listof(s: str) -> str:
    def repl(m: re.Match) -> str:
        inner = m.group(1)
        parts = re.findall(r'"([^"]+)" to "([^"]+)"', inner)
        if not parts:
            # merged: "10m" to "10分钟, "1h" to ...
            chunks = re.split(r', (?=")', inner)
            out = []
            for ch in chunks:
                ch = ch.strip().strip(",")
                pm = re.match(r'"([^"]+)" to (?:"([^"]*)"|(L\["[^"]+"\]))', ch)
                if pm:
                    a, b, l = pm.group(1), pm.group(2), pm.group(3)
                    if l:
                        out.append(f'"{a}" to {l}')
                    elif b:
                        out.append(f'"{a}" to {lit(b)}')
            if out:
                return "listOf(" + ", ".join(out) + ")"
        return m.group(0)

    return re.sub(r"listOf\(([^)]+)\)", repl, s)


def fix_table_col_title(s: str) -> str:
    return re.sub(
        r'TableCol<([^>]+)>\(title = "([^"]+), (weight = [^)]+\))',
        lambda m: f'TableCol<{m.group(1)}>(title = {lit(m.group(2))}, {m.group(3)}',
        s,
    )


def fix_l_suffix_in_code(s: str) -> str:
    """L["key"]sessions")}\" -> it.long(\"sessions\")"""
    s = re.sub(
        r'L\["(it_[^"]+)"\]([a-zA-Z0-9_"]+)\)',
        lambda m: f'{m.group(1).replace("it_long", "it.long").replace("it_first_long", "it.first.long")}("{m.group(2).strip(chr(34))}")',
        s,
    )
    # it.num(L["key"]) -> it.num("field")
    for m in list(re.finditer(r'\.(num|str|long)\(L\["([^"]+)"\]\)', s)):
        field = ZH.get(m.group(2), m.group(2))
        if re.match(r"^[a-z_][a-z0-9_]*$", field):
            s = s.replace(m.group(0), f'.{m.group(1)}("{field}")')
    # badL["..."]warn -> "warn"
    s = re.sub(r'badL\["[^"]+"\]', '"warn"', s)
    # PanelIcon(L["shieldalert..."] -> PanelIcon("shieldAlert"
    s = re.sub(
        r'PanelIcon\(L\["shieldalert_[^"]+"\]',
        'PanelIcon("shieldAlert"',
        s,
    )
    s = re.sub(
        r', icon = (L\["[a-z]+_[^"]+"\])\s*$',
        lambda m: f', icon = "{ZH.get(m.group(1)[3:-2], "file")}"' if m.group(1).startswith('L["') else m.group(0),
        s,
    )
    return s


def fix_note_common_when(s: str) -> str:
    return s.replace(
        'today -> "今天; tomorrow -> L["明天_b76ce230d3"]; else -> dueDate',
        'today -> L["今天_b76ce230d3"]; tomorrow -> L["明天_b76ce230d3"]; else -> dueDate',
    )


def fix_ops_files_title(s: str) -> str:
    return s.replace(
        '服务端 findL["else_97dccf7b6e"]本地扫描',
        '服务端 find"} else {"本地扫描',
    )


def fix_db_tables_page(s: str) -> str:
    if 'L["else_97dccf7b6e"]L["页_005ca94968"]' in s:
        s = s.replace(
            ' / ${maxPage + 1}L["else_97dccf7b6e"]L["页_005ca94968"]',
            ' / ${maxPage + 1}"} else {""}页',
        )
    return s


def fix_ops_hosts(s: str) -> str:
    return re.sub(
        r'label = "生产环境, value = L\["([^"]+)"\]生产"\) \?: 0\}"',
        r'label = L["生产环境_"], value = ${s.byEnv.get("生产") ?: 0}"',  # noqa - manual below
        s,
    )


def process(text: str) -> str:
    text = fix_note_common_when(text)
    text = fix_ops_files_title(text)
    text = fix_db_tables_page(text)
    text = fix_page_scaffold(text)
    text = fix_panel_card_title(text)
    text = fix_btn_text(text)
    text = fix_stat_tile(text)
    text = fix_field(text)
    text = fix_table_col_title(text)
    text = fix_l_suffix_in_code(text)
    # OpsHostsPage specific
    text = text.replace(
        'label = "生产环境, value = L["s_byenv_get_951b526fa1"]生产") ?: 0}"',
        'label = L["生产环境_7f152260df"], value = "${s.byEnv.get("生产") ?: 0}"',
    )
    return text


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        orig = path.read_text(encoding="utf-8")
        new = process(orig)
        if new != orig:
            path.write_text(new, encoding="utf-8")
            n += 1
    print(f"Fixed {n} files")


if __name__ == "__main__":
    main()
