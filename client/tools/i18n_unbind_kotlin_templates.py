#!/usr/bin/env python3
"""Replace L[\"key\"] with inline Kotlin string templates when zh.json value is a pure ${expr} (not translatable copy)."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PANEL = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"
ZH = json.loads((ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8"))

PURE: dict[str, str] = {}
for key, val in ZH.items():
    m = re.fullmatch(r"\$\{([^}]+)\}", val.strip())
    if m:
        PURE[key] = m.group(1)

LIT = re.compile(r'L\["(' + "|".join(re.escape(k) for k in PURE) + r')"\]')


def main() -> None:
    n_files = n_repl = 0
    for path in PANEL.rglob("*.kt"):
        if "/i18n/" in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8")

        def repl(m: re.Match[str]) -> str:
            nonlocal n_repl
            expr = PURE[m.group(1)]
            n_repl += 1
            return f'"${{{expr}}}"'

        new = LIT.sub(repl, text)
        if new != text:
            path.write_text(new, encoding="utf-8")
            n_files += 1
    print(f"files={n_files} replacements={n_repl} pure_keys={len(PURE)}")


if __name__ == "__main__":
    main()
