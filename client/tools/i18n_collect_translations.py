#!/usr/bin/env python3
"""Collect LLM translation outputs (tools/i18n_out/chunk_NNN_<lang>.tsv) and
scatter them into composeApp i18n/<lang>.json files.

- converts the two-char token \\n back into real newlines
- validates placeholder parity ({0}..{n} must all survive)
- reports per-locale coverage vs the translatable key set
- mirrors desktopMain resources
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "composeApp/src/commonMain/resources/i18n"
I18N_DESK = ROOT / "composeApp/src/desktopMain/resources/i18n"
JOBS = ROOT / "tools/i18n_jobs"
OUT = ROOT / "tools/i18n_out"

LOCALES = ["en", "de", "it", "fr", "ru", "es", "pt", "ja", "ko", "vi", "lo", "th", "ms", "hi", "tr", "el", "ar"]
PLACEHOLDER = re.compile(r"\{(\d+)\}")


def load_jobs() -> dict[str, str]:
    """key -> zh source (for reporting)."""
    out: dict[str, str] = {}
    for f in sorted(JOBS.glob("chunk_*.tsv")):
        for line in f.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            k, v = line.split("\t", 1)
            out[k] = v
    return out


def read_out(lang: str) -> dict[str, str]:
    res: dict[str, str] = {}
    for f in sorted(OUT.glob(f"chunk_*_{lang}.tsv")):
        for line in f.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            k, v = parts[0], parts[1]
            res[k] = v.replace("\\n", "\n")
    return res


def main() -> None:
    zh_jobs = load_jobs()
    total = len(zh_jobs)
    print(f"translatable keys: {total}")
    for lang in LOCALES:
        tr = read_out(lang)
        if not tr:
            print(f"{lang}: NO OUTPUT")
            continue
        path = I18N / f"{lang}.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        ok = bad_ph = copied = 0
        missing = []
        for k, zh in zh_jobs.items():
            v = tr.get(k)
            if v is None:
                missing.append(k)
                continue
            need = set(PLACEHOLDER.findall(zh.replace("\\n", "\n")))
            got = set(PLACEHOLDER.findall(v))
            if not need.issubset(got):
                bad_ph += 1
                v = zh  # fall back to Chinese rather than break the sentence
            if v == zh.replace("\\n", "\n"):
                copied += 1
            data[k] = v
            ok += 1
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(
            f"{lang}: {ok}/{total} translated, {len(missing)} missing, "
            f"{bad_ph} placeholder-fixes, {copied} identical-to-zh"
        )
        if missing[:5]:
            print("   missing e.g.:", missing[:5])
    I18N_DESK.mkdir(parents=True, exist_ok=True)
    for f in I18N.glob("*.json"):
        (I18N_DESK / f.name).write_text(f.read_text(encoding="utf-8"), encoding="utf-8")
    print("desktop mirrored")


CORRUPT = re.compile(
    r"\)\s*\?:|\.num\(|\.obj\(|Fmt\.|\$\{|\bL\[|listOf\(|FormField|trf\(|Modal\."
)
CJK = re.compile(r"[\u4e00-\u9fff]")


def corrupt_value(v: str) -> bool:
    return bool(v) and bool(CORRUPT.search(v))


def sync_all_from_zh() -> None:
    """Ensure every locale has all zh keys; fix corrupt values using de/en chunk output."""
    zh = json.loads((I18N / "zh.json").read_text(encoding="utf-8"))
    ref: dict[str, dict[str, str]] = {}
    for lang in LOCALES:
        ref[lang] = read_out(lang)
    for lang in LOCALES:
        path = I18N / f"{lang}.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        fixed = added = 0
        for k, zh_v in zh.items():
            cur = data.get(k)
            if cur is None:
                data[k] = ref[lang].get(k) or ref["en"].get(k) or zh_v
                added += 1
                continue
            if corrupt_value(cur) and not corrupt_value(zh_v):
                repl = ref[lang].get(k)
                if repl and not corrupt_value(repl):
                    data[k] = repl
                    fixed += 1
                elif lang != "en":
                    repl = ref["en"].get(k)
                    if repl and not corrupt_value(repl):
                        data[k] = repl
                        fixed += 1
                    else:
                        data[k] = zh_v
                        fixed += 1
                else:
                    for alt in ("de", "fr", "ja", "ko"):
                        repl = json.loads((I18N / f"{alt}.json").read_text(encoding="utf-8")).get(k)
                        if repl and not corrupt_value(repl):
                            data[k] = repl
                            fixed += 1
                            break
            elif lang not in ("zh", "ja") and CJK.search(cur) and CJK.search(zh_v) and cur == zh_v:
                repl = ref[lang].get(k)
                if repl and repl != zh_v and not corrupt_value(repl):
                    data[k] = repl
                    fixed += 1
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"{lang}: added {added}, fixed {fixed}, total {len(data)}")
    I18N_DESK.mkdir(parents=True, exist_ok=True)
    for f in I18N.glob("*.json"):
        (I18N_DESK / f.name).write_text(f.read_text(encoding="utf-8"), encoding="utf-8")
    print("desktop mirrored")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "sync-all":
        sync_all_from_zh()
    else:
        main()
