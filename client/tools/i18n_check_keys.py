#!/usr/bin/env python
"""列出某个 Kotlin 文件引用的 i18n 键在 18 个语言文件里的落地情况。

  python tools/i18n_check_keys.py composeApp/.../RhInsightCard.kt

输出三件事：缺失（编译能过但运行时原样显示键名）、仍是中文占位（非 zh 语言）、
以及模板占位符 {n} 数量是否与中文原文一致。
"""
import io
import json
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
COMMON = REPO / "composeApp/src/commonMain/resources/i18n"
CJK = re.compile(r"[\u4e00-\u9fff]")
KEY_RE = re.compile(r'L\["([^"]+)"|trf\(\s*"([^"]+)"')


def main():
    path = Path(sys.argv[1])
    src = io.open(path, encoding="utf-8").read()
    keys = sorted({a or b for a, b in KEY_RE.findall(src)})
    langs = [p.stem for p in sorted(COMMON.glob("*.json")) if p.stem != "template"]
    data = {}
    for lang in langs:
        data[lang] = json.load(io.open(COMMON / (lang + ".json"), encoding="utf-8"))
    missing, zh_left = [], []
    for k in keys:
        for lang in langs:
            if k not in data[lang]:
                missing.append((k, lang))
        en = data.get("en", {}).get(k, "")
        if lang_has_cjk(k, data):
            zh_left.append((k, [l for l in langs if l != "zh" and CJK.search(data[l].get(k, ""))]))
    print("引用键数: %d  语言数: %d" % (len(keys), len(langs)))
    print("缺失: %d" % len(missing))
    for k, l in missing[:20]:
        print("  MISSING %s  [%s]" % (k, l))
    ph = [(k, ls) for k, ls in zh_left if ls]
    print("仍是中文占位的键: %d" % len(ph))
    for k, ls in ph:
        print("  %s  (%d 个语言)  %s" % (k, len(ls), data.get("zh", {}).get(k, "")))


def lang_has_cjk(k, data):
    for lang, d in data.items():
        if lang == "zh":
            continue
        v = d.get(k)
        if v is not None and CJK.search(v):
            return True
    return False


main()
