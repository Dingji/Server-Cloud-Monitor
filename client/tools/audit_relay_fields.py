#!/usr/bin/env python
"""Audit: every field key the relay pages read must exist in the real api-dump."""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAGE = os.path.join(ROOT, "composeApp", "src", "commonMain", "kotlin",
                    "com", "servermaintance", "panel", "pages", "relay")
DUMP = os.path.join(ROOT, "api-dump")

# tab file -> dump files whose schema it reads
MAP = {
    "RelayOverview.kt":  ["relay_summary", "relay_health", "relay_selfheal", "relay_quota",
                          "relay_providers", "relay_hourly", "relay_daily"],
    "RelayTraffic.kt":   ["relay_summary", "relay_hourly", "relay_daily", "relay_models",
                          "relay_models-daily", "relay_status", "relay_routes",
                          "relay_endpoints", "relay_methods"],
    "RelayQuality.kt":   ["relay_summary", "relay_latency", "relay_ttfb", "relay_streaming",
                          "relay_sizes", "relay_failover", "relay_errors",
                          "relay_model-status", "relay_weekday"],
    "RelayClients.kt":   ["relay_sessions", "relay_session-memory", "relay_useragents",
                          "relay_recent", "relay_summary", "relay_ips"],
    "RelayTokens.kt":    ["relay_tokens", "relay_models"],
    "RelayHitrate.kt":   ["relayops_hitrate_range-24h"],
    "RelayTokenHit.kt":  ["relayops_tokenhit_range-24h"],
    "RelayKeys.kt":      ["relay_providers", "relay_keys", "relay_key"],
    "RelayCharts.kt":    ["relay_weekday"],
}

# keys that are synthetic / produced by our own code or are dynamic names
SYNTHETIC = {"__name", "__st", "__at", "x"}

ACCESSORS = re.compile(
    r'\.num\(\s*"([^"]+)"|\.str\(\s*"([^"]+)"|\.bool\(\s*"([^"]+)"|'
    r'\.long\(\s*"([^"]+)"|\.int\(\s*"([^"]+)"|\.obj\(\s*"([^"]+)"|'
    r'\.arr\(\s*"([^"]+)"|RCol\(\s*"([^"]+)"|nestedNum\(\s*"([^"]+)"'
)

def walk_keys(v, out):
    if isinstance(v, dict):
        for k, val in v.items():
            out.add(k)
            walk_keys(val, out)
    elif isinstance(v, list):
        for item in v[:5]:
            walk_keys(item, out)

def load(dumpname):
    p = os.path.join(DUMP, dumpname + ".json")
    if not os.path.exists(p):
        return set(), None
    try:
        data = json.load(open(p, encoding="utf-8"))
    except Exception:
        return set(), "unparseable"
    keys = set()
    walk_keys(data, keys)
    return keys, None

problems = 0
for fname, dumps in sorted(MAP.items()):
    path = os.path.join(PAGE, fname)
    if not os.path.exists(path):
        print(f"[MISSING FILE] {fname}"); problems += 1; continue
    src = open(path, encoding="utf-8").read()
    used = set()
    for m in ACCESSORS.finditer(src):
        g = next(g for g in m.groups() if g)
        if g not in SYNTHETIC:
            used.add(g)
    avail = set()
    errs = []
    for d in dumps:
        k, err = load(d)
        avail |= k
        if err:
            errs.append(d)
    missing = sorted(used - avail)
    tag = f"{fname} (vs {len(dumps)} dumps)"
    if errs:
        print(f"  ! dumps unreadable: {errs}")
    if missing:
        problems += len(missing)
        print(f"[{len(missing):>2} UNKNOWN] {tag}")
        print(f"       {', '.join(missing)}")
    else:
        print(f"[   OK ] {tag}  — {len(used)} field keys all verified")

print(f"\n{'ALL FIELDS VERIFIED' if problems == 0 else str(problems) + ' UNKNOWN FIELDS'}")
sys.exit(0)
