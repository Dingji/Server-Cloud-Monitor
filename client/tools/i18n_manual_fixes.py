#!/usr/bin/env python3
"""Manual i18n fixes for the handful of sites the automatic binder left in review,
plus repair of the dangling `今天_b76ce230d3` key reference.

- ensures each value has a key in zh.json (reusing slug scheme)
- rewrites the exact source snippet (line-targeted, literal replacement)
- adds the keys to every locale file (zh copy until translated)
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "composeApp/src/commonMain/resources/i18n"
I18N_DESK = ROOT / "composeApp/src/desktopMain/resources/i18n"
LOCALES = ["zh", "en", "de", "it", "fr", "ru", "es", "pt", "ja", "ko", "vi", "lo", "th", "ms", "hi", "tr", "el", "ar"]

P = "composeApp/src/commonMain/kotlin/com/servermaintance/panel/"
D = "composeApp/src/desktopMain/kotlin/com/servermaintance/panel/"

# (file, line_no, old_substring, new_substring) — exact line-targeted replacement
EDITS: list[tuple[str, int, str, str]] = [
    (P + "pages/NoteMarkdown.kt", 384, '"YouTube 视频"', 'L["{k:YouTube 视频}"]'),
    (P + "pages/NoteMarkdown.kt", 392, '"Bilibili 视频"', 'L["{k:Bilibili 视频}"]'),
    (P + "pages/NoteSettingsPage.kt", 173, '"已配置"', 'L["{k:已配置}"]'),
    (P + "pages/NotesPage.kt", 562, '"未命名笔记"', 'L["{k:未命名笔记}"]'),
    (
        P + "pages/RelayPage.kt",
        91,
        '"中继采集器不可用：${e.message ?: "连接失败"}"',
        'trf("{k:中继采集器不可用：{0}}", e.message ?: tr("{k:连接失败}"))',
    ),
    (
        P + "pages/SecurityPage.kt",
        272,
        '"\\n\\n警告：将关闭密码认证，请确认已配置 SSH 密钥，否则可能无法再登录！"',
        'L["{k:\n\n警告：将关闭密码认证，请确认已配置 SSH 密钥，否则可能无法再登录！}"]',
    ),
    (
        D + "Platform.desktop.kt",
        81,
        '"文本文件 (${extensions.joinToString(" ") { "*.$it" }})"',
        'trf("{k:文本文件 ({0})}", extensions.joinToString(" ") { "*.$it" })',
    ),
    (P + "pages/NoteCommon.kt", 127, '" 〔代码〕 "', 'L["{k: 〔代码〕 }"]'),
    (P + "pages/NoteCommon.kt", 128, '" 〔图片〕 "', 'L["{k: 〔图片〕 }"]'),
    # dangling key: reuse the existing 今天 key
    (P + "pages/NoteCommon.kt", 189, 'L["今天_b76ce230d3"]', 'L["今天_17e83cc25e"]'),
]


def slug_key(text: str) -> str:
    h = hashlib.sha1(text.encode("utf-8")).hexdigest()[:10]
    prefix = re.sub(r"[^\w一-鿿]+", "_", text[:24].strip().lower(), flags=re.UNICODE)
    prefix = re.sub(r"_+", "_", prefix).strip("_") or "s"
    return f"{prefix}_{h}"


def kotlin_escape(s: str) -> str:
    return (
        s.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
    )


def main() -> None:
    zh_path = I18N / "zh.json"
    zh = json.loads(zh_path.read_text(encoding="utf-8"))
    rev = {}
    for k, v in zh.items():
        rev.setdefault(v, k)

    def key_for(value: str) -> str:
        existing = rev.get(value)
        if existing:
            return existing
        key = slug_key(value)
        while key in zh:
            key = key[:-1] + "0"
        zh[key] = value
        rev[value] = key
        return key

    by_file: dict[str, list[tuple[int, str, str]]] = {}
    for rel, line, old, new in EDITS:
        by_file.setdefault(rel, []).append((line, old, new))

    for rel, edits in by_file.items():
        path = ROOT / rel
        lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
        for line_no, old, new in edits:
            idx = line_no - 1
            if idx >= len(lines):
                print(f"!! {rel}:{line_no} out of range")
                continue
            # substitute {k:VALUE} placeholders using real values/keys
            def sub_key(m: re.Match) -> str:
                return key_for(m.group(1))

            new_real = re.sub(r"\{k:(.*?)\}", sub_key, new, flags=re.DOTALL)
            if old not in lines[idx]:
                # allow the dangling-key edit to land on any line
                found = False
                for i, ln in enumerate(lines):
                    if old in ln:
                        lines[i] = ln.replace(old, new_real if "{k:" in new else new_real)
                        found = True
                        print(f"ok(flex) {rel}:{i + 1}  {old[:50]}")
                        break
                if not found:
                    print(f"!! NOT FOUND {rel}:{line_no}  {old[:60]}")
                continue
            lines[idx] = lines[idx].replace(old, new_real)
            print(f"ok {rel}:{line_no}  {old[:50]} -> {new_real[:60]}")
        path.write_text("".join(lines), encoding="utf-8")

    zh_path.write_text(json.dumps(zh, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    for tag in LOCALES:
        if tag == "zh":
            continue
        p = I18N / f"{tag}.json"
        loc = json.loads(p.read_text(encoding="utf-8"))
        added = 0
        for k, v in zh.items():
            if k not in loc:
                loc[k] = v
                added += 1
        if added:
            p.write_text(json.dumps(loc, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"{tag}: +{added}")
    I18N_DESK.mkdir(parents=True, exist_ok=True)
    for f in I18N.glob("*.json"):
        (I18N_DESK / f.name).write_text(f.read_text(encoding="utf-8"), encoding="utf-8")
    print("zh.json keys:", len(zh))


if __name__ == "__main__":
    main()
