#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""端点覆盖对照（testing 角色）——「一个不能少」的机器化证据之一。

解析 upstream/server/index.js（权威路由表），提取：
  - api.get('/x') / api.post('/x') 直接注册的路由
  - dbG('x')  → GET  /db/x        （数据库管理只读端点工厂）
  - dbR('x')  → POST /db/x        （数据库管理写端点工厂）
  - pkgG('x') → GET  /pkg/x       （软件包只读端点工厂）
  - RELAY_GET 数组 → GET /relay/<name>（OpenCode 中继采集端）
  - hub.js 的 WebSocket 升级：/panel/ws、/panel/term
  - app.get(BASE+p) 的 vendor 静态资源（App 无需复刻，仅列出）

然后与 tests/api_contract.py 实际请求的端点集合比对，输出：
  tests/endpoints.md —— 全部端点清单 + 覆盖状态 + 未覆盖端点列表

用法：python tests/endpoint_inventory.py
"""
import importlib.util
import os
import re
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INDEX_JS = os.path.join(ROOT, "upstream", "server", "index.js")
HUB_JS = os.path.join(ROOT, "upstream", "server", "hub.js")
TESTS_DIR = os.path.join(ROOT, "tests")
API_PREFIX = "/panel/api"  # app.use(BASE + '/api', api)，BASE=/panel（upstream/config.json）


def load_contract_cases():
    """从 tests/api_contract.py 载入用例表（不执行 main）。"""
    p = os.path.join(TESTS_DIR, "api_contract.py")
    spec = importlib.util.spec_from_file_location("api_contract", p)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.CASES


def parse_index_js(text):
    routes = []  # (method, path, how)

    # 1) RELAY_GET 数组
    relay_names = []
    m = re.search(r"const\s+RELAY_GET\s*=\s*\[(.*?)\]", text, re.S)
    if m:
        relay_names = re.findall(r"'([^']+)'|\"([^\"]+)\"", m.group(1))
        relay_names = [a or b for a, b in relay_names]
    for n in relay_names:
        routes.append(("GET", "/relay/" + n, "RELAY_GET"))

    # 2) 直接注册（要求路径字面量后紧跟参数分隔符，从而排除 '/db/' + name 这类工厂模板拼接）
    for method in ("get", "post", "put", "delete"):
        for m in re.finditer(r"\bapi\.%s\(\s*(['\"])([^'\"]+)\1\s*(?:,|\))" % method, text):
            routes.append((method.upper(), m.group(2), "api.%s" % method))

    # 3) 三个路由工厂
    for m in re.finditer(r"\bdbG\(\s*(['\"])([^'\"]+)\1", text):
        routes.append(("GET", "/db/" + m.group(2), "dbG"))
    for m in re.finditer(r"\bdbR\(\s*(['\"])([^'\"]+)\1", text):
        routes.append(("POST", "/db/" + m.group(2), "dbR"))
    for m in re.finditer(r"\bpkgG\(\s*(['\"])([^'\"]+)\1", text):
        routes.append(("GET", "/pkg/" + m.group(2), "pkgG"))

    # 去重（保持顺序）
    seen, uniq = set(), []
    for r in routes:
        if (r[0], r[1]) not in seen:
            seen.add((r[0], r[1]))
            uniq.append(r)
    return uniq, relay_names


def parse_ws_and_static():
    ws = [("WS", "/panel/ws", "hub.js 升级 /\\/ws$/ —— 指标/告警/日志跟随"),
          ("WS", "/panel/term", "hub.js 升级 /\\/term$/ —— Web 终端（会话 id 客户端生成，attach 时创建）")]
    static = [("GET", "/panel/public/**", "静态前端（App 不复刻）"),
              ("GET", "/panel/vendor/chart.js|xterm.js|xterm.css|addon-fit.js", "vendor 静态（App 不复刻）"),
              ("GET", "/panel、/panel/、/panel/index.html、/ → 302 /panel/public/index.html", "重定向")]
    return ws, static


def main():
    text = open(INDEX_JS, encoding="utf-8").read()
    routes, relay_names = parse_index_js(text)
    ws, static = parse_ws_and_static()
    cases = load_contract_cases()

    covered = set()          # (method, path-without-query)
    for c in cases:
        path = c[0].split("?")[0]
        covered.add(("GET", path))
    # 契约脚本额外覆盖的 POST（均无副作用）
    extra_post = [("POST", "/login"), ("POST", "/logout"), ("POST", "/logs/tail")]
    for p in extra_post:
        covered.add(p)

    gets = [r for r in routes if r[0] == "GET"]
    posts = [r for r in routes if r[0] == "POST"]

    uncovered_gets = [r for r in gets if (r[0], r[1]) not in covered]
    covered_posts = [r for r in posts if (r[0], r[1]) in covered]
    uncovered_posts = [r for r in posts if (r[0], r[1]) not in covered]

    # 反向检查：契约用例里是否有路由表中不存在的端点（脚本手误 / 上游删了路由）
    route_set = {(r[0], r[1]) for r in routes}
    stale_cases = []
    for c in cases:
        path = c[0].split("?")[0]
        if ("GET", path) not in route_set:
            stale_cases.append(c[0])

    lines = []
    a = lines.append
    a("# 端点覆盖对照表（upstream/server/index.js ↔ tests/api_contract.py）")
    a("")
    a("> 自动生成：`python tests/endpoint_inventory.py`。这是「一个不能少」的机器化证据之一：")
    a("> 面板后端的**每一个**路由都必须出现在本清单里，并标注 App 复刻与测试覆盖状态。")
    a("")
    a("## 汇总")
    a("")
    a("| 项 | 数量 |")
    a("|---|---|")
    a("| REST 路由总数（index.js 解析） | %d |" % len(routes))
    a("| 其中 GET | %d |" % len(gets))
    a("| 其中 POST | %d |" % len(posts))
    a("| RELAY_GET 采集端数量 | %d |" % len(relay_names))
    a("| 契约测试覆盖的 GET | %d |" % (len(gets) - len(uncovered_gets)))
    a("| **未覆盖 GET（必须补）** | **%d** |" % len(uncovered_gets))
    a("| 契约测试覆盖的 POST（无副作用） | %d |" % len(covered_posts))
    a("| 未覆盖 POST（有副作用，禁止在契约测试中调用） | %d |" % len(uncovered_posts))
    a("| WebSocket 端点 | %d |" % len(ws))
    a("| 契约用例指向不存在路由（应为 0） | %d |" % len(stale_cases))
    a("")
    a("说明：POST 端点全部带副作用（进程/服务/包/数据库变更，且都会写审计日志），"
      "契约测试**故意不调用**；它们的请求体契约以 `upstream/server/index.js` + "
      "`upstream/server/actions.js|dbadmin.js|dbmulti.js|pkgmgr.js` + 各 "
      "`upstream/public/js/pages/*.js` 调用点为准，由 Kotlin 实现侧对照复刻。")
    a("")

    a("## GET 端点清单与覆盖")
    a("")
    a("| # | 方法 | 路径 | 注册方式 | 契约覆盖 | 备注 |")
    a("|---|---|---|---|---|---|")
    for i, (method, path, how) in enumerate(gets, 1):
        cov = "✅ 已覆盖" if (method, path) in covered else "❌ **未覆盖**"
        a("| %d | %s | `%s%s` | %s | %s | |" % (i, method, API_PREFIX, path, how, cov))
    a("")

    if uncovered_gets:
        a("## ❌ 未覆盖 GET 端点（需要在 tests/api_contract.py 补用例）")
        a("")
        for method, path, how in uncovered_gets:
            a("- `%s%s`（%s）" % (API_PREFIX, path, how))
        a("")
    else:
        a("## ✅ GET 覆盖率：100%")
        a("")
        a("index.js 中注册的全部 GET 端点都已被 `tests/api_contract.py` 实际请求验证。")
        a("")

    a("## POST 端点清单（契约测试不调用，列出供 Kotlin 侧对照）")
    a("")
    a("| # | 路径 | 注册方式 | 契约测试触及 |")
    a("|---|---|---|---|")
    for i, (method, path, how) in enumerate(posts, 1):
        a("| %d | `%s%s` | %s | %s |" % (i, API_PREFIX, path, how,
                                         "✅（只读/无副作用）" if (method, path) in covered else "—"))
    a("")

    a("## WebSocket 端点（REST 之外）")
    a("")
    a("| 端点 | 说明 |")
    a("|---|---|")
    for method, path, desc in ws:
        a("| `%s` | %s |" % (path, desc))
    a("")
    a("协议细节：`upstream/server/hub.js`（metrics socket + term socket）、`upstream/server/shell.js`（终端会话）。"
      "注意 `/term` 的会话 id 由**客户端**生成（`[a-zA-Z0-9_-]{8,64}`），attach 成功时服务端惰性创建会话 —— "
      "没有对应的 REST 创建入口。")
    a("")

    a("## 静态/其它路由（App 无需复刻）")
    a("")
    a("| 路由 | 说明 |")
    a("|---|---|")
    for method, path, desc in static:
        a("| %s | %s |" % (path, desc))
    a("")

    if stale_cases:
        a("## ⚠️ 契约用例与路由表不一致")
        a("")
        for p in stale_cases:
            a("- 用例 `%s` 在 index.js 中找不到对应路由" % p)
        a("")

    a("## 如何复现")
    a("")
    a("```powershell")
    a("python tests\\endpoint_inventory.py    # 本清单")
    a("python tests\\api_contract.py          # 实测契约（限速串行）")
    a("```")
    a("")

    out = os.path.join(TESTS_DIR, "endpoints.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print("routes: GET=%d POST=%d relay=%d" % (len(gets), len(posts), len(relay_names)), flush=True)
    print("uncovered GET: %d" % len(uncovered_gets), flush=True)
    for method, path, how in uncovered_gets:
        print("  MISS %s %s (%s)" % (method, path, how), flush=True)
    print("stale cases: %d" % len(stale_cases))
    for p in stale_cases:
        print("  STALE %s" % p, flush=True)
    print("written: %s" % out, flush=True)
    return 0 if not uncovered_gets and not stale_cases else 1


if __name__ == "__main__":
    sys.exit(main())
