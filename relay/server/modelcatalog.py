#!/usr/bin/env python3
"""Single source of truth for model lists: /models-catalog.json

Every client (opencode.json, mimocode.jsonc, the DSH profile) is generated from this
file, so the model list lives on the relay, not in five hand-edited configs.

Providers it describes:
  relay-go    http://8.153.153.176          -> opencode.ai/zen/go/v1   (Go plan)
  relay-zen   http://8.153.153.176/zen/v1   -> opencode.ai/zen/v1      (zen line)
  relay-glm   http://8.153.153.176/glm/v1   -> open.bigmodel.cn         (GLM Coding Plan)
  relay-mimo  http://8.153.153.176/mimo/v1  -> token-plan-cn.xiaomimimo.com (MiMo Token Plan CN)

Status values: ok (probed and answered), free (upstream restricts to opencode's own
client), unavailable (upstream rejects), untested (not probed yet).
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

TOKEN = open("/etc/relay/relay-token", encoding="utf-8").read().strip() if os.path.exists(
    "/etc/relay/relay-token") else "5278dae39bb5b8ec5f5a5c5546b68280821d39407bf3698a"
PUBLIC = "/var/lib/relay/public"
OUT = os.path.join(PUBLIC, "models-catalog.json")
PROBE_BUDGET = float(os.environ.get("PROBE_BUDGET", "90"))   # seconds of upstream calls
UA = "relay-modelcatalog/selfhosted"

# context windows we can state with confidence (tokens)
WINDOWS = {
    "deepseek-v4.1-flash": 1000000, "deepseek-v4-pro": 1000000, "deepseek-v4-flash": 1000000,
    "deepseek-flash": 1000000, "claude-haiku-5-5": 200000, "claude-sonnet-5-5": 200000,
    "claude-opus-5-5": 200000, "glm-5.3": 200000, "glm-5.3-flash": 200000, "glm-5.2": 200000,
    "glm-5.1": 200000, "grok-4.6": 256000, "grok-4.7": 256000, "kimi-k2.6": 256000,
    "kimi-k2.7-code": 256000, "kimi-k3": 256000, "minimax-m3": 204800, "minimax-m2.7": 204800,
    "qwen3.8-max": 262144, "qwen3.8-flash": 262144, "qwen3.7-max": 262144, "qwen3.6-plus": 262144,
    "mimo-v2.6-flash": 1048576, "mimo-v2.6-pro": 1048576, "mimo-v2.5": 1048576,
    "mimo-v2.5-pro": 1048576, "gemini-4-argon": 1000000, "gemini-3.8-flash": 1000000,
    "gpt-6.1-sol": 400000, "gpt-6-sol": 400000, "gpt-6-luna": 400000, "mistral-large-4": 256000,
    "jev-1.13": 200000, "omen-alpha": 200000, "hy3": 200000, "hy4-preview": 200000,
}

PROVIDERS = {
    "relay-go": {
        "label": "OpenCode Go 套餐",
        "baseURL": "http://8.153.153.176",
        "path": "/v1",
        "upstream": "https://opencode.ai/zen/go/v1",
        "protocol": "openai+anthropic",
        "plan": "Go 套餐（按 5 小时 / 周 / 月限额）",
    },
    "relay-zen": {
        "label": "OpenCode Zen",
        "baseURL": "http://8.153.153.176/zen/v1",
        "path": "/zen/v1",
        "upstream": "https://opencode.ai/zen/v1",
        "protocol": "openai+anthropic",
        "plan": "Zen 线路（按模型计费，免费模型需 opencode 客户端）",
    },
    "relay-glm": {
        "label": "GLM Coding Plan",
        "baseURL": "http://8.153.153.176/glm/v1",
        "path": "/glm/v1",
        "upstream": "https://open.bigmodel.cn/api/coding/paas/v4",
        "protocol": "openai",
        "plan": "智谱 Coding Plan（5 小时 / 周限额）",
    },
    "relay-mimo": {
        "label": "MiMo Token Plan (CN)",
        "baseURL": "http://8.153.153.176/mimo/v1",
        "path": "/mimo/v1",
        "upstream": "https://token-plan-cn.xiaomimimo.com/v1",
        "protocol": "openai",
        "plan": "小米 MiMo 中国区 Token Plan",
    },
}
# models the GLM / MiMo plans actually serve (their upstreams don't publish /models)
FIXED = {
    "relay-glm": ["glm-5.3", "glm-5.3-flash"],
    "relay-mimo": ["mimo-v2.6", "mimo-v2.6-flash", "mimo-v2.5"],
}


def http_json(url, headers=None, timeout=30):
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8", "replace") or "{}")
        except Exception:
            return e.code, {}
    except Exception as e:
        return 0, {"error": str(e)[:120]}


def fetch_catalog(provider):
    p = PROVIDERS[provider]
    url = f"http://127.0.0.1{p['path']}/models"
    status, body = http_json(url, {"Authorization": f"Bearer {TOKEN}", "User-Agent": UA}, 40)
    ids = []
    if status == 200:
        ids = [m.get("id") for m in (body.get("data") or []) if m.get("id")]
    return ids, status


def classify(mid):
    if mid.endswith("-free") or mid in ("big-pickle", "ling-3.1-flash-free", "space-bunny-free",
                                        "muse-spark-1.2-contributor-free", "step-5-preview-free",
                                        "jev-1.13-free", "longcat-2.5-preview-free", "cat-jam-free"):
        return "free"
    return "untested"


def probe(provider, mid):
    """A 1-token real request; the answer tells us whether the model is usable."""
    p = PROVIDERS[provider]
    url = f"http://127.0.0.1{p['path']}/chat/completions"
    body = json.dumps({"model": mid, "max_tokens": 8,
                       "messages": [{"role": "user", "content": "say ok"}]}).encode()
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json",
        "User-Agent": UA, "x-opencode-session": f"catalog-{int(time.time()*1000)}"})
    try:
        with urllib.request.urlopen(req, timeout=45) as r:
            return r.status, ""
    except urllib.error.HTTPError as e:
        text = ""
        try:
            text = e.read().decode("utf-8", "replace")[:240]
        except Exception:
            pass
        return e.code, text
    except Exception as e:
        return 0, str(e)[:120]


def main():
    catalog = {"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
               "generated_by": "modelcatalog.py", "providers": PROVIDERS, "models": []}
    statuses = {}
    try:
        with open(os.path.join(PUBLIC, "models-status.json"), encoding="utf-8") as fh:
            for row in json.load(fh).get("models", []):
                statuses[row["id"]] = row.get("status") or row.get("state")
    except Exception:
        pass

    # Real 1-token probes cost upstream calls; the only scheduled availability
    # check is relay-modelcheck at 08:30. This catalog refresh stays list-only.
    probe_enabled = "--probe" in sys.argv
    deadline = time.time() + PROBE_BUDGET
    summary = {}
    for provider in ("relay-go", "relay-zen", "relay-glm", "relay-mimo"):
        if provider in FIXED:
            ids, cat_status = FIXED[provider], 200
        else:
            ids, cat_status = fetch_catalog(provider)
        summary[provider] = {"catalog_status": cat_status, "models": len(ids), "probed": 0}
        for mid in ids:
            st = classify(mid)
            if provider in FIXED:
                st = "untested"          # GLM/MiMo keys may not be loaded yet
            # reuse an existing probe result for the Go plan where we have one
            if st == "untested" and provider == "relay-go" and statuses.get(mid) == "ok":
                st = "ok"
            elif st == "untested" and probe_enabled and time.time() < deadline:
                code, text = probe(provider, mid)
                summary[provider]["probed"] += 1
                if code == 200:
                    st = "ok"
                elif code == 403 and "FreeTierError" in text:
                    st = "free"
                elif code == 400 and "unavailable" in text:
                    st = "unavailable"
                else:
                    st = f"http{code}"
            catalog["models"].append({
                "id": mid, "provider": provider, "baseURL": PROVIDERS[provider]["baseURL"],
                "protocol": PROVIDERS[provider]["protocol"], "window": WINDOWS.get(mid),
                "status": st})

    os.makedirs(PUBLIC, exist_ok=True)
    tmp = OUT + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(catalog, fh, ensure_ascii=False, indent=1)
    os.replace(tmp, OUT)
    os.chmod(OUT, 0o644)

    by_status = {}
    for m in catalog["models"]:
        by_status[m["status"]] = by_status.get(m["status"], 0) + 1
    print(f"wrote {OUT}  ({len(catalog['models'])} models)")
    for k, v in summary.items():
        print(f"  {k:12} catalog_http={v['catalog_status']} models={v['models']:>3} probed={v['probed']}")
    print("  status:", " ".join(f"{k}={v}" for k, v in sorted(by_status.items())))
    ok = [m["id"] for m in catalog["models"] if m["status"] == "ok"]
    print("  usable:", ", ".join(ok))


if __name__ == "__main__":
    main()
