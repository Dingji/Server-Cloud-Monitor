#!/usr/bin/env python3
"""Report likely user-facing Chinese string literals not using L[...] or tr()."""
import re
from collections import Counter
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"
CJK = re.compile(r"[\u4e00-\u9fff]")
SKIP = re.compile(r"^\s*(//|\*|import |package )")
LIT = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')

hits: Counter[str] = Counter()
examples: list[tuple[str, int, str]] = []

for path in sorted(PANEL.rglob("*.kt")):
    if "i18n/" in path.as_posix():
        continue
    for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if SKIP.match(line) or "L[\"" in line or "tr(" in line or "trf(" in line:
            continue
        if not CJK.search(line):
            continue
        if "zh.json" in line or "i18n" in line:
            continue
        for m in LIT.finditer(line):
            body = m.group(1)
            if CJK.search(body) and "${" not in body and len(body) < 120:
                hits[path.name] += 1
                if len(examples) < 40:
                    examples.append((path.name, i, line.strip()[:140]))

print("files with CJK literals (no L/tr on line):", len(hits))
for name, n in hits.most_common(25):
    print(f"  {n:4d}  {name}")
print("\nexamples:")
for name, ln, text in examples[:20]:
    print(f"  {name}:{ln}  {text}")
