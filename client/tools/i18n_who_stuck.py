#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("t", ROOT / "tools/i18n_translate_locales.py")
t = importlib.util.module_from_spec(spec)
spec.loader.exec_module(t)
zh = json.loads((ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8"))
vals = sorted({v for v in zh.values() if t.translatable(v)}, key=len)
for i in range(345, min(360, len(vals))):
    v = vals[i]
    ck = f"zh-CN|en|{v}"
    cache = json.loads((ROOT / "tools/i18n_translate_cache.json").read_text(encoding="utf-8"))
    hit = ck in cache
    print(i, "cached" if hit else "MISSING", repr(v[:120]))
