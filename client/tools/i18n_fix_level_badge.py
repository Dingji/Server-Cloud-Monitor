#!/usr/bin/env python3
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PANEL = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"
ZH = json.loads((ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8"))
REV = {v: k for k, v in ZH.items() if v}


def lit(t: str) -> str:
    k = REV.get(t.strip())
    return f'L["{k}"]' if k else f'"{t}"'


def fix(s: str) -> str:
    s = re.sub(
        r'LevelBadge\("([^"]+?), (Level\.\w+)\)',
        lambda m: f'LevelBadge({lit(m.group(1))}, {m.group(2)})',
        s,
    )
    s = re.sub(
        r'(GhostBtn|PrimaryBtn|DangerBtn)\(text = "([^"]+?), icon = "([^"]+)", (enabled = [^,]+, )?onClick = ',
        lambda m: f'{m.group(1)}(text = {lit(m.group(2))}, icon = "{m.group(3)}", {m.group(4) or ""}onClick = ',
        s,
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
