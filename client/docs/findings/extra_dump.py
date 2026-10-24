#!/usr/bin/env python
"""调研专用：补 dump 面板 GET 端点（api-dump 未覆盖的部分）到 docs/findings/extra-api/。
只读探测 + 一次故意失败的登录探测（错误密码只试 1 次，避免触发 10 分钟锁定）。"""
import json, os, urllib.request, urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASE = "http://8.153.153.176/panel/api"
TOKEN = open(os.path.join(ROOT, "tools", ".token")).read().strip()
OUT = os.path.join(ROOT, "docs", "findings", "extra-api")
os.makedirs(OUT, exist_ok=True)

GETS = [
    "/history?range=1h",
    "/history?range=24h",
    "/metrics/history?range=7d",
    "/metrics/history?range=24h&compare=1",
    "/metrics/trend?days=30",
    "/pkg/detail?name=nginx",
    "/pkg/installed?q=ngi&limit=3&offset=0&sort=name&dir=asc",
    "/pkg/installed?section=net&limit=3",
    "/pkg/search-in-source?q=nginx&limit=3",
    "/pkg/test-mirror?url=http://archive.ubuntu.com/ubuntu",
    "/db/status?engine=mysql",
    "/db/databases?engine=mysql",
    "/db/tables?engine=postgres&database=postgres",
    "/db/tables?engine=mysql&database=mysql",
    "/db/tables?engine=sqlite&database=x",
    "/db/table/schema?engine=postgres&database=postgres&table=pg_class",
    "/db/roles?engine=mysql",
    "/db/insights?engine=postgres",
    "/db/insights?engine=mysql",
    "/db/objects?engine=postgres",
    "/db/statements?engine=postgres",
    "/db/schemas?engine=postgres",
    "/db/table/storage?engine=postgres&database=postgres&table=pg_class",
    "/db/missing-indexes?engine=postgres",
    "/db/extra-monitor?engine=postgres",
    "/db/kv/keys?database=0&pattern=*&limit=10",
    "/db/kv/value?database=0&key=foo",
    "/file?path=/etc/nginx/nginx.conf",
    "/files?dir=/etc/nginx/",
    "/files?dir=/var/www/",
    "/db/backups?engine=postgres",
    "/pkg/job?id=nonexistent",
]

# 本机系统代理(127.0.0.1:7890)会对该站点返回 502，这里显式绕过代理直连
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def req(path, method="GET", body=None, headers=None):
    h = {"Authorization": "Bearer " + TOKEN}
    if headers:
        h.update(headers)
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        h["Content-Type"] = "application/json"
    r = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    try:
        with OPENER.open(r, timeout=60) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, str(e)


def safe_name(p):
    name = p.replace("/", "_").replace("?", "_").replace("&", "_").replace("=", "-")
    for ch in '*<>|:"\\':
        name = name.replace(ch, "-")
    return name.strip("_")

manifest = {}
for p in GETS:
    st, raw = req(p)
    name = safe_name(p)
    try:
        obj = json.loads(raw)
    except Exception:
        obj = raw
    with open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
    manifest[p] = {"status": st, "file": name + ".json", "bytes": len(raw)}
    print(f"{st:>4} {len(raw):>8}  {p}", flush=True)

# 登录失败形态（只试 1 次）
st, raw = req("/login", "POST", {"password": "__definitely_wrong_pw__"})
manifest["POST /login (wrong password)"] = {"status": st, "body": raw}
print(f"{st:>4} {len(raw):>8}  POST /login (wrong)", flush=True)

with open(os.path.join(OUT, "_manifest.json"), "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=1)
print("done ->", OUT)
