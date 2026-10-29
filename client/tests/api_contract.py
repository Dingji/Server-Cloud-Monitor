#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Cloud Monitor 面板 API 契约测试（testing 角色）。

对 upstream/server/index.js 里登记的**全部 GET 端点**发起真实请求，校验：
  1. HTTP 状态码是否符合预期（预期值来自 api-dump/_manifest.json 的真实记录 + 源码推导）；
  2. 顶层结构是否符合 index.js 的返回约定（对象 / 数组 / 错误 {error[,output]}）；
  3. 关键字段存在且类型正确 —— 以 api-dump/*.json 为基准做逐键类型比对，
     另外对少数核心端点加了硬编码关键字段断言；
  4. 负向契约：无 token → 401、错密码 → 401、未知路由 → 404、logout 后 token 失效。

产出：
  tests/report-api.md    人读表格报告（端点 / 状态 / 字段数 / 结论）
  tests/report-api.json  机读结果（供 parity / 回归脚本使用）

安全约束：
  - 只发 GET（外加 login / logout / logs/tail 三个只读或无副作用的 POST）；
  - 串行 + 限速 sleep，429 时退避重试（面板限流 1800 次/分钟/IP）；
  - 使用独立登录令牌，不改动 tools/.token（避免影响其它角色）。

用法：
  python tests/api_contract.py [--only <子串过滤>] [--base-url http://host/panel] [--no-report]

注意：本机若配置了系统代理（Windows 注册表，如 127.0.0.1:7890），urllib 会走代理，
而该代理对纯 HTTP 的目标会返回空 502。脚本强制直连（ProxyHandler({})）。
"""
import json
import os
import sys
import time
import urllib.request
import urllib.error
import urllib.parse

# ---------------------------------------------------------------- 基本配置
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DUMP_DIR = os.path.join(ROOT, "api-dump")
TESTS_DIR = os.path.join(ROOT, "tests")
BASE_URL = os.environ.get("SM_PANEL_URL", "http://8.153.153.176/panel")
API = BASE_URL.rstrip("/") + "/api"
PASSWORD = os.environ["SM_PANEL_PASSWORD"]
TOKEN_FILE = os.path.join(ROOT, "tools", ".token")

REQ_TIMEOUT = 60          # 单请求超时（部分收集器较慢）
SLEEP_SEC = 0.25          # 串行限速
MAX_429_RETRIES = 3       # 429 退避重试次数

# 强制直连，绕开系统/注册表代理（本机 Clash 等会对裸 HTTP 返回空 502）
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


# ---------------------------------------------------------------- 用例表
# 每条：(路径含查询串, 可接受状态码集合, 基线 dump 文件名或 None, 关键字段断言, 备注)
# 关键字段断言格式：{"点路径": "类型"}，类型 ∈ bool/num/str/array/object
CHECKS_HEALTH = {"ok": "bool", "name": "str", "version": "str", "ts": "num"}
CHECKS_ME = {"ok": "bool", "info": "object", "env": "object", "refreshMs": "num",
             "netLimitBps": "num", "uptimeStr": "str", "panelPid": "num",
             "info.hostname": "str", "info.cores": "num", "env.distro": "str"}
CHECKS_METRICS = {"ts": "num", "cpu": "object", "memory": "object", "net": "object", "diskio": "object"}
CHECKS_OVERVIEW = {"metrics": "object", "disks": "object", "info": "object",
                   "topProcesses": "array", "temps": "array"}
CHECKS_PROCESSES = {"total": "num", "statCounts": "object", "list": "array"}
CHECKS_SERVICES = {"ts": "num", "list": "array", "counts": "object"}
CHECKS_DISKS = {"ts": "num", "mounts": "array", "blocks": "array", "overallPct": "num"}
CHECKS_MEMORY = {"metrics": "object", "swappiness": "num", "topRss": "array", "procTotal": "num"}
CHECKS_NETWORK = {"ts": "num", "interfaces": "array", "defaultRoute": "object",
                  "listening": "array", "established": "array"}
CHECKS_SECURITY = {"ufw": "object", "fail2ban": "object", "sshd": "object", "users": "array",
                   "sudoers": "array", "recentFailed": "array", "recentLogins": "array"}
CHECKS_DATABASES = {"ts": "num", "engines": "array", "bins": "object"}
CHECKS_WEB = {"ts": "num", "nginx": "object", "apache": "object", "certs": "array",
              "certbotInstalled": "bool"}
CHECKS_DOCKER = {"ts": "num", "installed": "bool"}
CHECKS_CRON = {"ts": "num", "rootJobs": "array", "etcJobs": "array", "cronFiles": "array", "timers": "array"}
CHECKS_HISTORY = {"range": "str", "points": "array"}
CHECKS_ALERTS = {"alerts": "array", "config": "object", "config.enabled": "bool",
                 "config.cpu": "num", "config.mem": "num", "config.disk": "num"}
CHECKS_BACKUPS = {"dir": "str", "items": "array", "relay": "object"}
CHECKS_LOGS_SRC = {"files": "array", "special": "array"}
CHECKS_CONFIG = {"port": "num", "bind": "str", "basePath": "str", "refreshMs": "num",
                 "tokenTtlHours": "num", "ssh": "object", "db": "object"}
CHECKS_FILE = {"content": "str", "size": "num", "mode": "str", "owner": "str", "mtime": "str"}
CHECKS_FILES = {"dir": "str", "entries": "array"}
CHECKS_ENGINES = {"engines": "array"}
CHECKS_KVCFG = {"ok": "bool", "rows": "array"}
CHECKS_DBBACKUPS = {"dir": "str", "items": "array"}
CHECKS_MSTORE = {"ok": "bool", "rows": "num", "sizeBytes": "num"}
CHECKS_MTREND = {"cpu": "object", "mem": "object", "disk": "object"}
CHECKS_PKG_SUMMARY = {"installed_total": "num", "upgradable": "num", "hold_list": "array"}
CHECKS_RELAY_AVAIL = {"ok": "bool", "data": "object", "host": "str", "port": "num"}

OK200 = {200}
# 错误契约：路由存在，但参数/引擎不可用 —— 必须是 JSON {error,...}，绝不能是 Express 默认 HTML 404
ERR_JSON = {200, 400, 404, 500}

CASES = [
    # ---- 面板基础（/health 在认证中间件之前，公开） ------------------------
    ("/health", OK200, "health.json", CHECKS_HEALTH, "公开端点，无需 token"),
    ("/me", OK200, "me.json", CHECKS_ME, "系统信息 + env + refreshMs"),
    ("/metrics", OK200, "metrics.json", CHECKS_METRICS, "单点实时指标"),
    ("/overview", OK200, "overview.json", CHECKS_OVERVIEW, "仪表盘快照"),
    ("/processes", OK200, "processes.json", CHECKS_PROCESSES, "进程列表(force, limit 500→300)"),
    ("/services", OK200, "services.json", CHECKS_SERVICES, "systemd 服务"),
    ("/disks", OK200, "disks.json", CHECKS_DISKS, "磁盘/挂载"),
    ("/memory", OK200, "memory.json", CHECKS_MEMORY, "内存详情"),
    ("/network", OK200, "network.json", CHECKS_NETWORK, "网络详情"),
    ("/security", OK200, "security.json", CHECKS_SECURITY, "安全态势"),
    ("/databases", OK200, "databases.json", CHECKS_DATABASES, "数据库引擎探测"),
    ("/web", OK200, "web.json", CHECKS_WEB, "Nginx/Apache/证书"),
    ("/docker", OK200, "docker.json", CHECKS_DOCKER, "Docker 探测"),
    ("/cron", OK200, "cron.json", CHECKS_CRON, "定时任务"),
    ("/history?range=10m", OK200, "history_range-10m.json", CHECKS_HISTORY, "WS 历史的 REST 等价"),
    ("/alerts", OK200, "alerts.json", CHECKS_ALERTS, "告警列表+阈值配置"),
    ("/backups", OK200, "backups.json", CHECKS_BACKUPS, "备份中心列表"),
    ("/logs/sources", OK200, "logs_sources.json", CHECKS_LOGS_SRC, "日志源清单"),
    ("/config", OK200, "config.json", CHECKS_CONFIG, "面板设置（脱敏）"),
    ("/file?path=/etc/hostname", OK200, "file_path-_etc_hostname.json", CHECKS_FILE, "读文件"),
    ("/files?dir=/etc/", OK200, "files_dir-_etc.json", CHECKS_FILES, "列目录"),

    # ---- 数据库管理（postgres 引擎当前不可用 → 记录错误契约） --------------
    ("/db/status?engine=postgres", {400}, "db_status_engine-postgres.json", None,
     "psql 连接失败 → {error,output}，错误契约"),
    ("/db/databases?engine=postgres", {400}, "db_databases_engine-postgres.json", None, "同上"),
    ("/db/roles?engine=postgres", {400}, "db_roles_engine-postgres.json", None, "同上"),
    ("/db/backups", OK200, "db_backups.json", CHECKS_DBBACKUPS, "DB 备份列表"),
    ("/db/engines", OK200, "db_engines.json", CHECKS_ENGINES, "SQLite/Db2/Redis/Mongo"),
    ("/db/kv/config", OK200, "db_kv_config.json", CHECKS_KVCFG, "Redis 配置"),
    # 无 dump 基准的 dbG 端点：验证「路由存在 + JSON 响应」
    ("/db/tables?engine=postgres&database=postgres", ERR_JSON, None, None, "无基准：验证路由存在"),
    ("/db/table/schema?engine=postgres&database=postgres&table=x", ERR_JSON, None, None, "无基准"),
    ("/db/insights?engine=postgres", ERR_JSON, None, None, "无基准"),
    ("/db/objects?engine=postgres", ERR_JSON, None, None, "无基准"),
    ("/db/statements?engine=postgres", ERR_JSON, None, None, "无基准"),
    ("/db/schemas?engine=postgres", ERR_JSON, None, None, "无基准"),
    ("/db/table/storage?engine=postgres&database=postgres&table=x", ERR_JSON, None, None, "无基准"),
    ("/db/missing-indexes?engine=postgres", ERR_JSON, None, None, "无基准"),
    ("/db/extra-monitor?engine=postgres", ERR_JSON, None, None, "无基准"),
    ("/db/kv/keys?database=0", ERR_JSON, None, None, "Redis 未激活 → 预期错误契约"),
    ("/db/kv/value?database=0&key=x", ERR_JSON, None, None, "同上"),
    ("/db/backup/download", {400}, None, None, "缺 name → 400 {error:非法文件名}"),

    # ---- 指标仓库 --------------------------------------------------------
    ("/metrics/history?range=24h", OK200, "metrics_history_range-24h.json", None, "长周期指标"),
    ("/metrics/store", OK200, "metrics_store.json", CHECKS_MSTORE, "指标仓库统计"),
    ("/metrics/trend?days=7", OK200, "metrics_trend_days-7.json", CHECKS_MTREND, "7 日趋势"),

    # ---- relay 运维（relayops） -------------------------------------------
    ("/relayops/ratelimit", OK200, "relayops_ratelimit.json", None, "限流视图"),
    ("/relayops/slo?range=24h", OK200, "relayops_slo_range-24h.json", None, "SLO 统计"),
    ("/relayops/hitrate?range=24h", OK200, "relayops_hitrate_range-24h.json", None, "套餐命中率"),
    ("/relayops/tokenhit?range=24h", OK200, "relayops_tokenhit_range-24h.json", None, "Token 命中率"),
    ("/relayops/usage?range=24h", OK200, "relayops_usage_range-24h.json", None, "用量成本"),
    ("/relayops/slo-prev?range=24h", OK200, "relayops_slo-prev_range-24h.json", None, "上期 SLO"),
    ("/relayops/slo-budget", OK200, "relayops_slo-budget.json", None, "SLO 预算"),
    ("/relayops/correlate?hours=24&window=30", OK200, "relayops_correlate_hours-24_window-30.json",
     None, "错误×变更关联"),
    ("/relayops/audit?range=24h", OK200, "relayops_audit_range-24h.json", None, "auditd 审计"),
    ("/relayops/health", OK200, "relayops_health.json", None, "中继健康告警"),
    ("/relayops/backup/download", {400}, None, None, "缺 name → 400 {error}"),

    # ---- 软件包管理 ------------------------------------------------------
    ("/pkg/summary", OK200, "pkg_summary.json", CHECKS_PKG_SUMMARY, "apt 概览"),
    ("/pkg/search?q=nginx&limit=5", OK200, "pkg_search_q-nginx_limit-5.json", None, "搜索"),
    ("/pkg/installed?limit=5", OK200, "pkg_installed_limit-5.json", None, "已装列表"),
    ("/pkg/upgradable", OK200, "pkg_upgradable.json", None, "可升级"),
    ("/pkg/autoremove", OK200, "pkg_autoremove.json", None, "可 autoremove"),
    ("/pkg/recommended", OK200, "pkg_recommended.json", None, "推荐包"),
    ("/pkg/sources", OK200, "pkg_sources.json", None, "源列表"),
    ("/pkg/source-backups", OK200, "pkg_source-backups.json", None, "源备份"),
    ("/pkg/job", OK200, "pkg_job.json", None, "最近变更任务（无任务时 null）"),
    ("/pkg/detail?name=nginx", ERR_JSON, None, None, "无基准：验证路由存在"),
    ("/pkg/search-in-source?q=nginx", ERR_JSON, None, None, "无基准"),
    ("/pkg/test-mirror", ERR_JSON, None, None, "无基准：缺 url → 快速错误"),

    # ---- 备份下载（负参数） ----------------------------------------------
    ("/backups/download", {400}, None, None, "缺 name → 400 {error:非法文件名}"),

    # ---- relay 采集端（RELAY_GET 全部 29 个 + available） ------------------
    ("/relay/available", OK200, "relay_available.json", CHECKS_RELAY_AVAIL, "中继可用性探测"),
    ("/relay/summary", OK200, "relay_summary.json", None, ""),
    ("/relay/keys", OK200, "relay_keys.json", None, ""),
    ("/relay/key", OK200, "relay_key.json", None, ""),
    ("/relay/quota", OK200, "relay_quota.json", None, ""),
    ("/relay/providers", OK200, "relay_providers.json", None, ""),
    ("/relay/daily", OK200, "relay_daily.json", None, ""),
    ("/relay/hourly", OK200, "relay_hourly.json", None, ""),
    ("/relay/weekday", OK200, "relay_weekday.json", None, ""),
    ("/relay/models", OK200, "relay_models.json", None, ""),
    ("/relay/models-daily", OK200, "relay_models-daily.json", None, ""),
    ("/relay/tokens", OK200, "relay_tokens.json", None, ""),
    ("/relay/model-status", OK200, "relay_model-status.json", None, ""),
    ("/relay/health", OK200, "relay_health.json", None, ""),
    ("/relay/selfheal", OK200, "relay_selfheal.json", None, ""),
    ("/relay/latency", OK200, "relay_latency.json", None, ""),
    ("/relay/ttfb", OK200, "relay_ttfb.json", None, ""),
    ("/relay/streaming", OK200, "relay_streaming.json", None, ""),
    ("/relay/sizes", OK200, "relay_sizes.json", None, ""),
    ("/relay/status", OK200, "relay_status.json", None, ""),
    ("/relay/routes", OK200, "relay_routes.json", None, ""),
    ("/relay/methods", OK200, "relay_methods.json", None, ""),
    ("/relay/endpoints", OK200, "relay_endpoints.json", None, ""),
    ("/relay/failover", OK200, "relay_failover.json", None, ""),
    ("/relay/errors", OK200, "relay_errors.json", None, ""),
    ("/relay/recent", OK200, "relay_recent.json", None, ""),
    ("/relay/sessions", OK200, "relay_sessions.json", None, ""),
    ("/relay/session-memory", OK200, "relay_session-memory.json", None, ""),
    ("/relay/useragents", OK200, "relay_useragents.json", None, ""),
    ("/relay/ips", {404}, "relay_ips.json", None, "采集端无此端点 → {error:unknown endpoint}，UI 需优雅降级"),
]


# ---------------------------------------------------------------- 工具
def jtype(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, (int, float)):
        return "num"
    if isinstance(v, str):
        return "str"
    if isinstance(v, list):
        return "array"
    if isinstance(v, dict):
        return "object"
    return type(v).__name__


def dig(obj, dotted):
    cur = obj
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return _MISSING
        cur = cur[part]
    return cur


class _Missing:
    pass


class _JsonNull:
    """响应体是合法 JSON 字面量 null（如 /pkg/job 无任务时）。"""


_MISSING = _Missing()
_JSONNULL = _JsonNull()


def request(method, path, token=None, body=None, timeout=REQ_TIMEOUT):
    """返回 (status, parsed_json_or_None, raw_text, headers)。绝不抛异常。"""
    url = API + path
    data = None
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status, _try_json(raw), raw, dict(r.headers)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        return e.code, _try_json(raw), raw, dict(e.headers)
    except Exception as e:  # 网络错误
        return -1, None, repr(e), {}


def _try_json(s):
    """解析响应体。返回：解析后的值 / _JSONNULL（响应体就是字面量 null）/ _MISSING（非 JSON 或空）。"""
    s = (s or "").strip()
    if not s:
        return _MISSING
    try:
        v = json.loads(s)
        return _JSONNULL if v is None else v
    except Exception:
        return _MISSING  # 非 JSON（如 Express 默认 404 的纯文本 Not Found）


def load_baseline(fname):
    if not fname:
        return None, None
    p = os.path.join(DUMP_DIR, fname)
    if not os.path.exists(p):
        return None, "基线文件缺失: " + fname
    with open(p, encoding="utf-8") as f:
        return json.load(f), None


def acquire_token(log):
    """优先重新登录拿独立令牌（不影响 tools/.token）；失败则退回 tools/.token。"""
    st, data, raw, _ = request("POST", "/login", body={"password": PASSWORD})
    if st == 200 and isinstance(data, dict) and data.get("token"):
        log.append("login: 200，取得独立 token（expiresAt=%s）" % data.get("expiresAt"))
        return data["token"], "fresh-login"
    log.append("login: status=%s body=%s —— 退回 tools/.token" % (st, raw[:120]))
    if os.path.exists(TOKEN_FILE):
        with open(TOKEN_FILE, encoding="utf-8") as f:
            return f.read().strip(), "tools/.token"
    return None, "none"


# ---------------------------------------------------------------- 主流程
def smart_get(path, token, results_extra, log):
    """带 429 退避的 GET，返回 rec 字典。"""
    for attempt in range(MAX_429_RETRIES + 1):
        st, data, raw, _ = request("GET", path, token=token)
        if st == 429:
            log.append("429 限流：%s（第 %d 次），退避 65s" % (path, attempt + 1))
            results_extra.append({"event": "429", "path": path, "attempt": attempt + 1})
            time.sleep(65)
            continue
        return st, data, raw
    return st, data, raw


def main():
    only = None
    no_report = False
    args = sys.argv[1:]
    i = 0
    global BASE_URL, API
    while i < len(args):
        if args[i] == "--only" and i + 1 < len(args):
            only = args[i + 1]; i += 2
        elif args[i] == "--base-url" and i + 1 < len(args):
            BASE_URL = args[i + 1]; API = BASE_URL.rstrip("/") + "/api"; i += 2
        elif args[i] == "--no-report":
            no_report = True; i += 1
        else:
            i += 1

    log, results_extra = [], []
    started = time.time()
    token, token_src = acquire_token(log)

    out = {
        "meta": {
            "base_url": BASE_URL, "api": API, "token_source": token_src,
            "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "rate_limit": "串行 + sleep(0.25s)，429 退避 65s×3（面板限流 1800 次/分/IP）",
        },
        "auth_tests": [], "cases": [], "extra_events": results_extra, "log": log,
    }

    # ---------------- 负向认证契约 ----------------
    # 注意（实测契约，2026-10-26 起）：/login 已用 try/catch 包住 auth.login 并透传
    # e.status/e.message —— 密码错误 → 401 {"error":"密码错误"}，失败 8 次锁 10 分钟
    # → 429 {"error":"尝试次数过多，请 10 分钟后再试"}。KMP 登录页仍须按
    # 「非 200 即登录失败 + 展示 error 文本」处理，不能假定一定是 401。
    negs = [
        ("GET /me 无 Authorization", "GET", "/me", None, None, {401}, "受保护端点缺 token → 401"),
        ("GET /me 伪造 token", "GET", "/me", "0" * 64, None, {401}, "无效 token → 401"),
        ("POST /login 错误密码", "POST", "/login", None,
         {"password": "definitely-wrong-" + str(int(time.time()))}, {401, 500},
         "401 密码错误（2026-10-26 起透传 e.status）；未打补丁的老面板仍为 500 服务器内部错误"),
    ]
    for name, method, path, tk, body, expect, note in negs:
        st, data, raw, _ = request(method, path, token=tk, body=body)
        ok = st in expect and isinstance(data, dict) and "error" in data
        out["auth_tests"].append({
            "name": name, "status": st, "expect": sorted(expect),
            "body": (raw or "")[:120], "ok": ok, "note": note,
        })
        print("[neg] %-22s -> %s %s" % (name, st, "OK" if ok else "MISMATCH"), flush=True)
        time.sleep(SLEEP_SEC)

    # ---------------- 主体 GET 用例 ----------------
    for idx, (path, expect, base, checks, note) in enumerate(CASES, 1):
        if only and only not in path:
            continue
        st, data, raw = smart_get(path, token, results_extra, log)
        # 重新组装成 request() 返回格式再交给 run_case 的逻辑：直接内联比对
        rec = _rec_from_triple(path, expect, base, checks, note, st, data, raw)
        out["cases"].append(rec)
        print("[%3d/%d] %-55s %4s %-5s %s" % (
            idx, len(CASES), path[:55], st, rec["verdict"], rec["conclusion"][:60]), flush=True)
        time.sleep(SLEEP_SEC)

    # ---------------- 安全 POST：/logs/tail ----------------
    src_id = None
    src_rec = next((c for c in out["cases"] if c["path"] == "/logs/sources"), None)
    if src_rec and isinstance(src_rec.get("types"), dict):
        pass
    st, sdata, sraw, _ = request("GET", "/logs/sources", token=token)
    if isinstance(sdata, dict):
        for bucket in ("files", "special"):
            arr = sdata.get(bucket) or []
            if arr and isinstance(arr[0], dict):
                src_id = arr[0].get("id") or arr[0].get("key") or arr[0].get("name")
                if src_id:
                    break
    tail = {"name": "POST /logs/tail（只读）", "source": src_id}
    if src_id:
        st, data, raw, _ = request("POST", "/logs/tail", token=token, body={"source": src_id})
        tail.update({"status": st, "ok": st == 200 and isinstance(data, dict) and isinstance(data.get("output"), str),
                     "body": (raw or "")[:120]})
    else:
        tail.update({"status": None, "ok": False, "body": "无法从 /logs/sources 解析日志源 id"})
    out["auth_tests"].append(tail)
    print("[post] /logs/tail source=%s -> %s %s" % (src_id, tail["status"], "OK" if tail["ok"] else "CHECK"), flush=True)
    time.sleep(SLEEP_SEC)

    # ---------------- 未知路由 404 形状 ----------------
    st, data, raw, _ = request("GET", "/no-such-route-xyz", token=token)
    out["auth_tests"].append({
        "name": "GET /no-such-route-xyz（记录 404 形状）", "status": st,
        "expect": [404], "body": (raw or "")[:120],
        "ok": st == 404, "note": "Express 兜底 404：非 JSON，UI 需识别",
    })
    print("[neg] unknown route -> %s" % st, flush=True)
    time.sleep(SLEEP_SEC)

    # ---------------- logout 契约（最后，用独立 token） ----------------
    if token_src == "fresh-login":
        st, data, raw, _ = request("POST", "/logout", token=token)
        ok1 = st == 200 and isinstance(data, dict) and data.get("ok") is True
        time.sleep(SLEEP_SEC)
        st2, _, _, _ = request("GET", "/me", token=token)
        out["auth_tests"].append({
            "name": "POST /logout 后 token 失效", "status": st, "status_after": st2,
            "expect": [200], "ok": ok1 and st2 == 401,
            "body": "logout=%s, /me-after=%s" % (raw[:60], st2),
        })
        print("[neg] logout -> %s, /me after -> %s" % (st, st2), flush=True)

    # ---------------- 汇总 ----------------
    out["meta"]["duration_s"] = round(time.time() - started, 1)
    out["meta"]["requests"] = len(out["cases"]) + len(out["auth_tests"]) + 2 + (1 if token_src == "fresh-login" else 0)
    fails = [c for c in out["cases"] if c["verdict"] == "FAIL"]
    warns = [c for c in out["cases"] if c["verdict"] == "WARN"]
    out["summary"] = {
        "total": len(out["cases"]),
        "pass": sum(1 for c in out["cases"] if c["verdict"] == "PASS"),
        "pass_star": sum(1 for c in out["cases"] if c["verdict"] == "PASS*"),
        "warn": len(warns), "fail": len(fails),
        "auth_failures": [t["name"] for t in out["auth_tests"] if not t.get("ok")],
    }

    if not no_report:
        with open(os.path.join(TESTS_DIR, "report-api.json"), "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
        write_markdown(out)
    print("== done: total=%d pass=%d pass*=%d warn=%d fail=%d ==" % (
        out["summary"]["total"], out["summary"]["pass"], out["summary"]["pass_star"],
        out["summary"]["warn"], out["summary"]["fail"]), flush=True)
    return 0 if not fails else 1


def _rec_from_triple(path, expect, baseline_file, checks, note, st, data, raw):
    """把一次已取回的响应 (status, parsed, raw) 与期望/基线比对，生成结果记录。"""
    rec = {
        "path": path, "method": "GET", "note": note,
        "expect_status": sorted(expect), "baseline_file": baseline_file,
        "status": st, "bytes": len(raw or ""),
        "status_ok": st in expect,
        "is_json": data is not _MISSING,   # 字面量 null（/pkg/job 无任务）也算合法 JSON
    }
    if data is _MISSING:
        rec.update(top_type="non-json", fields=0, types={}, field_errors=["响应不是 JSON: %r" % (raw or "")[:120]])
    elif data is _JSONNULL:
        rec.update(top_type="null", fields=0, types={}, field_errors=[])
    elif data is None:
        rec.update(top_type="no-body", fields=0, types={}, field_errors=[])
    else:
        rec.update(top_type=jtype(data),
                   fields=len(data) if isinstance(data, (dict, list)) else 1,
                   types=({k: jtype(v) for k, v in data.items()} if isinstance(data, dict) else {"<top>": jtype(data)}),
                   field_errors=[])

    checks_failed = []
    if checks and isinstance(data, dict):
        for k, want in checks.items():
            v = dig(data, k)
            if v is _MISSING:
                checks_failed.append("缺少关键字段 %s" % k)
            elif jtype(v) != want:
                checks_failed.append("关键字段 %s 类型应为 %s 实为 %s" % (k, want, jtype(v)))
    rec["checks_failed"] = checks_failed

    missing, type_drift, extra = [], [], []
    base, berr = load_baseline(baseline_file)
    rec["baseline_error"] = berr
    if berr:
        pass
    elif baseline_file is None:
        pass
    elif isinstance(base, dict) and isinstance(data, dict):
        for k, bv in base.items():
            if k not in data:
                missing.append(k)
            elif bv is not None and jtype(bv) != jtype(data[k]):
                type_drift.append("%s: 基线 %s → 实测 %s" % (k, jtype(bv), jtype(data[k])))
        extra = [k for k in data if k not in base]
    elif isinstance(base, str):
        if not (isinstance(data, dict) and "error" in data):
            missing.append("<错误契约 error 键>")
    elif isinstance(base, list) and isinstance(data, list):
        pass
    elif base is not None and type(base) is not type(data):
        missing.append("<顶层类型: 基线 %s → 实测 %s>" % (jtype(base), jtype(data)))
    rec["baseline_fields"] = len(base) if isinstance(base, (dict, list)) else (1 if base is not None else 0)
    rec["missing_vs_baseline"] = missing
    rec["type_drift_vs_baseline"] = type_drift
    rec["extra_vs_baseline"] = extra

    if not rec["status_ok"]:
        rec["verdict"], rec["conclusion"] = "FAIL", "状态码 %s 不在预期 %s" % (st, sorted(expect))
    elif checks_failed:
        rec["verdict"], rec["conclusion"] = "FAIL", "关键字段断言失败: " + "; ".join(checks_failed[:3])
    elif not rec["is_json"]:
        rec["verdict"], rec["conclusion"] = "FAIL", "响应不是 JSON"
    elif missing:
        rec["verdict"], rec["conclusion"] = "WARN", "结构漂移，缺字段: " + ", ".join(missing[:6])
    elif type_drift:
        rec["verdict"], rec["conclusion"] = "WARN", "类型漂移: " + "; ".join(type_drift[:3])
    elif baseline_file is None:
        rec["verdict"], rec["conclusion"] = "PASS*", "可达（无基准，仅验证路由与 JSON 结构）"
    else:
        rec["verdict"], rec["conclusion"] = "PASS", "状态/结构/字段与基准一致"
    return rec


def write_markdown(out):
    m = out["meta"]; s = out["summary"]
    lines = []
    a = lines.append
    a("# Cloud Monitor 面板 API 契约测试报告")
    a("")
    a("> 自动生成：`python tests/api_contract.py`（testing 角色）。请勿手改。")
    a("")
    a("## 概览")
    a("")
    a("| 项 | 值 |")
    a("|---|---|")
    a("| 目标 | `%s` |" % m["base_url"])
    a("| API base | `%s` |" % m["api"])
    a("| 令牌来源 | %s |" % m["token_source"])
    a("| 开始时间(UTC) | %s |" % m["started_utc"])
    a("| 耗时 | %s s |" % m.get("duration_s"))
    a("| 请求总数(约) | %d |" % m.get("requests", 0))
    a("| 限流策略 | %s |" % m["rate_limit"])
    a("| 用例总数 | %d |" % s["total"])
    a("| PASS | %d |" % s["pass"])
    a("| PASS*（无基准仅可达性） | %d |" % s["pass_star"])
    a("| WARN（结构/类型漂移） | %d |" % s["warn"])
    a("| FAIL | %d |" % s["fail"])
    a("")
    a("结论口径：**PASS** = 状态码 + 顶层结构 + 全部基线字段与类型一致；"
      "**PASS*** = 端点可达且返回合法 JSON（api-dump 无基准，多为需要参数或错误路径的端点）；"
      "**WARN** = 端点可用但与 api-dump 基线存在字段缺失/类型漂移（多为服务器数据状态变化，需人工确认）；"
      "**FAIL** = 状态码异常 / 非 JSON / 关键字段断言失败。")
    a("")

    a("## 认证与负向契约")
    a("")
    a("| 测试 | 状态 | 期望 | 结果 | 备注 |")
    a("|---|---|---|---|---|")
    for t in out["auth_tests"]:
        a("| %s | %s | %s | %s | %s |" % (
            t.get("name", ""), t.get("status", ""), ",".join(str(x) for x in t.get("expect", [])) or "-",
            "**OK**" if t.get("ok") else "**异常**", (t.get("body", "") or t.get("note", ""))[:80].replace("|", "\\|")))
    a("")

    a("## GET 端点契约明细")
    a("")
    a("| # | 端点 | 期望状态 | 实测状态 | 字段数(实测/基准) | 结论 | 说明 |")
    a("|---|---|---|---|---|---|---|")
    for i, c in enumerate(out["cases"], 1):
        a("| %d | `%s` | %s | %s | %s/%s | %s | %s |" % (
            i, c["path"], ",".join(str(x) for x in c["expect_status"]), c["status"],
            c["fields"], c["baseline_fields"] if c.get("baseline_file") else "-",
            {"PASS": "✅ PASS", "PASS*": "🟡 PASS*", "WARN": "⚠️ WARN", "FAIL": "❌ FAIL"}[c["verdict"]],
            (c["conclusion"] + ("；" + c["note"] if c.get("note") else ""))[:110].replace("|", "\\|")))
    a("")

    fails = [c for c in out["cases"] if c["verdict"] == "FAIL"]
    warns = [c for c in out["cases"] if c["verdict"] == "WARN"]
    if fails:
        a("## ❌ FAIL 明细（必须处理）")
        a("")
        for c in fails:
            a("### `%s`" % c["path"])
            a("")
            a("- 状态：%s（期望 %s）" % (c["status"], c["expect_status"]))
            a("- 结论：%s" % c["conclusion"])
            if c.get("checks_failed"):
                a("- 字段断言：%s" % "；".join(c["checks_failed"]))
            a("")
    if warns:
        a("## ⚠️ WARN 明细（结构漂移，需确认是数据状态变化还是契约变更）")
        a("")
        for c in warns:
            a("### `%s`" % c["path"])
            a("")
            a("- 状态：%s，字段数 实测 %s / 基准 %s" % (c["status"], c["fields"], c["baseline_fields"]))
            a("- 结论：%s" % c["conclusion"])
            if c.get("missing_vs_baseline"):
                a("- 缺失字段：%s" % ", ".join("`%s`" % k for k in c["missing_vs_baseline"][:20]))
            if c.get("type_drift_vs_baseline"):
                a("- 类型漂移：%s" % "；".join(c["type_drift_vs_baseline"][:10]))
            if c.get("extra_vs_baseline"):
                a("- 新增字段：%s" % ", ".join("`%s`" % k for k in c["extra_vs_baseline"][:20]))
            a("")
    a("## 关键契约发现（实测确认，Kotlin 侧必须照此实现）")
    a("")
    a("1. **登录失败按「非 200 + error 文本」处理**：`POST /login` 现由 try/catch 透传 `e.status`，"
      "密码错误 → **HTTP 401** `{\"error\":\"密码错误\"}`，失败 8 次锁 10 分钟 → **HTTP 429** "
      "`{\"error\":\"尝试次数过多，请 10 分钟后再试\"}`（2026-10-26 之前 `/login` 未包 wrap()，一律 "
      "500 `{\"error\":\"服务器内部错误\"}`，安卓端曾因此在锁定期内「密码正确也报服务器内部错误」）。"
      "客户端仍须按「非 200 即登录失败 + 展示 error 文本」实现；带 token 的受保护端点 401 "
      "`{\"error\":\"未登录或会话过期\"}` 用于触发清 token 回登录页。")
    a("2. **`GET /pkg/job` 无任务时返回字面量 JSON `null`**（4 字节）。Kotlin 侧解析要容忍 `JsonNull`，"
      "不要当成解析失败。")
    a("3. **`GET /relay/ips` → 404** `{\"error\":\"unknown endpoint\"}`：relay 采集端没有该端点，"
      "relay 页 clients 标签要优雅降级。")
    a("4. **引擎不可用 = 400 + `{error, output}`**：`/db/status|databases|roles|tables…?engine=postgres`"
      "在本机 psql 连不上时返回 400，`output` 是命令原始输出，应弹窗展示而不是崩溃。")
    a("5. **未知路由 → 404 纯文本 `Not Found`**（Express 兜底，非 JSON）：所有请求封装都要能容忍非 JSON 响应体。")
    a("6. **`POST /logout` → `{\"ok\":true}`**，之后该 token 在所有端点 401（已实测）。")
    a("7. 下载类端点（`/backups/download`、`/db/backup/download`、`/relayops/backup/download`）缺 `name` 时"
      "返回 400 `{\"error\":\"非法文件名\"}`；带 `name` 时是 `application/octet-stream` 二进制流"
      "（不是 JSON），下载封装不能按 JSON 解析。")
    a("")
    a("## 运行注意事项")
    a("")
    a("- 本脚本**只发 GET**（外加 `/login`、`/logout`、`/logs/tail` 三个无副作用 POST），不会触发任何服务器变更。")
    a("- 面板 API 限流为 **单 IP 1800 次/分钟**（`server/auth.js` `API_MAX_PER_WINDOW`；"
      "`index.js` 注释里的 240 是过时注释），脚本串行 + 0.25s 间隔，429 自动退避 65s 重试。")
    a("- 本机若开了系统代理（如 127.0.0.1:7890），urllib 会走代理并对裸 HTTP 返回空 502 —— "
      "脚本已强制直连（`ProxyHandler({})`）。其它脚本同样要注意。")
    a("- `/relay/ips` 返回 404 `{error:unknown endpoint}` 属采集端既定行为，App 端要优雅降级。")
    a("- `/db/status|databases|roles?engine=postgres` 返回 400（本机 psql 连不上 socket）属真实环境状态，"
      "App 需要展示 `{error,output}` 而不是崩溃。")
    a("")
    p = os.path.join(TESTS_DIR, "report-api.md")
    with open(p, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("report written: %s" % p, flush=True)


if __name__ == "__main__":
    sys.exit(main())
