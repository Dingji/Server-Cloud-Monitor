#!/usr/bin/env python
"""只读：把 api-dump/ 与 docs/findings/extra-api/ 里所有 JSON 样本的顶层结构打平成一张摘要表，供写文档核对字段名。"""
import json, os, glob, collections

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def brief(v, depth=0):
    if isinstance(v, dict):
        if depth >= 2:
            return "{" + ",".join(list(v.keys())[:12]) + ("}" if len(v) <= 12 else ",…}")
        return "{" + ", ".join(f"{k}: {brief(x, depth+1)}" for k, x in list(v.items())[:20]) + ("}" if len(v) <= 20 else ", …}")
    if isinstance(v, list):
        if not v:
            return "[]"
        return "[" + (brief(v[0], depth+1) if len(v) == 1 else f"{len(v)} items e.g. {brief(v[0], depth+1)}") + "]"
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, (int, float)):
        return "num"
    if isinstance(v, str):
        return "str(" + str(len(v)) + ")"
    return type(v).__name__


for d in ("api-dump", os.path.join("docs", "findings", "extra-api")):
    base = os.path.join(ROOT, d)
    if not os.path.isdir(base):
        continue
    print("\n########", d)
    for f in sorted(glob.glob(os.path.join(base, "*.json"))):
        name = os.path.basename(f)
        if name.startswith("_"):
            continue
        try:
            with open(f, encoding="utf-8") as fh:
                data = json.load(fh)
        except Exception as e:
            print(f"--- {name}: PARSE FAIL {e}")
            continue
        print(f"--- {name}: {brief(data)}")
