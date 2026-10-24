#!/usr/bin/env python3
"""Revert specific L["key"] bindings back to their original Kotlin string literals.

Used for literals that must stay byte-identical because they are matched against
server data / file contents / persisted values (translation would break behavior).
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "composeApp/src/commonMain/resources/i18n"
PANEL = ROOT / "composeApp/src"

# (relative kt path, key, optional line number to restrict the revert to)
REVERTS: list[tuple[str, str, int | None]] = [
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/LogsPage.kt", "error_fail_refused_criti_0f4318e26d", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/db/DbModel.kt", "未安装_未找到_87cb306b77", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/SettingsPage.kt", "nginx_sshd_fstab_b779b9097b", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/SettingsPage.kt", "失败_3e3c8068bb", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/SettingsPage.kt", "通过_dcc4233255", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/PackagesPage.kt", "d_to_remove_removed_63b7577201", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/ops/OpsDiskPage.kt", "gz_xz_bz2_zst_old_d_7aa01dcd34", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/TodosPage.kt", "d_4_d_2_d_2_4ffd25861c", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/NoteMarkdownBlocks.kt", "s_3c39a6dea1", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/NoteMarkdownBlocks.kt", "s_340d18c64d", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/NotesPage.kt", "s_b8c89677ea", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/db/DbTablesTab.kt", "s_77a94f44a0", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/AuditPage.kt", "s_25ef703b14", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/SecurityPage.kt", "s_25ef703b14", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/db/DbAdvancedTab.kt", "w_5f3a1db37c", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/db/DbAdvancedTab.kt", "s_53a0acfad5", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/db/DbConsoleTab.kt", "b_4629ca1b60", None),
    # env labels are persisted server-side (POST "env" to f["env"]) — keep raw values
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/ops/OpsHostsPage.kt", "生产_35d6fec69f", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/ops/OpsHostsPage.kt", "预发_8a98f5b5bd", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/ops/OpsHostsPage.kt", "测试_0b5d7ed54b", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/ops/OpsHostsPage.kt", "开发_eb3774e356", None),
    ("composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/ops/OpsHostsPage.kt", "其它_295c71ff98", 80),
]


def kotlin_escape(s: str) -> str:
    return (
        s.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
    )


def main() -> None:
    zh = json.loads((I18N / "zh.json").read_text(encoding="utf-8"))
    by_file: dict[str, list[tuple[str, int | None]]] = {}
    for rel, key, line in REVERTS:
        by_file.setdefault(rel, []).append((key, line))
    total = 0
    missing = []
    for rel, items in by_file.items():
        path = ROOT / rel
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines(keepends=True)
        for key, line in items:
            if key not in zh:
                missing.append((rel, key))
                continue
            lit = '"' + kotlin_escape(zh[key]) + '"'
            pat = re.compile(r'L\["' + re.escape(key) + r'"\]')
            hits = 0
            for i, ln in enumerate(lines):
                if line is not None and i + 1 != line:
                    continue
                new, n = pat.subn(lit, ln)
                if n:
                    lines[i] = new
                    hits += n
            total += hits
            print(f"{hits}x  {rel.split('/')[-1]}  {key}  ->  {lit[:60]}")
        path.write_text("".join(lines), encoding="utf-8")
    if missing:
        print("MISSING KEYS:", missing)
    print("total reverts:", total)


if __name__ == "__main__":
    main()
