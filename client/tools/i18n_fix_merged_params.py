#!/usr/bin/env python3
"""Fix merged parameter strings: label/hint, text/icon, TableCol titles, Slo accessors."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PANEL = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"
ZH = json.loads((ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8"))
REV = {v: k for k, v in ZH.items() if v and len(v) < 120}


def lit(t: str) -> str:
    k = REV.get(t.strip())
    return f'L["{k}"]' if k else f'"{t}"'


def field_name(key: str) -> str | None:
    v = ZH.get(key)
    if v and re.match(r"^[a-zA-Z_][a-zA-Z0-9_]*$", v):
        return v
    return None


def fix(s: str) -> str:
    s = re.sub(
        r'label = "([^"]+?), hint = "([^"]+)"',
        lambda m: f'label = {lit(m.group(1))}, hint = "{m.group(2)}"',
        s,
    )
    s = re.sub(
        r'label = "([^"]+?), hint = if ',
        lambda m: f'label = {lit(m.group(1))}, hint = if ',
        s,
    )
    s = re.sub(
        r'text = "([^"]+?), icon = "([^"]+)"',
        lambda m: f'text = {lit(m.group(1))}, icon = "{m.group(2)}"',
        s,
    )
    s = re.sub(
        r'title = "([^"]+?), text = (L\["[^"]+"\])',
        lambda m: f'title = {lit(m.group(1))}, text = {m.group(2)}',
        s,
    )
    s = re.sub(
        r'title = "([^"]+?), collapsible = ',
        lambda m: f'title = {lit(m.group(1))}, collapsible = ',
        s,
    )
    s = re.sub(
        r'label = "([^"]+?), selected = ',
        lambda m: f'label = {lit(m.group(1))}, selected = ',
        s,
    )
    s = re.sub(
        r'sub = "([^"]+?), icon = (L\["[^"]+"\]|"[^"]+")',
        lambda m: f'sub = {lit(m.group(1))}, icon = {m.group(2)}',
        s,
    )
    s = re.sub(
        r'TableCol<([^>]+)>\(title = "([^"]+?), (weight = [^)]+\))',
        lambda m: f'TableCol<{m.group(1)}>(title = {lit(m.group(2))}, {m.group(3)}',
        s,
    )
    s = re.sub(
        r'TableCol\("([^"]+?), ([0-9.]+f)\)',
        lambda m: f'TableCol({lit(m.group(1))}, {m.group(2)})',
        s,
    )
    s = re.sub(r'\.long_[a-f0-9]+\("([^"]+)"\)\}"', r'.long("\1")', s)
    s = re.sub(r'\.first\.long_[a-f0-9]+\("([^"]+)"\)\}"', r'.first.long("\1")', s)
    s = re.sub(r'\.long"([^"]+)"\)\),,', r'.long("\1"),', s)
    s = s.replace('Text(L["it_a0070d5f76"]k"]}"', 'Text("${it["k"]}",')
    s = re.sub(
        r'StatTile\("([^"]+)", "([^"]+), sub = ',
        lambda m: f'StatTile({lit(m.group(1))}, {lit(m.group(2))}, sub = ',
        s,
    )
    s = s.replace(
        'SelectField("db$dbSel, (0..15).map { L["db_it_a4848647a7"] }',
        'SelectField("db$dbSel", (0..15).map { "db$it" }',
    )
    for key, val in ZH.items():
        if re.match(r"^[a-z][a-zA-Z0-9_]*$", val):
            s = s.replace(f'.num(L["{key}"])', f'.num("{val}")')
            s = s.replace(f'.str(L["{key}"])', f'.str("{val}")')
            s = s.replace(f'.long(L["{key}"])', f'.long("{val}")')
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
