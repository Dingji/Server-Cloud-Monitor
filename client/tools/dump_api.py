#!/usr/bin/env python
"""Dump every panel GET endpoint response to ./api-dump for schema study."""
import json, os, urllib.request, urllib.error

BASE = "http://8.153.153.176/panel/api"
TOKEN = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".token")).read().strip()
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "api-dump")
os.makedirs(OUT, exist_ok=True)

def get(path):
    req = urllib.request.Request(BASE + path, headers={"Authorization": "Bearer " + TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=45) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:400]
    except Exception as e:
        return -1, str(e)

RELAY = ['summary','keys','key','quota','providers','daily','hourly','weekday','models',
         'models-daily','tokens','model-status','health','selfheal','latency','ttfb',
         'streaming','sizes','status','routes','methods','endpoints','failover','errors',
         'recent','sessions','session-memory','useragents','ips']

GETS = [
    "/health", "/me", "/metrics", "/overview", "/processes", "/services", "/disks",
    "/memory", "/network", "/security", "/databases", "/web", "/docker", "/cron",
    "/history?range=10m", "/alerts", "/backups", "/logs/sources", "/config",
    "/file?path=/etc/hostname", "/files?dir=/etc/",
    # db admin
    "/db/status?engine=postgres", "/db/databases?engine=postgres",
    "/db/roles?engine=postgres", "/db/backups", "/db/engines",
    "/db/kv/config",
    # metrics store
    "/metrics/history?range=24h", "/metrics/store", "/metrics/trend?days=7",
    # relay ops
    "/relayops/ratelimit", "/relayops/slo?range=24h", "/relayops/hitrate?range=24h",
    "/relayops/tokenhit?range=24h", "/relayops/usage?range=24h",
    "/relayops/slo-prev?range=24h", "/relayops/slo-budget",
    "/relayops/correlate?hours=24&window=30", "/relayops/audit?range=24h",
    "/relayops/health",
    # packages
    "/pkg/summary", "/pkg/search?q=nginx&limit=5", "/pkg/installed?limit=5",
    "/pkg/upgradable", "/pkg/autoremove", "/pkg/recommended", "/pkg/sources",
    "/pkg/source-backups", "/pkg/job",
    "/relay/available",
] + ["/relay/" + n for n in RELAY]

manifest = {}
for p in GETS:
    st, data = get(p)
    name = p.replace("/", "_").replace("?", "_").replace("&", "_").replace("=", "-").strip("_")
    with open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    size = len(json.dumps(data, ensure_ascii=False)) if not isinstance(data, str) else len(data)
    manifest[p] = {"status": st, "bytes": size, "file": name + ".json"}
    print(f"{st:>4}  {size:>8}  {p}", flush=True)

with open(os.path.join(OUT, "_manifest.json"), "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=1)
print("done")
