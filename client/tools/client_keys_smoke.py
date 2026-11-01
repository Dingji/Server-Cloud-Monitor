#!/usr/bin/env python
"""Drive the panel's 分发 Key endpoints through the public URL and print a report.

  SM_PANEL_PW=... python tools/client_keys_smoke.py            # read-only view
  SM_PANEL_PW=... python tools/client_keys_smoke.py issue 5     # mint keys for distribution
  SM_PANEL_PW=... python tools/client_keys_smoke.py selftest    # create -> quota -> revoke -> delete

`issue` prints each plaintext key exactly once -- the panel stores it for nginx's
credential list but the API only ever returns it at creation, same as this tool.
The ceiling defaults are a starting point, not a policy: adjust per key in
OpenCode 中继 -> 分发 Key, or with `python3 /opt/relay/clientkeys.py limit <id> ...`.
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("SM_PANEL_URL", "http://8.153.153.176/panel").rstrip("/")
API = BASE + "/api"
PASSWORD = os.environ.get("SM_PANEL_PW", "")          # never default to a live password
TOKEN = None

# hour is a rolling window; week and month are Beijing calendar windows.
DEFAULT_QUOTA = {"hour": 20000000, "week": 200000000, "month": 600000000}
WINDOWS = ("hour", "week", "month")
WIN_LABEL = {"hour": "近1小时", "week": "本周", "month": "本月"}


def http(method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=45) as r:
            return r.status, json.loads(r.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw or "{}")
        except Exception:
            return e.code, {"error": raw[:400]}
    except Exception as e:
        return 0, {"error": repr(e)}


def login():
    global TOKEN
    if not PASSWORD:
        sys.exit("set SM_PANEL_PW -- this tool deliberately has no built-in password")
    st, j = http("POST", "/login", {"password": PASSWORD})
    if st != 200:
        sys.exit("panel login failed: HTTP %s %s" % (st, str(j)[:200]))
    TOKEN = j.get("token") or j.get("tokenValue")
    if not TOKEN:
        sys.exit("login returned no token: %s" % str(j)[:200])


def key_list():
    st, j = http("GET", "/relay/client-keys")
    if st != 200:
        sys.exit("GET /relay/client-keys -> HTTP %s %s" % (st, str(j)[:300]))
    return j


def act(name, payload):
    return http("POST", "/relay/action", {"name": name, "payload": payload})


def show(data):
    print("仓库 %s · 更新 %s" % (data.get("store"), data.get("updated_at")))
    rows = data.get("keys") or []
    print("%-10s %-9s %-22s %s" % ("状态", "id", "key / 用途", "  ".join(WIN_LABEL[w] for w in WINDOWS)))
    for r in rows:
        w = r.get("windows") or {}
        cells = []
        for k in WINDOWS:
            e = w.get(k) or {}
            limit = e.get("limit") or 0
            used = e.get("used") or 0
            cells.append("%s/%s%s" % (format(used, ","), format(limit, ",") if limit else "不限",
                                      " ⛔" if e.get("over") else ""))
        flags = "内部" if r.get("internal") else ""
        print("%-10s %-9s %-22s %s  %s" % (
            "启用" if r.get("enabled") else "停用", r.get("id"),
            "%s %s" % (r.get("masked"), r.get("label") or ""), "  ".join(cells), flags))
    tot = data.get("totals") or {}
    print("合计已用: " + "  ".join("%s %s" % (WIN_LABEL[k], format((tot.get(k) or {}).get("used") or 0, ","))
                                   for k in WINDOWS))


def issue(n):
    made = []
    for i in range(1, n + 1):
        st, j = act("client-keys/create", {
            "label": "分发 %d" % i, "note": "预生成，待分配", "quota": dict(DEFAULT_QUOTA)})
        if st != 200 or not j.get("ok"):
            print("第 %d 枚生成失败: HTTP %s %s" % (i, st, str(j)[:300]))
            continue
        if j.get("applied") is False:
            print("  警告: key 已写入仓库但 nginx 名单没更新 ->", str(j.get("warning"))[:200])
        made.append((j.get("id"), j.get("key")))
        print("  %-9s %s" % (j.get("id"), j.get("detail")))
    if not made:
        return
    print("\n把这些交给使用方（每个客户端只填自己那一枚）：")
    for kid, secret in made:
        print("  %-9s %s" % (kid, secret))
    print("\n客户端用法：把 OPENCODE_RELAY_KEY 设成上面任意一串，"
          "provider 仍指向 http://8.153.153.176/v1")
    print("明文不会再出现在面板或接口里；忘了就重新生成一枚。")


def selftest():
    """Prove the panel path really can mint / cap / revoke / delete, then clean up."""
    for orphan in [k for k in key_list().get("keys") or []
                   if (k.get("label") or "").startswith("__自检")]:
        st, j = act("client-keys/delete", {"id": orphan["id"]})
        print("cleanup -> removed leftover", orphan["id"], st, j.get("ok"))
    st, j = act("client-keys/create", {"label": "__自检__", "note": "跑完即删",
                                       "quota": {"hour": 1, "week": 1, "month": 1}})
    print("create  ->", st, j.get("ok"), j.get("applied"), (j.get("key") or "")[:10] + "…")
    if st != 200:
        return
    kid = j.get("id")
    st, j = act("client-keys/update", {"id": kid, "label": "__自检改名__",
                                       "quota": {"hour": 12345, "week": 0, "month": 0}})
    print("update  ->", st, j.get("ok"), j.get("detail"))
    data = key_list()
    rec = next((k for k in data["keys"] if k["id"] == kid), None)
    print("listed  ->", bool(rec), (rec or {}).get("label"),
          (rec or {}).get("quota"))
    st, j = act("client-keys/enable", {"id": kid, "enabled": False})
    print("disable ->", st, j.get("ok"), j.get("applied"))
    st, j = act("client-keys/enable", {"id": kid, "enabled": True})
    print("enable  ->", st, j.get("ok"), j.get("applied"))
    st, j = act("client-keys/delete", {"id": kid})
    print("delete  ->", st, j.get("ok"), j.get("detail"))
    st, j = act("client-keys/delete", {"id": "ck_master"})
    print("guard   ->", st, str(j.get("detail") or j.get("error"))[:80],
          "(主令牌应拒绝删除)")
    st, j = act("client-keys/create", {"label": "", "quota": {}})
    print("validate->", st, str(j.get("detail") or j.get("error"))[:60], "(空标签应被拒)")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "show"
    login()
    if cmd == "show":
        show(key_list())
    elif cmd == "issue":
        issue(int(sys.argv[2]) if len(sys.argv) > 2 else 5)
        show(key_list())
    elif cmd == "selftest":
        selftest()
        show(key_list())
    else:
        sys.exit("unknown command: %s (show|issue N|selftest)" % cmd)


if __name__ == "__main__":
    main()
