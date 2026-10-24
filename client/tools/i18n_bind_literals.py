#!/usr/bin/env python3
"""Replace exact UI string literals with L[\"key\"] using zh.json reverse lookup (safe subset)."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ZH = ROOT / "composeApp/src/commonMain/resources/i18n/zh.json"
KOTLIN = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"

# Only replace in obvious UI contexts
LIT = re.compile(r'(?P<q>")(?P<body>(?:\\.|[^"\\])*)(?P=q)')

SKIP_FILES = ("NoteMarkdownParser", "AnsiScreen")

UI_BEFORE = re.compile(
    r"(Text\s*\(|title\s*=\s*|label\s*=\s*|hint\s*=\s*|placeholder\s*=\s*|"
    r"okText\s*=\s*|cancelText\s*=\s*|emptyText\s*=\s*|sub\s*=\s*|"
    r"PanelCard\s*\(|PageScaffold\s*\(|EmptyState\s*\(|LoadingState\s*\(|"
    r"PaletteCmd\s*\(|SectionTitle\s*\(|Modal\.confirm\s*\(|"
    r"GhostBtn\s*\(|PrimaryBtn\s*\(|DangerBtn\s*\(|BlockButton\s*\(|SmallButton\s*\()"
)


def decode(s: str) -> str:
    return s.replace("\\n", "\n").replace("\\\"", '"').replace("\\\\", "\\")


def encode_for_search(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def build_reverse(zh: dict[str, str]) -> dict[str, str]:
    rev: dict[str, list[str]] = {}
    for k, v in zh.items():
        if not v or len(v) > 200:
            continue
        if v.strip().startswith(")") or "ifEmpty" in v and "\u4e00" not in v:
            continue
        rev.setdefault(v, []).append(k)

    out: dict[str, str] = {}
    for v, keys in rev.items():
        stable = [k for k in keys if re.match(r"^[a-z][a-z0-9_.]*$", k)]
        out[v] = stable[0] if stable else keys[0]
    return out


def should_skip_file(path: Path) -> bool:
    rel = path.as_posix()
    return any(x in rel for x in SKIP_FILES)


def process_file(path: Path, rev: dict[str, str]) -> bool:
    text = path.read_text(encoding="utf-8")
    orig = text
    needs_import = False

    def repl(m: re.Match) -> str:
        nonlocal needs_import
        body = m.group("body")
        decoded = decode(body)
        key = rev.get(decoded)
        if not key:
            return m.group(0)
        before = text[max(0, m.start() - 100) : m.start()]
        after = text[m.end() : m.end() + 40]
        if not UI_BEFORE.search(before):
            return m.group(0)
        if "L[\"" in before[-15:]:
            return m.group(0)
        # Never replace inside string templates or merged param tails
        if "${" in before[-80:] or ", cardKey" in after or ", value =" in after or ", right =" in after:
            return m.group(0)
        if re.search(r"\.(int|str|num|long)\(\s*$", before):
            return m.group(0)
        needs_import = True
        return f'L["{key}"]'

    new = LIT.sub(repl, text)
    if new == orig:
        return False
    if needs_import and "import com.servermaintance.panel.i18n.L" not in new:
        pkg = re.search(r"^package .+$", new, re.M)
        if pkg:
            ins = pkg.end()
            new = new[:ins] + "\nimport com.servermaintance.panel.i18n.L" + new[ins:]
    path.write_text(new, encoding="utf-8")
    return True


def main() -> None:
    zh = json.loads(ZH.read_text(encoding="utf-8"))
    rev = build_reverse(zh)
    n = 0
    for p in sorted(KOTLIN.rglob("*.kt")):
        if should_skip_file(p):
            continue
        if process_file(p, rev):
            n += 1
    print(f"Updated {n} files")


if __name__ == "__main__":
    main()
