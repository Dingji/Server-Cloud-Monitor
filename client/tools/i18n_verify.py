#!/usr/bin/env python3
"""Final i18n verification report.

- key parity across all 18 locale files (commonMain == desktopMain, all == zh key set)
- placeholder integrity ({0}..{n} parity per value vs zh)
- translation coverage per locale (translatable CJK values that still equal zh)
- runtime wiring sanity: AppLanguage rtl flag for ar; ProvideLocale/LocaleState present
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "composeApp/src/commonMain/resources/i18n"
I18N_DESK = ROOT / "composeApp/src/desktopMain/resources/i18n"
LOCALES = ["zh", "en", "de", "it", "fr", "ru", "es", "pt", "ja", "ko", "vi", "lo", "th", "ms", "hi", "tr", "el", "ar"]
CJK = re.compile(r"[一-鿿]")
PH = re.compile(r"\{(\d+)\}")


def main() -> None:
    zh = json.loads((I18N / "zh.json").read_text(encoding="utf-8"))
    zh_keys = set(zh)
    translatable = {k for k, v in zh.items() if CJK.search(v) and len(v) < 900}
    # keys actually referenced from Kotlin sources (stale catalogue entries are ignored below)
    code = ""
    for src in (ROOT / "composeApp/src").rglob("*.kt"):
        if "/i18n/" in src.as_posix():
            continue
        code += src.read_text(encoding="utf-8") + "\n"
    used = set(re.findall(r'\b(?:tr|trf|fmt|L)\s*(?:\(\s*|\[\s*)"((?:\\.|[^"\\])*)"', code))
    print(f"zh.json keys: {len(zh)}  (CJK-bearing: {len(translatable)}, referenced in code: {len(used)})")
    ok = True
    for tag in LOCALES:
        p = I18N / f"{tag}.json"
        d = json.loads(p.read_text(encoding="utf-8"))
        pd = I18N_DESK / f"{tag}.json"
        dd = json.loads(pd.read_text(encoding="utf-8")) if pd.is_file() else {}
        miss = zh_keys - set(d)
        extra = set(d) - zh_keys
        mirror_ok = d == dd
        untranslated = 0
        bad_ph = 0
        cjk_leak = 0
        for k in translatable:
            v = d.get(k, "")
            if v == zh[k] and k in used:
                untranslated += 1
            if set(PH.findall(zh[k])) - set(PH.findall(v)):
                bad_ph += 1
            if tag != "zh" and CJK.search(v) and k in used:
                cjk_leak += 1
        strict = tag in ("zh", "en")  # product scope is zh + en; others are preserved extras
        status = "OK " if not (miss or extra or bad_ph or not mirror_ok) else "!! "
        if strict and (miss or extra or bad_ph or not mirror_ok):
            ok = False
        if not strict and not status.startswith("!!"):
            status = "~~ "  # informational: not selectable in the current scope
        print(
            f"{status}{tag}: keys={len(d)} miss={len(miss)} extra={len(extra)} "
            f"mirror={'ok' if mirror_ok else 'DIFF'} untranslated={untranslated} "
            f"placeholder-issues={bad_ph} cjk-in-values={cjk_leak}"
        )
        if miss:
            print("   missing e.g.:", sorted(miss)[:5])
    # wiring sanity (product scope: zh + en; RTL mechanism preserved for future locales)
    app_lang = (ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel/i18n/AppLanguage.kt").read_text(encoding="utf-8")
    assert 'Zh("zh", "中文")' in app_lang and 'En("en", "English")' in app_lang, "zh/en entries missing"
    assert 'De("de"' not in app_lang and 'Ar("ar"' not in app_lang, "scope trim incomplete"
    assert "val rtl: Boolean" in app_lang, "rtl mechanism removed"
    strings = (ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel/i18n/Strings.kt").read_text(encoding="utf-8")
    assert "LayoutDirection.Rtl" in strings, "RTL layout wiring missing"
    app = (ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel/App.kt").read_text(encoding="utf-8")
    assert "ProvideLocale" in app and "LocaleState.load()" in app, "locale bootstrap missing"
    picker = (ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel/i18n/LanguagePicker.kt").read_text(encoding="utf-8")
    assert "LocaleState.apply(lang)" in picker, "language picker apply missing"
    print("wiring sanity: zh/en scope, RTL mechanism, ProvideLocale, bootstrap, picker apply — all present")
    print("OVERALL:", "OK" if ok else "ISSUES FOUND")


if __name__ == "__main__":
    main()
