#!/usr/bin/env python3
"""Report zh.json values that look like corrupted Kotlin fragments (not real copy)."""
import json
import re
from pathlib import Path

ZH = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/resources/i18n/zh.json"
zh = json.loads(ZH.read_text(encoding="utf-8"))
bad = []
for k, v in zh.items():
    if not v:
        continue
    if v.rstrip().endswith(("${", "[", "(", "?: ", ": ")):
        bad.append((k, v[:80]))
    elif re.search(r"\$\{[^}]*$", v):
        bad.append((k, v[:80]))
print(f"suspicious: {len(bad)}")
for k, preview in bad[:40]:
    print(f"  {k}: {preview!r}")
