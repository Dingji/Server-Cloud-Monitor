#!/usr/bin/env python3
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


def fix(s: str) -> str:
    s = re.sub(
        r'label = "([^"]+?), value = (L\["[^"]+"\]),',
        lambda m: f'label = {lit(m.group(1))}, value = {m.group(2)},',
        s,
    )
    s = re.sub(
        r'label = "([^"]+?), value = (if \([^)]+\)[^,]*),',
        lambda m: f'label = {lit(m.group(1))}, value = {m.group(2)},',
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
