#!/usr/bin/env python3
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
zh = json.loads((ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8"))
FRAGMENT = re.compile(r'^(\)|\$\{|\+\s*"|\.(str|int|num|arr|ifEmpty)|\s*\?\.)')


def translatable(text: str) -> bool:
    if not text or len(text) > 900:
        return False
    if FRAGMENT.match(text.strip()):
        return False
    if text.strip().startswith(")") and "{" not in text:
        return False
    return bool(re.search(r"[\u4e00-\u9fff]", text))


vals = [v for v in zh.values() if translatable(v)]
print("unique translatable:", len(set(vals)))
print("total keys translatable:", len(vals))
