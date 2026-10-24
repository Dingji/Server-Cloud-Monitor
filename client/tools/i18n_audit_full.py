#!/usr/bin/env python3
"""Full i18n audit: find user-facing string literals NOT yet bound to L[...]/tr()/trf().

Covers commonMain + androidMain + desktopMain (skips i18n package).
Reports:
  1) unbound literals containing CJK  (Chinese copy still hardcoded)
  2) unbound literals in UI call positions (Text/title/label/toast/...) regardless of language
  3) keys referenced in code but missing from zh.json (broken -> renders raw key)
"""
from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC_SETS = [
    ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel",
    ROOT / "composeApp/src/androidMain/kotlin/com/servermaintance/panel",
    ROOT / "composeApp/src/desktopMain/kotlin/com/servermaintance/panel",
]
ZH_PATH = ROOT / "composeApp/src/commonMain/resources/i18n/zh.json"
I18N_DIR = ROOT / "composeApp/src/commonMain/resources/i18n"

CJK = re.compile(r"[一-鿿]")
LIT = re.compile(r'"((?:\\.|[^"\\])*)"')
# slug keys end in _<10 hex>; structured keys are dotted lowercase
SLUG_KEY = re.compile(r"_[0-9a-f]{10}$")
STRUCT_KEY = re.compile(r"^[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+$")
BOUND_PRE = re.compile(r"(?:\btrf?|\bfmt|\bL|\bresolve|\bformat|\bgetString)\s*(?:\(\s*|\[\s*)$")

# positions that render user-facing copy
UI_POS = re.compile(
    r"(Text\s*\(|title\s*=\s*|label\s*=\s*|hint\s*=\s*|placeholder\s*=\s*|text\s*=\s*|"
    r"okText\s*=\s*|cancelText\s*=\s*|toast\(|EmptyState\s*\(|ErrorState\s*\(|"
    r"LoadingState\s*\(|contentDescription\s*=\s*|sub\s*=\s*|desc\s*=\s*|"
    r"Modal\.(confirm|prompt|alert)\s*\(|SectionTitle\s*\(|PanelCard\s*\(|"
    r"Banner\s*\(|badge\s*=\s*|tooltip\s*=\s*|message\s*=\s*|to=\"|label:\s*)"
)


def line_is_comment(line: str) -> bool:
    s = line.lstrip()
    return s.startswith("//") or s.startswith("*") or s.startswith("/*")


def scan() -> tuple[Counter, list[str], list[str], set[str]]:
    per_file: Counter = Counter()
    cjk_hits: list[str] = []
    ui_hits: list[str] = []
    used_keys: set[str] = set()
    for base in SRC_SETS:
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.kt")):
            if "/i18n/" in path.as_posix():
                continue
            rel = path.relative_to(ROOT).as_posix()
            text = path.read_text(encoding="utf-8")
            for m in re.finditer(r'\b(?:tr|trf|fmt|L)\s*(?:\(\s*|\[\s*)"(?:\\.|[^"\\])*"', text):
                lit = LIT.search(text[m.start():])
                if lit:
                    used_keys.add(lit.group(1))
            for i, line in enumerate(text.splitlines(), 1):
                if line_is_comment(line):
                    continue
                for m in LIT.finditer(line):
                    body = m.group(1)
                    pre = line[: m.start()].rstrip()
                    if BOUND_PRE.search(pre):
                        continue  # literal is the argument of tr/trf/fmt/L/resolve
                    if SLUG_KEY.search(body) or STRUCT_KEY.match(body):
                        continue  # looks like an i18n key used dynamically
                    if not body.strip() or body.strip() in ("—", "→", "✓", "/", "\\n", "\\t", " "):
                        continue
                    if re.fullmatch(r"[a-z][a-z0-9_./:-]*", body):  # keys/routes/ids
                        continue
                    if body.startswith("#") or body.startswith("http"):
                        continue
                    if re.fullmatch(r"[0-9.:%\s]+", body):
                        continue
                    has_cjk = bool(CJK.search(body))
                    if not has_cjk and "${" in body:
                        continue  # template without Chinese text: likely dynamic value
                    if not has_cjk:
                        ui_pos = bool(UI_POS.search(pre)) or bool(UI_POS.search(line))
                        if not (ui_pos and re.search(r"[A-Za-z]{3,}", body) and " " in body and len(body) >= 4):
                            continue
                    per_file[rel] += 1
                    bucket = cjk_hits if has_cjk else ui_hits
                    if len(bucket) < 500:
                        bucket.append(f"{rel}:{i}  {line.strip()[:160]}")
    return per_file, cjk_hits, ui_hits, used_keys


def main() -> None:
    per_file, cjk_hits, ui_hits, used_keys = scan()
    zh = json.loads(ZH_PATH.read_text(encoding="utf-8"))
    zh_keys = set(zh)
    all_locale_keys: dict[str, set[str]] = {}
    for f in sorted(I18N_DIR.glob("*.json")):
        all_locale_keys[f.stem] = set(json.loads(f.read_text(encoding="utf-8")))

    missing = sorted(used_keys - zh_keys)
    keyless_locales = {loc: sorted(zh_keys - ks) for loc, ks in all_locale_keys.items() if zh_keys - ks}

    print(f"== unbound user-facing literals: {sum(per_file.values())} hits in {len(per_file)} files ==")
    for name, n in per_file.most_common(70):
        print(f"  {n:4d}  {name}")

    print(f"\n== CJK unbound examples ({len(cjk_hits)} shown) ==")
    for h in cjk_hits[:80]:
        print("  " + h)

    print(f"\n== English unbound examples ({len(ui_hits)} shown) ==")
    for h in ui_hits[:40]:
        print("  " + h)

    print(f"\n== keys used in code but MISSING from zh.json: {len(missing)} ==")
    for k in missing[:80]:
        print("  " + k)

    print("\n== locales missing keys present in zh.json ==")
    for loc, ks in keyless_locales.items():
        print(f"  {loc}: {len(ks)} missing")

    print(f"\ntotal zh.json keys: {len(zh_keys)}; referenced in code: {len(used_keys & zh_keys)}")


if __name__ == "__main__":
    main()
