#!/usr/bin/env python3
"""OpenCode relay model capability probe (v2).

WHY THIS REPLACED THE OLD PROBE
-------------------------------
The old probe sent a *non-streaming* request and reported the model "ok". When the
upstream later disabled the streaming path for some models, every real client
(which always streams) failed with `[model_coming_soon]` while this probe still
reported "ok". Probe path != real client path.

This probe sends a **streaming** request for every model on every wire protocol
the relay exposes, records each result separately, and classifies failures --
including the streaming-only ones that mean "works non-streamed, not streamed".

Output
------
/var/lib/relay/public/models-status.json   (dashboard + client sync; compatible
                                            with the old shape, plus `routes`
                                            and `stream` detail per model)

A model is `ok` when at least one protocol answers 200 **while streaming**.
"""
import json
import os
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

RELAY = "http://127.0.0.1"
OUT = "/var/lib/relay/public/models-status.json"
LOG = "/var/lib/relay/modelcheck.log"
TZ = timezone(timedelta(hours=8))
UA = "relay-modelcheck/2.0"
TOKEN_FILE = "/etc/relay/relay.env"
TIMEOUT = 90

# Context window per model. The upstream /models endpoint does not advertise it,
# and consumers (DSH's pi-ai profile) require a positive integer: a null there
# makes the whole provider fail to load, which is exactly how every opencode
# model disappeared from the DSH picker once.
CONTEXT_WINDOWS = {
    "mimo-v2.6-flash": 1048576, "mimo-v2.6-pro": 1048576,
    "mimo-v2.5": 1048576, "mimo-v2.5-pro": 1048576,
    "deepseek-v4.1-flash": 1000000, "deepseek-v4-pro": 1000000,
    "deepseek-v4-flash": 1000000, "deepseek-flash": 1000000,
    "glm-5.3": 200000, "glm-5.3-flash": 200000, "glm-5.2": 200000, "glm-5.1": 200000,
    "qwen3.8-flash": 262144, "qwen3.7-plus": 262144, "qwen3.6-plus": 262144,
    "qwen3.8-max": 262144, "qwen3.7-max": 262144,
    "kimi-k2.6": 262144, "kimi-k2.7-code": 262144, "kimi-k3": 262144,
    "longcat-2.5-preview-free": 262144, "longcat-2.0": 262144,
    "minimax-m3": 204800, "minimax-m2.5": 204800, "minimax-m2.7": 204800,
    "grok-4.6": 262144, "grok-4.7": 262144, "gpt-5.6-luna": 400000,
    "gpt-6-luna": 400000, "claude-haiku-5-5": 200000,
    "space-bunny": 200000, "omen-alpha": 200000, "hy3": 200000,
    "hy4-preview": 200000, "step-5-preview": 200000,
    "mistral-large-4": 256000, "gpt-6.1-sol": 400000,
    "claude-opus-5-5-backup": 200000,
}
DEFAULT_CONTEXT = 262144

PROVIDERS = {
    "opencode-go": {"list": "/v1/models", "route": "go", "models_path": "/v1/"},
    "opencode": {"list": "/zen/v1/models", "route": "zen", "models_path": "/zen/v1/"},
}
DISABLE = {"removed", "region_blocked", "payment_required", "route_unsupported"}


def token():
    txt = open(TOKEN_FILE, encoding="utf-8").read()
    start = txt.find('"') + 1
    return txt[start:txt.find('"', start)]


TOKEN = token()


def http(path, payload, headers, timeout=TIMEOUT):
    body = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        RELAY + path, data=body, method="POST" if body else "GET",
        headers={"Content-Type": "application/json", "User-Agent": UA, **headers})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(6000).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        try:
            return e.code, e.read(6000).decode("utf-8", "replace")
        except Exception:
            return e.code, ""
    except Exception as e:
        return 0, "%s: %s" % (type(e).__name__, e)


def classify(code, body):
    low = (body or "").lower()
    if "model_not_allowed" in low:
        return "plan_restricted"
    if code == 200:
        head = (body or "").lstrip()
        # A 200 that carries an HTML landing page instead of a completion means
        # the request was misrouted (wrong path, captive proxy, double slash).
        # Counting that as "ok" is how a probe reports a healthy model list
        # while every real request fails, so refuse it explicitly.
        if head.startswith("<") or ("data:" not in low and '"choices"' not in low
                                    and '"content"' not in low
                                    and '"error"' not in low
                                    and '"usage"' not in low):
            return "bad_route"
        return "ok"
    if "missingsessionid" in low or "missing x-opencode-session" in low:
        return "missing_session"
    if "freelierror" in low or "only be used from within opencode" in low:
        return "probe_restricted"
    if "regionerror" in low or "not available in your country" in low:
        return "region_blocked"
    if code == 402 or "insufficient account funds" in low:
        return "payment_required"
    if code == 401:
        return "auth_error"
    if code == 429:
        return "rate_limited"
    if code == 404 or any(k in low for k in ("model_not_found", "model not found",
                                             "unknown model", "does not exist")):
        return "removed"
    if "coming_soon" in low:
        # streaming path not deployed; a non-streamed request may still work
        return "stream_coming_soon"
    if "not support" in low or "unsupported" in low or "protocol" in low:
        return "route_unsupported"
    if code >= 500 or code == 0:
        return "upstream_error"
    return "failed"


def shape(protocol, mid, stream):
    if protocol == "chat":
        return "chat/completions", {"model": mid, "stream": stream,
                                       "max_tokens": 8 if not stream else 16,
                                       "messages": [{"role": "user", "content": "ping"}]}
    if protocol == "responses":
        return "responses", {"model": mid, "stream": stream, "input": "ping",
                                "max_output_tokens": 16}
    return "messages", {"model": mid, "stream": stream, "max_tokens": 16,
                           "messages": [{"role": "user", "content": "ping"}]}


def headers_for(protocol, session):
    if protocol == "messages":
        return {"x-api-key": TOKEN, "anthropic-version": "2023-06-01",
                "x-opencode-session": session}
    return {"Authorization": "Bearer " + TOKEN, "x-opencode-session": session}


def probe_model(base, mid):
    session = "probe-%d-%s" % (int(time.time()), mid.replace(".", "-")[:40])
    routes = {}
    for protocol in ("chat", "responses", "messages"):
        tmpl, payload = shape(protocol, mid, True)
        path = base + tmpl
        code, body = http(path, payload, headers_for(protocol, session))
        verdict = classify(code, body)
        routes[protocol] = {"status": verdict, "code": code,
                            "detail": (body or "")[:200]}
        if verdict == "ok":
            continue
        # a streaming-only failure is worth recording as usable-nonstreamed
        if verdict in ("stream_coming_soon", "route_unsupported") and protocol == "chat":
            tmpl2, payload2 = shape(protocol, mid, False)
            c2, b2 = http(base + tmpl2, payload2, headers_for(protocol, session))
            v2 = classify(c2, b2)
            if v2 == "ok":
                routes[protocol]["nonstream"] = "ok"
    order = ("chat", "responses", "messages")
    usable = [p for p in order if routes[p]["status"] == "ok"]
    nonstream_only = [p for p, r in routes.items()
                      if r["status"] in ("stream_coming_soon", "route_unsupported")
                      and r.get("nonstream") == "ok"]
    if usable:
        status = "ok"
        via = usable[0]
    elif nonstream_only:
        # the relay's stream fallback should hide this; flag it loudly
        status = "stream_only_blocked"
        via = nonstream_only[0]
    else:
        first = next(iter(routes.values()))
        status, via = first["status"], None
    return {"status": status, "via": via, "code": routes[via]["code"] if via else 0,
            "detail": routes[via]["detail"] if via else
            next(iter(routes.values()))["detail"],
            "routes": routes, "checked_at": datetime.now(TZ).isoformat(timespec="seconds"),
            "enabled": status == "ok",
            "auto_disable": status in DISABLE,
            "streaming": status == "ok"}


def main():
    started = time.time()
    result = {"generated_at": datetime.now(TZ).isoformat(timespec="seconds"),
              "relay": "http://8.153.153.176", "version": 2,
              "providers": {}, "summary": {}}
    ok = bad = 0
    for pid, cfg in PROVIDERS.items():
        restricted = {}
        status, body = http(cfg["list"], None, {"Authorization": "Bearer " + TOKEN})
        if status != 200:
            result["providers"][pid] = {"error": "list HTTP %s" % status, "models": {}}
            continue
        ids = [m.get("id") for m in json.loads(body).get("data", []) if m.get("id")]
        # ── NIGHTLY_SCOPE: only the Go allowlist and the free models ────────
        # The nightly availability/statistics run exists to answer one question:
        # "do the models this plan may use still work?" Everything else is
        # recorded as excluded without a single request, so the run is fast and
        # its numbers cannot be skewed by models the plan cannot use.
        restricted = {}
        checked_here = 0
        probe_ids = []
        if pid == "opencode-go":
            try:
                allowed = set(json.load(open("/etc/relay/go-allowlist.json",
                                             encoding="utf-8")).get("models") or [])
            except Exception:
                allowed = set()
            result["go_allowlist"] = sorted(allowed)
            free = {m for m in ids if m.endswith("-free")}
            probe_ids = [m for m in ids if m in allowed or m in free]
            for mid in ids:
                if mid not in probe_ids:
                    restricted[mid] = {
                        "status": "plan_restricted", "via": None, "code": 403,
                        "detail": "not in OpenCode Go allowlist",
                        "routes": {}, "checked_at": datetime.now(TZ).isoformat(timespec="seconds"),
                        "enabled": False, "auto_disable": False, "streaming": False}
        else:
            # Zen / other routes are outside the plan's usable set; do not probe.
            for mid in ids:
                restricted[mid] = {
                    "status": "excluded", "via": None, "code": 0,
                    "detail": "nightly check covers Go allowlist + free models only",
                    "routes": {}, "checked_at": datetime.now(TZ).isoformat(timespec="seconds"),
                    "enabled": False, "auto_disable": False, "streaming": False}
            probe_ids = []
        with ThreadPoolExecutor(max_workers=4) as pool:
            outcomes = list(pool.map(
                lambda mid: (mid, probe_model(cfg["models_path"], mid)), probe_ids))
        models = dict(restricted)
        for mid, info in sorted(outcomes):
            models[mid] = info
            checked_here += 1
            if info["status"] == "ok":
                ok += 1
            else:
                bad += 1
        result.setdefault("providers", {})
        result["providers"][pid] = {"route": cfg["route"],
                                    "models_path": cfg["models_path"],
                                    "models": models}
    excluded = sum(1 for p in result["providers"].values()
                   for i in (p.get("models") or {}).values()
                   if i.get("status") in ("plan_restricted", "excluded"))
    result["summary"] = {"usable": ok, "not_usable": bad,
                         "checked": ok + bad, "excluded": excluded,
                         "scope": "go_allowlist+free",
                         "duration_s": round(time.time() - started, 1)}
    # Every entry carries an integer context window (see CONTEXT_WINDOWS).
    for provider in result["providers"].values():
        for mid, entry in (provider.get("models") or {}).items():
            entry["window"] = CONTEXT_WINDOWS.get(mid, DEFAULT_CONTEXT)
            entry["context"] = entry["window"]

    # Safety: never publish a report that is obviously broken (bad credentials,
    # nginx down, wrong token). A poisoned report would make every client sync
    # disable every model, so keep the previous report instead.
    if ok == 0:
        print("ABORT: probe found no usable model (%d bad); keeping previous report" % bad)
        with open(LOG, "a", encoding="utf-8") as f:
            f.write("%s ABORT probe=0-usable not_usable=%d (previous report kept)\n"
                    % (result["generated_at"], bad))
        return 1
    tmp = OUT + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    os.replace(tmp, OUT)
    os.chmod(OUT, 0o644)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write("%s checked=%d usable=%d not_usable=%d excluded=%d %.1fs\n"
                % (result["generated_at"], ok + bad, ok, bad, excluded,
                   result["summary"]["duration_s"]))
    print("wrote %s checked=%d usable=%d not_usable=%d excluded=%d in %ss"
          % (OUT, ok + bad, ok, bad, excluded, result["summary"]["duration_s"]))
    for pid in result["providers"]:
        blocked = [m for m, i in result["providers"][pid].get("models", {}).items()
                   if i["status"] == "stream_only_blocked"]
        if blocked:
            print("  STREAM-ONLY BLOCKED %s: %s" % (pid, ", ".join(blocked)))


if __name__ == "__main__":
    main()

