#!/usr/bin/env python
"""Print a compact type-shape of each api-dump JSON (field names + value kinds)."""
import json, os, sys

D = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "api-dump")

def shape(v, depth=0, out=None, maxdepth=6):
    if out is None: out = []
    if depth > maxdepth:
        out.append("...")
        return out
    if isinstance(v, dict):
        if not v: out.append("{}")
        for k, val in v.items():
            sub = shape(val, depth+1, [], maxdepth)
            s = "".join(sub)
            if len(s) > 300: s = s[:300] + "…"
            out.append(f"{k}={s}; ")
    elif isinstance(v, list):
        if not v: out.append("[]")
        else:
            out.append("[")
            sub = shape(v[0], depth+1, [], maxdepth)
            s = "".join(sub)
            if len(s) > 300: s = s[:300] + "…"
            out.append(s)
            if len(v) > 1: out.append(f"|x{len(v)}")
            out.append("]")
    elif isinstance(v, bool): out.append("bool")
    elif isinstance(v, int): out.append("num")
    elif isinstance(v, float): out.append("num")
    elif v is None: out.append("null")
    elif isinstance(v, str): out.append("str")
    else: out.append(type(v).__name__)
    return out

names = sys.argv[1:] if len(sys.argv) > 1 else sorted(
    f for f in os.listdir(D) if f.startswith("relay") and f.endswith(".json"))
for n in names:
    p = os.path.join(D, n)
    if not os.path.exists(p): continue
    try:
        data = json.load(open(p, encoding="utf-8"))
    except Exception as e:
        print(f"### {n}: PARSE ERR {e}\n"); continue
    print(f"### {n}  ({os.path.getsize(p)} bytes)")
    print("".join(shape(data, maxdepth=4)))
    print()
