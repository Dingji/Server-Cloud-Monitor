#!/usr/bin/env python3
"""Prepare LLM translation jobs for all locales.

Reads zh.json, filters translatable values, and writes chunk files
tools/i18n_jobs/chunk_NNN.tsv with lines:  key <TAB> zh
After agents fill tools/i18n_out/<chunk>_<LANG>.tsv (key <TAB> translated),
i18n_collect_translations.py scatters them into the locale JSON files.

Real newlines inside values are emitted as the two-char token \\n (agents must
keep it verbatim); the collector converts them back.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "composeApp/src/commonMain/resources/i18n"
JOBS = ROOT / "tools/i18n_jobs"
OUT = ROOT / "tools/i18n_out"

CHUNK = 120
CJK = re.compile(r"[一-鿿]")
FRAGMENT = re.compile(
    r"^(\)|\$\{|\+\s*\"|\.(str|int|num|arr|ifEmpty)|\s*\?\.|\s*\.)"
)


def translatable(v: str) -> bool:
    if not v or len(v) > 900:
        return False
    if not CJK.search(v):
        return False
    s = v.strip()
    if FRAGMENT.match(s):
        return False
    if s.startswith((")", "?", ".", ",", "+", "listOf", "FormField", ":" , "}")):
        return False
    if "listOf(FormField" in v or "FormField.Text" in v:
        return False
    if re.search(r"\$\{[^}]*$", v):
        return False
    if v.endswith(("${", "[", "(", "?: ", ": ", ".str(", ".num(")):
        return False
    return True


def main() -> None:
    zh = json.loads((I18N / "zh.json").read_text(encoding="utf-8"))
    items = sorted((k, v) for k, v in zh.items() if translatable(v))
    JOBS.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    for f in JOBS.glob("chunk_*.tsv"):
        f.unlink()
    n = 0
    for i in range(0, len(items), CHUNK):
        part = items[i : i + CHUNK]
        lines = [f"{k}\t{v.replace(chr(10), chr(92) + 'n')}" for k, v in part]
        (JOBS / f"chunk_{i // CHUNK:03d}.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
        n += 1
    print(f"{len(items)} translatable values -> {n} chunks of <= {CHUNK}")


if __name__ == "__main__":
    main()
