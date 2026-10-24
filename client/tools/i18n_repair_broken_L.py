#!/usr/bin/env python3
"""Fix L[\"key\"]suffix\" where bind_literals broke Kotlin string templates."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ZH = json.loads(
    (ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8")
)
PANEL = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"

# L["key"]rest"  ->  "zh[key]+rest"
BROKEN = re.compile(r'L\["([^"]+)"\]([^"]+)"')


def repair_line(line: str) -> str:
    if "L[\"app.description\"]" in line or "${L[" in line:
        return line

    def repl(m: re.Match) -> str:
        key, rest = m.group(1), m.group(2)
        if key == "app.description" or rest.startswith("]"):
            return m.group(0)
        prefix = ZH.get(key, "")
        if not prefix and not rest:
            return m.group(0)
        # Restore Kotlin string (templates must stay literal, not L[])
        inner = prefix + rest
        escaped = inner.replace("\\", "\\\\").replace('"', '\\"')
        return f'"{escaped}"'

    return BROKEN.sub(repl, line)


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
        out = [repair_line(ln) if 'L["' in ln and BROKEN.search(ln) else ln for ln in lines]
        new = "".join(out)
        if new != "".join(lines):
            path.write_text(new, encoding="utf-8")
            n += 1
    print(f"Repaired {n} files")


if __name__ == "__main__":
    main()
