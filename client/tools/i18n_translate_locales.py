#!/usr/bin/env python3
"""Translate i18n/zh.json into all locale files (MyMemory with cache + fallbacks)."""
from __future__ import annotations

import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "composeApp" / "src" / "commonMain" / "resources" / "i18n"
DESKTOP_I18N = ROOT / "composeApp" / "src" / "desktopMain" / "resources" / "i18n"
CACHE = ROOT / "tools" / "i18n_translate_cache.json"

# MyMemory langpair targets (from zh-CN)
LOCALES = {
    "en": "en",
    "de": "de",
    "it": "it",
    "fr": "fr",
    "ru": "ru",
    "es": "es",
    "pt": "pt",
    "ja": "ja",
    "ko": "ko",
    "vi": "vi",
    "lo": "lo",
    "th": "th",
    "ms": "ms",
    "hi": "hi",
    "tr": "tr",
    "el": "el",
    "ar": "ar",
}

FRAGMENT = re.compile(
    r"^(\)|\$\{|\+\s*\"|\.(str|int|num|arr|ifEmpty)|\s*\?\.)"
)
CORRUPT = re.compile(
    r"\)\s*\?:|\.num\(|\.obj\(|Fmt\.|\$\{|\bL\[|listOf\(|FormField|trf\(|Modal\."
)
CJK = re.compile(r"[\u4e00-\u9fff]")


def corrupt_value(v: str) -> bool:
    return bool(v) and bool(CORRUPT.search(v))


def load_json(path: Path) -> dict:
    raw = path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        raw = raw[3:]
    return json.loads(raw.decode("utf-8"))


def save_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def translatable(text: str) -> bool:
    if not text or len(text) > 900:
        return False
    if FRAGMENT.match(text.strip()):
        return False
    s = text.strip()
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


def mymemory_translate(text: str, target: str, cache: dict) -> tuple[str, bool]:
    ck = f"zh-CN|{target}|{text}"
    if ck in cache:
        return cache[ck], True
    q = urllib.parse.quote(text[:480])
    url = f"https://api.mymemory.translated.net/get?q={q}&langpair=zh-CN|{target}"
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=12) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            out = data.get("responseData", {}).get("translatedText") or text
            if out.upper() == "INVALID LANGUAGE PAIR":
                out = text
            cache[ck] = out
            return out, False
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            if attempt == 2:
                print(f"    warn: translate failed ({type(e).__name__}): {text[:40]!r}", flush=True)
            time.sleep(1.0 * (attempt + 1))
    cache[ck] = text
    return text, False


def translate_locale(
    zh: dict[str, str],
    tag: str,
    target: str,
    cache: dict,
    max_fetch: int | None = None,
) -> dict[str, str]:
    unique_vals = {v for v in zh.values() if translatable(v)}
    missing = sorted(
        [v for v in unique_vals if f"zh-CN|{target}|{v}" not in cache],
        key=len,
    )
    if max_fetch is not None and max_fetch > 0:
        missing = missing[:max_fetch]
    print(
        f"  [{tag}] {len(unique_vals) - len(missing)} cached, {len(missing)} to fetch",
        flush=True,
    )
    n = len(missing)
    for i, v in enumerate(missing):
        mymemory_translate(v, target, cache)[0]
        if i % 10 == 0 or i == n - 1:
            print(f"  [{tag}] fetch {i + 1}/{n}", flush=True)
            save_json(CACHE, cache)
        time.sleep(0.15)
    out: dict[str, str] = {}
    for k, v in zh.items():
        if translatable(v):
            ck = f"zh-CN|{target}|{v}"
            out[k] = cache.get(ck, v)
        else:
            out[k] = v
    return out


def mirror_desktop() -> None:
    DESKTOP_I18N.mkdir(parents=True, exist_ok=True)
    for f in I18N.glob("*.json"):
        save_json(DESKTOP_I18N / f.name, load_json(f))


def heal_locale(tag: str, zh: dict[str, str], cache: dict) -> None:
    """Re-translate keys where the locale file is corrupt or still Chinese."""
    target = LOCALES[tag]
    path = I18N / f"{tag}.json"
    data = load_json(path)
    n = 0
    for k, zh_v in zh.items():
        if corrupt_value(zh_v):
            continue
        cur = data.get(k, "")
        if not cur:
            continue
        if corrupt_value(cur) or (tag != "ja" and CJK.search(cur) and translatable(zh_v)):
            ck = f"zh-CN|{target}|{zh_v}"
            if ck not in cache:
                mymemory_translate(zh_v, target, cache)
                time.sleep(0.12)
            data[k] = cache.get(ck, zh_v)
            n += 1
    save_json(path, data)
    save_json(CACHE, cache)
    print(f"  healed {n} keys in {tag}.json")


def main() -> None:
    sys.stdout.reconfigure(line_buffering=True)
    args = sys.argv[1:]
    if args and args[0] == "heal":
        tags = args[1:] if len(args) > 1 else list(LOCALES.keys())
        zh = load_json(I18N / "zh.json")
        cache = load_json(CACHE) if CACHE.is_file() else {}
        for tag in tags:
            if tag == "zh":
                continue
            print(f"Healing {tag}...")
            heal_locale(tag, zh, cache)
        mirror_desktop()
        print("done")
        return
    max_fetch: int | None = None
    if "--max" in args:
        i = args.index("--max")
        max_fetch = int(args[i + 1])
        args = args[:i] + args[i + 2 :]
    tags = args if args else list(LOCALES.keys())
    zh = load_json(I18N / "zh.json")
    cache = load_json(CACHE) if CACHE.is_file() else {}
    for tag in tags:
        if tag == "zh":
            continue
        target = LOCALES.get(tag)
        if not target:
            print(f"skip unknown tag {tag}")
            continue
        print(f"Translating -> {tag} ({target})...")
        out = translate_locale(zh, tag, target, cache, max_fetch=max_fetch)
        boot_path = ROOT / "tools" / f"i18n_bootstrap_{tag}.json"
        if boot_path.is_file():
            out.update(load_json(boot_path))
            print(f"  merged bootstrap {boot_path.name}")
        save_json(I18N / f"{tag}.json", out)
        save_json(CACHE, cache)
        print(f"  wrote {tag}.json")
    mirror_desktop()
    print("done")


if __name__ == "__main__":
    main()
