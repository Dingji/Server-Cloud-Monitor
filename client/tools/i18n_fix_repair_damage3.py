#!/usr/bin/env python3
"""Third pass: fix PanelCard/title merges, if-string breaks, param-in-string."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PANEL = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"
ZH = json.loads(
    (ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8")
)
REV: dict[str, str] = {}
for k, v in ZH.items():
    if v and len(v) < 120:
        REV.setdefault(v, k)


def lit(text: str) -> str:
    k = REV.get(text.strip())
    return f'L["{k}"]' if k else f'"{text}"'


def fix_file(s: str) -> str:
    # PanelCard(title = "X) { Child(
    s = re.sub(
        r'PanelCard\(title = "([^"]+?)\) \{ (\w+)\(',
        lambda m: f'PanelCard(title = {lit(m.group(1))}) {{ {m.group(2)}(',
        s,
    )
    # PageScaffold already mostly fixed; title = "X) { 
    s = re.sub(
        r'PageScaffold\(title = "([^"]+?)\) \{',
        lambda m: f'PageScaffold(title = {lit(m.group(1))}) {{',
        s,
    )
    # RelaySection(title = "X, cardKey = L[" or cardKey = "id"
    s = re.sub(
        r'(RelaySection|PanelCard)\(title = "([^"]+?), cardKey = (L\["[^"]+"\]|"[^"]+")',
        lambda m: f'{m.group(1)}(title = {lit(m.group(2))}, cardKey = {m.group(3)}',
        s,
    )
    s = re.sub(
        r'PanelCard\(title = "([^"]+?), cardKey = "([^"]+)", collapsible = ',
        lambda m: f'PanelCard(title = {lit(m.group(1))}, cardKey = "{m.group(2)}", collapsible = ',
        s,
    )
    # PanelCard(title = "X (${...}), cardKey = "
    s = re.sub(
        r'PanelCard\(title = "([^"]+?) \((\$[^"]+)\), cardKey = "([^"]+)"',
        lambda m: f'PanelCard(title = "{m.group(1)} (${m.group(2)})", cardKey = "{m.group(3)}"',
        s,
    )
    # if (cond) "A else L["B"
    s = re.sub(
        r'"([^"]+?) else L\["([^"]+)"\]',
        lambda m: f'{lit(m.group(1))} else L["{m.group(2)}"]',
        s,
    )
    # "A else L["B"], level" 
    s = re.sub(
        r'if \(([^)]+)\) "([^"]+?) else L\["([^"]+)"\]',
        lambda m: f'if ({m.group(1)}) {lit(m.group(2))} else L["{m.group(3)}"]',
        s,
    )
    # FormField / Modal broken: "label, hint = L["
    s = re.sub(
        r'FormField\.Text\("([^"]+)", "([^"]+?), hint = (L\["[^"]+"\])',
        lambda m: f'FormField.Text("{m.group(1)}", {lit(m.group(2))}, hint = {m.group(3)}',
        s,
    )
    s = re.sub(
        r'FormField\.Text\("([^"]+)", "([^"]+?), value = ',
        lambda m: f'FormField.Text("{m.group(1)}", {lit(m.group(2))}, value = ',
        s,
    )
    s = re.sub(
        r'FormField\.Select\("([^"]+)", "([^"]+?), listOf\(',
        lambda m: f'FormField.Select("{m.group(1)}", {lit(m.group(2))}, listOf(',
        s,
    )
    s = re.sub(
        r'Modal\.confirm\("([^"]+?), (L\["[^"]+"\])',
        lambda m: f'Modal.confirm({lit(m.group(1))}, {m.group(2)}',
        s,
    )
    s = re.sub(
        r'Modal\.confirm\("([^"]+?), text,',
        lambda m: f'Modal.confirm({lit(m.group(1))}, text,',
        s,
    )
    # listOf("A", "B), L[" - missing quote
    s = re.sub(r'listOf\("([^"]+)", "([^"]+?), (L\["[^"]+"\])\)', r'listOf("\1", "\2", \3)', s)
    s = re.sub(r'listOf\("([^"]+)", "([^"]+?), "([^"]+)"', r'listOf("\1", "\2", "\3"', s)
    # emptyText = if (...) "A else L["
    s = re.sub(
        r'emptyText = if \([^)]+\) "([^"]+?) else L\["([^"]+)"\]',
        lambda m: f'emptyText = if (query.isEmpty()) {lit(m.group(1))} else L["{m.group(2)}"]',
        s,
    )
    # StatTile("label, L[" 
    s = re.sub(
        r'StatTile\("([^"]+?), (L\["[^"]+"\]|Fmt\.[^,]+|[^,]+), sub = ',
        lambda m: f'StatTile({lit(m.group(1))}, {m.group(2)}, sub = ',
        s,
    )
    # okText = if (x) "A else L["
    s = re.sub(
        r'okText = if \([^)]+\) "([^"]+?) else L\["([^"]+)"\]',
        lambda m: f'okText = if (isUpdate) {lit(m.group(1))} else L["{m.group(2)}"]',
        s,
    )
    # PrimaryBtn(text = "A (${...})",  already ok; fix "立即备份_... broken
    s = re.sub(
        r'PrimaryBtn\(text = if \([^)]+\) "([^"]+?)_([a-f0-9]{10})"',
        lambda m: f'PrimaryBtn(text = if (busy) L["{m.group(1)}_{m.group(2)}"]',
        s,
    )
    return s


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        orig = path.read_text(encoding="utf-8")
        new = fix_file(orig)
        if new != orig:
            path.write_text(new, encoding="utf-8")
            n += 1
    print(f"Fixed {n} files")


if __name__ == "__main__":
    main()
