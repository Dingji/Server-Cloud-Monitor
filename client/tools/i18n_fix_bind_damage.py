#!/usr/bin/env python3
"""Fix merged PanelCard/StatTile params from overly aggressive i18n_bind_literals."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ZH = json.loads((ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8"))
PANEL = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"

rev: dict[str, str] = {}
for k, v in ZH.items():
    if v and re.search(r"[\u4e00-\u9fff]", v) and len(v) < 120:
        rev.setdefault(v, k)


def key_for(text: str) -> str:
    k = rev.get(text)
    if k:
        return f'L["{k}"]'
    escaped = text.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def fix_content(s: str) -> str:
    s = re.sub(
        r'PanelCard\(title = "([^"]+?), right = ',
        lambda m: f"PanelCard(title = {key_for(m.group(1))}, right = ",
        s,
    )
    s = re.sub(
        r'PanelCard\(title = "([^"]+?), cardKey = "([^"]+)"',
        lambda m: f'PanelCard(title = {key_for(m.group(1))}, cardKey = "{m.group(2)}"',
        s,
    )
    s = re.sub(
        r'RelaySection\(title = "([^"]+?), cardKey = "([^"]+)"',
        lambda m: f'RelaySection(title = {key_for(m.group(1))}, cardKey = "{m.group(2)}"',
        s,
    )
    s = re.sub(
        r'title = "([^"]+?), cardKey = L\["([^"]+)"\]',
        lambda m: f'title = {key_for(m.group(1))}, cardKey = "{ZH.get(m.group(2), m.group(2))}"',
        s,
    )
    s = re.sub(
        r'RelaySection\(title = "([^"]+?), cardKey = L\["([^"]+)"\]',
        lambda m: f'RelaySection(title = {key_for(m.group(1))}, cardKey = "{ZH.get(m.group(2), m.group(2))}")',
        s,
    )
    s = re.sub(
        r'PageScaffold\(title = "([^"]+?), desc = "([^"]+)"',
        lambda m: f"PageScaffold(title = {key_for(m.group(1))}, desc = {key_for(m.group(2))}",
        s,
    )
    s = re.sub(
        r'StatTile\(label = "([^"]+?), value = ',
        lambda m: f"StatTile(label = {key_for(m.group(1))}, value = ",
        s,
    )
    s = re.sub(
        r'StatTile\(label = "([^"]+?), value = L\["',
        lambda m: f'StatTile(label = {key_for(m.group(1))}, value = L["',
        s,
    )
    # sub = "text, icon = "icon"
    s = re.sub(
        r', sub = "([^"]+), icon = "([^"]+)"\)',
        lambda m: f', sub = {key_for(m.group(1))}, icon = "{m.group(2)}")',
        s,
    )
    # value = L["key"]field")} -> restore via repair_broken pattern
    s = re.sub(
        r'L\["([^"]+)"\]([a-zA-Z0-9_.]+)"\)',
        lambda m: f'"{ZH.get(m.group(1), "")}{m.group(2)}")',
        s,
    )
    # if (x) "中文 else L["key"] -> if (x) L["a"] else L["b"]
    s = re.sub(
        r'if \(([^)]+)\) "([^"]+) else L\["([^"]+)"\]',
        lambda m: f'if ({m.group(1)}) {key_for(m.group(2))} else L["{m.group(3)}"]',
        s,
    )
    s = re.sub(
        r'== "([^"]+) else L\["([^"]+)"\]',
        lambda m: f'== {key_for(m.group(1))} else L["{m.group(2)}"]',
        s,
    )
    s = re.sub(
        r'PageScaffold\(title = "([^"]+?), desc = (L\["[^"]+"\])',
        lambda m: f"PageScaffold(title = {key_for(m.group(1))}, desc = {m.group(2)}",
        s,
    )
    s = re.sub(
        r'PanelCard\(title = "([^"]+?)\) \{',
        lambda m: f"PanelCard(title = {key_for(m.group(1))}) {{",
        s,
    )
    s = re.sub(
        r'LevelBadge\("([^"]+?), (Level\.\w+)\)',
        lambda m: f"LevelBadge({key_for(m.group(1))}, {m.group(2)})",
        s,
    )
    s = re.sub(
        r'FormField\.Text\("([^"]+)", "([^"]+?), hint = (L\["[^"]+"\])',
        lambda m: f'FormField.Text("{m.group(1)}", {key_for(m.group(2))}, hint = {m.group(3)}',
        s,
    )
    s = re.sub(
        r'FormField\.Text\("([^"]+)", "([^"]+?), value = ',
        lambda m: f'FormField.Text("{m.group(1)}", {key_for(m.group(2))}, value = ',
        s,
    )
    s = re.sub(
        r'\.(int|str|num|long)\(([a-zA-Z_][a-zA-Z0-9_]*)"\)',
        lambda m: f'.{m.group(1)}("{m.group(2)}")',
        s,
    )
    s = re.sub(
        r'label = "([^"]+?), hint = L\["([^"]+)"\]',
        lambda m: f'label = {key_for(m.group(1))}, hint = L["{m.group(2)}"]',
        s,
    )
    s = re.sub(
        r'label = "([^"]+?), value = L\["',
        lambda m: f'label = {key_for(m.group(1))}, value = L["',
        s,
    )
    s = re.sub(
        r'label = "([^"]+?), value = if ',
        lambda m: f'label = {key_for(m.group(1))}, value = if ',
        s,
    )
    s = re.sub(
        r'FormField\.Password\("([^"]+)", "([^"]+?), hint = ',
        lambda m: f'FormField.Password("{m.group(1)}", {key_for(m.group(2))}, hint = ',
        s,
    )
    return s


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        t = path.read_text(encoding="utf-8")
        nt = fix_content(t)
        if nt != t:
            path.write_text(nt, encoding="utf-8")
            n += 1
    print(f"fixed {n} files")


if __name__ == "__main__":
    main()
