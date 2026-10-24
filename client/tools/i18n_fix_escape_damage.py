#!/usr/bin/env python3
"""Repair literals damaged by i18n_revert_server_strings.py.

That script used re.sub with the literal as a replacement TEMPLATE, so `\\\\`
collapses to `\\` — producing invalid Kotlin escapes like `"\\s+"`. This script
rewrites each damaged literal with a properly escaped one (plain str.replace,
no regex semantics).
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "composeApp/src/commonMain/resources/i18n"

# (relative kt path, key) — every entry whose kotlin_escape() contained backslashes
DAMAGED: list[tuple[str, str]] = [
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/AuditPage.kt", "s_25ef703b14"),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/SecurityPage.kt", "s_25ef703b14"),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/PackagesPage.kt", "d_to_remove_removed_63b7577201"),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/TodosPage.kt", "d_4_d_2_d_2_4ffd25861c"),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/db/DbAdvancedTab.kt", "w_5f3a1db37c"),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/db/DbConsoleTab.kt", "b_4629ca1b60"),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/ops/OpsDiskPage.kt", "gz_xz_bz2_zst_old_d_7aa01dcd34"),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/NoteMarkdownBlocks.kt", "s_3c39a6dea1"),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/NoteMarkdownBlocks.kt", "s_340d18c64d"),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/NotesPage.kt", "s_b8c89677ea"),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/db/DbTablesTab.kt", "s_77a94f44a0"),
]


def kotlin_escape(s: str) -> str:
    return (
        s.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
    )


def damaged_form(v: str) -> str:
    """What re.sub produced: backslashes single, $ escaped, quotes escaped."""
    return v.replace("$", "\\$").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")


def main() -> None:
    zh = json.loads((I18N / "zh.json").read_text(encoding="utf-8"))
    fixed = 0
    for rel, key in DAMAGED:
        v = zh[key]
        path = ROOT / rel
        text = path.read_text(encoding="utf-8")
        bad = '"' + damaged_form(v) + '"'
        good = '"' + kotlin_escape(v) + '"'
        n = text.count(bad)
        if n:
            text = text.replace(bad, good)
            path.write_text(text, encoding="utf-8")
        print(f"{n}x  {rel.split('/')[-1]}  {bad}  ->  {good}")
        fixed += n
    print("total fixed:", fixed)


if __name__ == "__main__":
    main()
