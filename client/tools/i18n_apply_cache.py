#!/usr/bin/env python3
"""Build locale JSON from zh + MyMemory cache (no network)."""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "composeApp/src/commonMain/resources/i18n"
DESKTOP = ROOT / "composeApp/src/desktopMain/resources/i18n"
CACHE = ROOT / "tools/i18n_translate_cache.json"
FRAGMENT = re.compile(r'^(\)|\$\{|\+\s*"|\.(str|int|num|arr|ifEmpty)|\s*\?\.)')


def translatable(text: str) -> bool:
    if not text or len(text) > 900:
        return False
    if FRAGMENT.match(text.strip()):
        return False
    s = text.strip()
    if s.startswith("+") and re.search(r"[\u4e00-\u9fff]", s):
        return False
    if s.startswith((")", "?", ".", ",", "+", "listOf", "FormField")):
        return False
    if "listOf(FormField" in text or "FormField.Text" in text:
        return False
    if s.startswith(")") and "{" not in text:
        return False
    if "ifEmpty" in text and not re.search(r"[\u4e00-\u9fff]", text):
        return False
    if "${" in text and not re.search(r"[\u4e00-\u9fff]", text):
        return False
    if s.endswith(("${", "[", "(", "?: ", ": ", ".str(", ".num(", ".long(", ".int(")):
        return False
    if re.search(r"\$\{[^}]*$", text):
        return False
    return bool(re.search(r"[\u4e00-\u9fff]", text))


LOCALES = {
    "en": "en", "de": "de", "it": "it", "fr": "fr", "ru": "ru", "es": "es", "pt": "pt",
    "ja": "ja", "ko": "ko", "vi": "vi", "lo": "lo", "th": "th", "ms": "ms", "hi": "hi",
    "tr": "tr", "el": "el", "ar": "ar",
}


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def save(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def apply_tag(zh: dict, cache: dict, tag: str, target: str) -> dict[str, str]:
    out: dict[str, str] = {}
    miss = 0
    for k, v in zh.items():
        if not translatable(v):
            out[k] = v
            continue
        ck = f"zh-CN|{target}|{v}"
        if ck in cache:
            out[k] = cache[ck]
        else:
            out[k] = v
            miss += 1
    boot = ROOT / "tools" / f"i18n_bootstrap_{tag}.json"
    if boot.is_file():
        out.update(load(boot))
    print(f"  {tag}: missing cache for {miss} keys")
    return out


def main() -> None:
    tags = sys.argv[1:] if len(sys.argv) > 1 else ["en"]
    zh = load(I18N / "zh.json")
    cache = load(CACHE) if CACHE.is_file() else {}
    DESKTOP.mkdir(parents=True, exist_ok=True)
    for tag in tags:
        target = LOCALES.get(tag)
        if not target:
            print("skip", tag)
            continue
        out = apply_tag(zh, cache, tag, target)
        save(I18N / f"{tag}.json", out)
        save(DESKTOP / f"{tag}.json", out)
        print(f"  wrote {tag}.json")


if __name__ == "__main__":
    main()
