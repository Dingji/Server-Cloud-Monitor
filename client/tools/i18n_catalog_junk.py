#!/usr/bin/env python3
"""Classify junk zh.json values (Kotlin extraction damage) and Kotlin references."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ZH_PATH = ROOT / "composeApp/src/commonMain/resources/i18n/zh.json"
PANEL = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"


def is_junk(v: str) -> bool:
    if not v:
        return True
    s = v.strip()
    if s.startswith("+") and re.search(r"[\u4e00-\u9fff]", s):
        return False
    if s.startswith((")", "?", ".", ",", "+", "listOf", "FormField")):
        return True
    if "listOf(FormField" in v or "FormField.Text" in v:
        return True
    if "${" in v and not re.search(r"[\u4e00-\u9fff]", v):
        return True
    if s.endswith(("${", "[", "(", "?: ", ": ", ".str(", ".num(", ".long(", ".int(")):
        return True
    if re.search(r"\$\{[^}]*$", v):
        return True
    return False


def main() -> None:
    zh = json.loads(ZH_PATH.read_text(encoding="utf-8"))
    junk = {k for k, v in zh.items() if is_junk(v)}
    used: set[str] = set()
    for path in PANEL.rglob("*.kt"):
        if "/i18n/" in path.as_posix():
            continue
        t = path.read_text(encoding="utf-8")
        for m in re.finditer(r'L\["([^"]+)"\]', t):
            used.add(m.group(1))
        for m in re.finditer(r'tr\("([^"]+)"\)', t):
            used.add(m.group(1))
        for m in re.finditer(r'L\.fmt\(\s*"([^"]+)"', t):
            used.add(m.group(1))
    bad_used = sorted(k for k in used if k in junk)
    print(f"junk keys in zh.json: {len(junk)}")
    print(f"junk keys referenced from Kotlin: {len(bad_used)}")
    for k in bad_used[:30]:
        print(f"  {k}: {zh[k][:70]!r}")


if __name__ == "__main__":
    main()
