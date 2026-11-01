#!/usr/bin/env python3
"""Unit tests for the relay tap's routing policy (busy + idle) and client identity.

Runs against the real tap module with its paths pointed at a temp dir, so no
system state is touched:

  python tools/relay_policy_test.py
"""
import importlib.util
import json
import os
import sys
import tempfile
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))

TMP = tempfile.mkdtemp(prefix="relay-policy-")
CONF = os.path.join(TMP, "busy-hours.json")
OFF_FLAG = os.path.join(TMP, "busy-hours.off")
ALLOW = os.path.join(TMP, "go-allowlist.json")

with open(CONF, "w", encoding="utf-8") as f:
    json.dump({}, f)  # everything defaults
with open(ALLOW, "w", encoding="utf-8") as f:
    json.dump({"models": ["mimo-v2.6-flash", "deepseek-v4.1-flash", "longcat-2.5-preview-free"]}, f)

spec = importlib.util.spec_from_file_location("usagetap", os.path.join(
    os.path.dirname(__file__), "..", "upstream", "opt", "relay", "usagetap.py"))
tap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tap)
tap.BUSY_CONF_PATH = CONF
tap.BUSY_OFF_FLAG = OFF_FLAG
tap.GO_ALLOWLIST_PATH = ALLOW
tap._go_allow["loaded"] = False  # force reload from the patched path
tap._busy_conf["mtime"] = None

ALLOWED = tap.go_allowlist()
assert ALLOWED, "allowlist did not load"

# 北京时间周五 10:30 = 忙时；周五 20:00 = 闲时；周六 10:30 = 闲时（补集语义）
BUSY = datetime(2026, 10, 30, 10, 30, tzinfo=TZ)     # Friday
IDLE_EVE = datetime(2026, 10, 30, 20, 0, tzinfo=TZ)  # Friday evening
IDLE_NOON = datetime(2026, 10, 30, 12, 30, tzinfo=TZ)
WEEKEND = datetime(2026, 10, 31, 10, 30, tzinfo=TZ)  # Saturday

passed = failed = 0


def ok(name, cond, extra=None):
    global passed, failed
    if cond:
        passed += 1
        print("  PASS  " + name)
    else:
        failed += 1
        print("  FAIL  " + name + ("  -> %r" % (extra,) if extra is not None else ""))


def eval_at(model, at, allowed=ALLOWED):
    return tap.policy_rewrite("go", model, allowed, at)


m, why, pol = eval_at("deepseek-v4.1-flash", BUSY)
ok("忙时 deepseek → mimo (busy)", m == "mimo-v2.6-flash" and pol == "busy", (m, why, pol))

m, why, pol = eval_at("mimo-v2.6-flash", BUSY)
ok("忙时 mimo 原样透传", m is None and why == "busy_not_a_match", (m, why, pol))

m, why, pol = eval_at("longcat-2.5-preview-free", BUSY)
ok("忙时 longcat 原样透传", m is None and pol is None, (m, why, pol))

m, why, pol = eval_at("mimo-v2.6-flash", IDLE_EVE)
ok("闲时 mimo → deepseek (idle)", m == "deepseek-v4.1-flash" and pol == "idle", (m, why, pol))

m, why, pol = eval_at("longcat-2.5-preview-free", IDLE_NOON)
ok("午间休市也是闲时：longcat → deepseek", m == "deepseek-v4.1-flash" and pol == "idle", (m, why, pol))

m, why, pol = eval_at("deepseek-v4.1-flash", IDLE_EVE)
ok("闲时 deepseek 本来就是目标：不改写", m is None and why == "already_target", (m, why, pol))

m, why, pol = eval_at("mimo-v2.6-flash", WEEKEND)
ok("周末整天都是闲时 → deepseek", m == "deepseek-v4.1-flash" and pol == "idle", (m, why, pol))

# 兜底：开关、禁用、目标不在白名单、线路不符
open(OFF_FLAG, "w").close()
m, why, pol = eval_at("mimo-v2.6-flash", IDLE_EVE)
ok("kill switch 关闭全部改写", m is None and why == "switch_off", (m, why, pol))
os.unlink(OFF_FLAG)

doc = tap.busy_conf()
doc["idle"]["enabled"] = False
with open(CONF, "w", encoding="utf-8") as f:
    json.dump(doc, f)
tap._busy_conf["mtime"] = None
m, why, pol = eval_at("mimo-v2.6-flash", IDLE_EVE)
ok("idle.enabled=false 时不改写", m is None and why == "idle_disabled", (m, why, pol))
doc["idle"]["enabled"] = True
doc["idle"]["target_model"] = "gpt-9-imaginary"
with open(CONF, "w", encoding="utf-8") as f:
    json.dump(doc, f)
tap._busy_conf["mtime"] = None
m, why, pol = eval_at("mimo-v2.6-flash", IDLE_EVE)
ok("闲时目标不在白名单 → 不改写（fail-open）", m is None and why == "target_not_allowed", (m, why, pol))
doc["idle"]["target_model"] = "deepseek-v4.1-flash"
doc["idle"]["windows"] = [{"start": "03:00", "end": "05:00"}]
with open(CONF, "w", encoding="utf-8") as f:
    json.dump(doc, f)
tap._busy_conf["mtime"] = None
m, why, pol = eval_at("mimo-v2.6-flash", IDLE_EVE)
ok("配置了 idle.windows 就按窗口而不是补集", m is None and why == "idle_off", (m, why, pol))
m, why, pol = eval_at("mimo-v2.6-flash", datetime(2026, 10, 30, 4, 0, tzinfo=TZ))
ok("idle 窗口内照常改写", m == "deepseek-v4.1-flash" and pol == "idle", (m, why, pol))

# 回到默认配置，验证 busy_rewrite 兼容包装与 zen 线路豁免
with open(CONF, "w", encoding="utf-8") as f:
    json.dump({}, f)
tap._busy_conf["mtime"] = None
m, why = tap.busy_rewrite("go", "mimo-v2.6-flash", ALLOWED, IDLE_EVE)
ok("busy_rewrite 包装返回 (model, reason)", m == "deepseek-v4.1-flash", (m, why))
m, why, pol = tap.policy_rewrite("zen", "some-model", None, IDLE_EVE)
ok("zen/glm/mimo 线路不参与策略", m is None and why == "other_route", (m, why, pol))

# 客户端身份：令牌只落哈希、IP 取 X-Real-IP、UA 截断
hdrs = {"Authorization": "Bearer relay-token-abcdef", "User-Agent": "opencode/2.1.4 " + "x" * 200,
        "X-Real-IP": "203.0.113.7"}
k, ip, ua = tap.client_identity(hdrs, "127.0.0.1")
ok("令牌变成 sha256 前缀且不出现原文", len(k) == 16 and "relay-token" not in k, k)
ok("IP 取 X-Real-IP", ip == "203.0.113.7", ip)
ok("UA 截断到 120", len(ua) == 120, len(ua))
hdrs2 = {"x-api-key": "tok2", "X-Forwarded-For": "198.51.100.9, 10.0.0.1"}
k2, ip2, ua2 = tap.client_identity(hdrs2, "127.0.0.1")
ok("x-api-key 同样识别、XFF 取第一跳", k2 and k2 != k and ip2 == "198.51.100.9", (k2, ip2))
k3, ip3, ua3 = tap.client_identity({}, "127.0.0.1")
ok("无凭据/无头时回退 socket 地址", k3 == "" and ip3 == "127.0.0.1", (k3, ip3))

# 空白名单 fail-open
m, why, pol = eval_at("mimo-v2.6-flash", IDLE_EVE, allowed=frozenset())
ok("白名单不可用时整段 fail-open", m is None and why == "target_not_allowed", (m, why, pol))

print("\n%d pass, %d fail" % (passed, failed))
sys.exit(1 if failed else 0)
