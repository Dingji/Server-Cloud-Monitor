#!/usr/bin/env python3
"""Multi-provider key store, quota polling and nginx key selection.

Providers
---------
opencode-go   OpenCode Go plan     keys.json       upstream-auth.conf
glm           GLM Coding Plan      keys-glm.json   glm-auth.conf

Each provider keeps N keys; exactly one is active and is written into that
provider's nginx include (the file that injects the credential upstream). Quota is
read per provider with its own API shape:

* opencode-go  GET https://opencode.ai/zen/go/v1/usage
               -> {rolling|weekly|monthly: {status, percent, resetsAt}}
* glm          GET https://open.bigmodel.cn/api/monitor/usage/quota/limit?type=N
               Authorization: <key>            (raw, no "Bearer")
               bigmodel-organization / bigmodel-project headers
               -> {data:{limits:[{type,unit,number,percentage,nextResetTime}]}}
               unit 3 = 5-hour rolling window, unit 6 = weekly window

Selection = first enabled key in priority order whose windows are not used up.
When every key is exhausted the generated include flips the provider's
$<provider>_exhausted flag and nginx answers 503 instead of calling upstream.

Every include write is validated with `nginx -t` and rolled back on failure, so a
bad key or template can never take the relay down.
"""

import json
import os
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

UA = "opencode/latest/2.0.26/cli"
TZ = timezone(timedelta(hours=8))
USAGE_TTL = 45          # seconds a polled reading stays fresh
SWITCH_AT_PERCENT = 95  # switch away before the upstream starts refusing

PROVIDERS = {
    "opencode-go": {
        "label": "OpenCode Go",
        "keys_path": "/var/lib/relay/keys.json",
        "include": "/etc/relay/upstream-auth.conf",
        "flag": "relay_exhausted",
        "windows": ("rolling", "weekly", "monthly"),
        "usage_kind": "percent",
        "usage_url": "https://opencode.ai/zen/go/v1/usage",
        "validate": {"url": "https://opencode.ai/zen/go/v1/models", "method": "GET"},
    },
    "glm": {
        "label": "GLM Coding Plan",
        "keys_path": "/var/lib/relay/keys-glm.json",
        "include": "/etc/relay/glm-auth.conf",
        "flag": "glm_exhausted",
        "windows": ("rolling", "weekly"),
        "usage_kind": "glm",
        "usage_url": "https://open.bigmodel.cn/api/monitor/usage/quota/limit",
        "validate": {
            "url": "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions",
            "method": "POST",
            "body": {"model": "glm-5.3-flash", "max_tokens": 1,
                     "messages": [{"role": "user", "content": "hi"}]},
        },
        "default_model": "glm-5.3-flash",
    },
    "mimo": {
        "label": "MiMo Token Plan (CN)",
        "api_host": "token-plan-cn.xiaomimimo.com",
        "keys_path": "/var/lib/relay/keys-mimo.json",
        "include": "/etc/relay/mimo-auth.conf",
        "flag": "mimo_exhausted",
        "windows": ("rolling", "weekly"),
        "usage_kind": "none",          # no public quota endpoint for this plan
        "usage_url": "",
        "validate": {"url": "", "method": "GET"},
        "default_model": "mimo-v2.6",
    },
}
DEFAULT_PROVIDER = "opencode-go"

_LOCK = threading.RLock()


def spec(provider=None):
    return PROVIDERS[provider or DEFAULT_PROVIDER]


def now():
    return datetime.now(TZ)


def iso(dt=None):
    return (dt or now()).isoformat(timespec="seconds")


def mask_key(key):
    if not key:
        return ""
    return key[:10] + "…" + key[-6:] if len(key) > 20 else key[:4] + "…"


def parse_reset(value):
    if not value:
        return None
    if isinstance(value, (int, float)):
        ts = float(value)
        if ts > 1e11:      # epoch milliseconds
            ts /= 1000.0
        return datetime.fromtimestamp(ts, TZ)
    text = str(value).strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(TZ)


# ── persistence ─────────────────────────────────────────────────────────────

def _default_state():
    return {"version": 1, "active_id": None, "keys": [], "meta": {}, "updated_at": iso()}


def load(provider=None):
    path = spec(provider)["keys_path"]
    with _LOCK:
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError):
            data = _default_state()
        if not isinstance(data, dict) or "keys" not in data:
            data = _default_state()
        data.setdefault("version", 1)
        data.setdefault("keys", [])
        data.setdefault("active_id", None)
        data.setdefault("meta", {})
        return data


def save(data, provider=None):
    path = spec(provider)["keys_path"]
    with _LOCK:
        data["updated_at"] = iso()
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass


def _next_id(data):
    n = 1
    used = {k.get("id") for k in data["keys"]}
    while f"k{n}" in used:
        n += 1
    return f"k{n}"


# ── upstream calls ──────────────────────────────────────────────────────────

def http_json(url, key, provider=None, method="GET", body=None, extra_headers=None,
              raw_auth=False, timeout=25):
    headers = {"User-Agent": UA, "x-opencode-session": "relay-keystore"}
    if key:
        headers["Authorization"] = key if raw_auth else "Bearer " + key
        headers["x-api-key"] = key
    if extra_headers:
        headers.update({k: v for k, v in extra_headers.items() if v})
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = resp.read().decode("utf-8", "replace")
            try:
                return resp.status, json.loads(payload)
            except ValueError:
                return resp.status, {"raw": payload[:300]}
    except urllib.error.HTTPError as exc:
        payload = exc.read().decode("utf-8", "replace")
        try:
            return exc.code, json.loads(payload)
        except ValueError:
            return exc.code, {"raw": payload[:300]}
    except Exception as exc:
        return 0, {"error": f"{type(exc).__name__}: {exc}"}


# ── quota ───────────────────────────────────────────────────────────────────

def _glm_windows(payload):
    """Normalise the GLM quota payload into the shared window shape."""
    limits = ((payload.get("data") or {}).get("limits")
              or payload.get("limits") or [])
    out = {}
    for item in limits:
        if not isinstance(item, dict):
            continue
        unit = item.get("unit")
        resets = parse_reset(item.get("nextResetTime") or item.get("next_reset_time"))
        pct = item.get("percentage")
        if pct is None:
            used = item.get("currentValue")
            total = item.get("usage")
            if isinstance(used, (int, float)) and isinstance(total, (int, float)) and total:
                pct = round(100.0 * used / total, 2)
        entry = {
            "status": "ok" if (pct is None or float(pct) < 100) else "exceeded",
            "percent": pct,
            "resets_at": iso(resets) if resets else None,
            "resets_epoch": resets.timestamp() if resets else None,
            "raw_type": item.get("type"),
        }
        if unit == 3:
            out["rolling"] = entry
        elif unit == 6:
            out["weekly"] = entry
        elif unit == 5:
            out["monthly"] = entry
    return out


def fetch_usage(key, provider=None):
    sp = spec(provider)
    meta = load(provider).get("meta") or {}
    if sp["usage_kind"] == "none":
        return {"windows": {}, "note": "该 provider 没有公开的配额接口，按上游 429 被动切换"}
    if sp["usage_kind"] == "percent":
        status, data = http_json(sp["usage_url"], key, provider)
        if status != 200:
            return {"error": f"usage endpoint HTTP {status}", "detail": data}
        usage = data.get("usage", data) or {}
        out = {}
        for w in sp["windows"]:
            raw = usage.get(w)
            if isinstance(raw, dict):
                resets = parse_reset(raw.get("resetsAt") or raw.get("resets_at"))
                out[w] = {"status": str(raw.get("status", "unknown")),
                          "percent": raw.get("percent"),
                          "resets_at": iso(resets) if resets else None,
                          "resets_epoch": resets.timestamp() if resets else None}
            else:
                out[w] = {"status": "missing", "percent": None,
                          "resets_at": None, "resets_epoch": None}
        return {"windows": out, "fetched_at": iso()}

    # glm: needs the org/project context headers or the API answers with empty data
    qtype = meta.get("type") or 1
    extra = {
        "bigmodel-organization": meta.get("organization") or "",
        "bigmodel-project": meta.get("project") or "",
    }
    url = f"{sp['usage_url']}?type={qtype}"
    status, data = http_json(url, key, provider, raw_auth=True,
                             extra_headers=extra, timeout=20)
    if status != 200:
        return {"error": f"quota endpoint HTTP {status}", "detail": data}
    windows = _glm_windows(data)
    if not windows:
        return {"windows": {},
                "error": ("配额接口返回空数据：需要填写 bigmodel-organization 与 "
                          "bigmodel-project（浏览器 DevTools 里能拿到），type 个人=1/团队=2"),
                "fetched_at": iso()}
    return {"windows": windows, "fetched_at": iso()}


def validate(key, provider=None):
    sp = spec(provider)
    v = sp["validate"]
    url = v.get("url")
    if not url:
        # no probe endpoint documented: a 1-token chat call is the honest check
        url = f"https://{sp.get('api_host', '')}/v1/chat/completions"
        body = {"model": sp.get("default_model", ""), "max_tokens": 1,
                "messages": [{"role": "user", "content": "hi"}]}
        status, _ = http_json(url, key, provider, method="POST", body=body)
        return status in (200, 400), status      # 400 = reached upstream, key accepted
    status, _ = http_json(url, key, provider, method=v["method"], body=v.get("body"))
    return status == 200, status


# ── exhaustion / selection ──────────────────────────────────────────────────

def windows_of(provider=None):
    return spec(provider)["windows"]


def _window_exhausted(w):
    if not isinstance(w, dict):
        return False
    status = str(w.get("status", "")).lower()
    if status and status not in ("ok", "active", "valid", "enabled"):
        return True
    pct = w.get("percent")
    return isinstance(pct, (int, float)) and pct >= 100


def evaluate(entry, provider=None):
    usage = entry.get("usage") or {}
    windows = usage.get("windows") or {}
    ws = windows_of(provider)
    if usage.get("error"):
        entry["state"] = entry.get("state") if entry.get("state") == "exhausted" else "unknown"
        entry["usage_error"] = usage["error"]
        return entry
    entry.pop("usage_error", None)
    if not windows:
        entry["state"] = "unknown"
        entry["exhausted_windows"] = []
        return entry
    bad = [w for w in ws if _window_exhausted(windows.get(w))]
    entry["exhausted_windows"] = bad
    if bad:
        entry["state"] = "exhausted"
        soonest = None
        for w in bad:
            ep = (windows.get(w) or {}).get("resets_epoch")
            if ep and (soonest is None or ep < soonest):
                soonest = ep
        entry["recovers_epoch"] = soonest
        entry["recovers_at"] = (datetime.fromtimestamp(soonest, TZ).isoformat(timespec="seconds")
                                if soonest else None)
    else:
        entry["state"] = "ok"
        entry["recovers_epoch"] = None
        entry["recovers_at"] = None
    return entry


def near_limit(entry, provider=None):
    windows = ((entry.get("usage") or {}).get("windows") or {})
    for w in windows_of(provider):
        pct = (windows.get(w) or {}).get("percent")
        if isinstance(pct, (int, float)) and pct >= SWITCH_AT_PERCENT:
            return w, pct
    return None


def usable(entry, provider=None):
    """Enabled, and no window used up (re-derived from the raw numbers, not the cache)."""
    if not entry.get("enabled", True):
        return False
    windows = ((entry.get("usage") or {}).get("windows") or {})
    if any(_window_exhausted(windows.get(w)) for w in windows_of(provider)):
        return False
    return entry.get("state") != "exhausted"


def choose_active(data=None, provider=None):
    data = data or load(provider)
    ordered = sorted(data["keys"], key=lambda k: (k.get("order", 0), k.get("id", "")))
    for k in ordered:
        if usable(k, provider):
            return k
    return None


# ── nginx include ───────────────────────────────────────────────────────────

def render_include(active, provider=None):
    sp = spec(provider)
    flag = sp["flag"]
    lines = []
    if active is None:
        lines.append(f"set ${flag} 1;")
        lines.append('proxy_set_header Authorization "Bearer none";')
        lines.append('proxy_set_header x-api-key "none";')
    else:
        lines.append(f"set ${flag} 0;")
        lines.append(f'set ${flag.replace("_exhausted", "_key_id")} "{active.get("id")}";')
        lines.append(f'proxy_set_header Authorization "Bearer {active["key"]}";')
        lines.append(f'proxy_set_header x-api-key "{active["key"]}";')
    return "\n".join(lines) + "\n"


def apply_config(active, provider=None):
    path = spec(provider)["include"]
    body = render_include(active, provider)
    with _LOCK:
        try:
            with open(path, encoding="utf-8") as f:
                old = f.read()
        except OSError:
            old = None
        if old == body:
            return True, "unchanged"
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(body)
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
        test = subprocess.run(["nginx", "-t"], capture_output=True, text=True)
        if test.returncode != 0:
            if old is not None:
                with open(path, "w", encoding="utf-8") as f:
                    f.write(old)
            else:
                try:
                    os.remove(path)
                except OSError:
                    pass
            return False, (test.stderr or test.stdout or "").strip()[-300:]
        subprocess.run(["systemctl", "reload", "nginx"], check=False)
    return True, "reloaded"


def seed_from_config(data, provider=None):
    """Adopt whichever key the provider include already carries."""
    path = spec(provider)["include"]
    try:
        with open(path, encoding="utf-8") as f:
            m = re.search(r"Bearer ([A-Za-z0-9_\-\.]{20,200})\"", f.read())
    except OSError:
        m = None
    if not m or m.group(1) == "none":
        return data
    key = m.group(1)
    if any(k.get("key") == key for k in data["keys"]):
        return data
    data["keys"].append({"id": _next_id(data), "key": key, "label": "现有 key（自动导入）",
                         "enabled": True, "order": 0, "added_at": iso(), "state": "unknown"})
    save(data, provider)
    return data


def sync(force_poll=False, provider=None):
    with _LOCK:
        data = load(provider)
        if not data["keys"]:
            seed_from_config(data, provider)
        changed = polled = False
        for entry in data["keys"]:
            if not entry.get("enabled", True):
                continue
            age = time.time() - float(entry.get("usage_ts") or 0)
            if force_poll or age > USAGE_TTL:
                entry["usage"] = fetch_usage(entry["key"], provider)
                entry["usage_ts"] = time.time()
                polled = changed = True
                evaluate(entry, provider)
            else:
                evaluate(entry, provider)
        active = choose_active(data, provider)
        active_id = active.get("id") if active else None
        switched = active_id != data.get("active_id")
        if changed:
            save(data, provider)
        if switched:
            ok, detail = apply_config(active, provider)
            entry = {"at": iso(), "ok": ok, "detail": detail, "to": active_id,
                     "to_masked": mask_key(active["key"]) if active else None}
            data["switch_log"] = ([entry] + (data.get("switch_log") or []))[:50]
            if ok:
                data["active_id"] = active_id
            save(data, provider)
            return {"switched": True, "ok": ok, "detail": detail,
                    "active_id": active_id, "polled": polled}
        return {"switched": False, "active_id": data.get("active_id"), "polled": polled}


def sync_all(force_poll=False):
    out = {}
    for name in PROVIDERS:
        try:
            out[name] = sync(force_poll=force_poll, provider=name)
        except Exception as exc:
            out[name] = {"error": f"{type(exc).__name__}: {exc}"}
    return out


def ensure_config(provider=None):
    with _LOCK:
        data = load(provider)
        if not data["keys"]:
            seed_from_config(data, provider)
        active = choose_active(data, provider)
    return apply_config(active, provider)


# ── public API for the collector / UI ───────────────────────────────────────

def status(provider=None):
    sp = spec(provider)
    data = load(provider)
    if not data["keys"]:
        data = seed_from_config(data, provider)
    ordered = sorted(data["keys"], key=lambda k: (k.get("order", 0), k.get("id", "")))
    active = choose_active(data, provider)
    return {
        "provider": provider or DEFAULT_PROVIDER,
        "label": sp["label"],
        "windows": list(sp["windows"]),
        "active_id": active.get("id") if active else None,
        "exhausted": active is None,
        "meta": data.get("meta") or {},
        "keys": [{
            "id": k.get("id"),
            "label": k.get("label") or "",
            "masked": mask_key(k.get("key")),
            "enabled": bool(k.get("enabled", True)),
            "order": k.get("order", 0),
            "state": k.get("state", "unknown"),
            "exhausted_windows": k.get("exhausted_windows") or [],
            "recovers_at": k.get("recovers_at"),
            "usage": ((k.get("usage") or {}).get("windows") or {}),
            "usage_error": k.get("usage_error") or ((k.get("usage") or {}).get("error")),
            "usage_at": (k.get("usage") or {}).get("fetched_at"),
            "added_at": k.get("added_at"),
            "active": bool(active and active.get("id") == k.get("id")),
        } for k in ordered],
        "switch_log": (data.get("switch_log") or [])[:8],
        "updated_at": data.get("updated_at"),
    }


def status_all():
    return {name: status(name) for name in PROVIDERS}


def set_meta(provider, patch):
    with _LOCK:
        data = load(provider)
        meta = data.get("meta") or {}
        for k, v in (patch or {}).items():
            if v in (None, ""):
                meta.pop(k, None)
            else:
                meta[k] = v
        data["meta"] = meta
        save(data, provider)
    return True, "已保存"


def add(key, label="", provider=None):
    key = (key or "").strip()
    if not re.fullmatch(r"[A-Za-z0-9_\-\.]{20,200}", key):
        return False, "key 格式不合法"
    with _LOCK:
        data = load(provider)
        if not data["keys"]:
            seed_from_config(data, provider)
        if any(k.get("key") == key for k in data["keys"]):
            return False, "该 key 已存在"
        ok, st = validate(key, provider)
        if not ok:
            return False, f"该 key 无法访问上游 API (HTTP {st})，未添加"
        order = max([k.get("order", 0) for k in data["keys"]] or [-1]) + 1
        entry = {"id": _next_id(data), "key": key,
                 "label": label.strip() or f"key {order + 1}",
                 "enabled": True, "order": order, "added_at": iso(), "state": "unknown"}
        entry["usage"] = fetch_usage(key, provider)
        entry["usage_ts"] = time.time()
        evaluate(entry, provider)
        data["keys"].append(entry)
        save(data, provider)
    sync(provider=provider)
    return True, f"已添加 {mask_key(key)}（{entry['state']}）"


def remove(key_id, provider=None):
    with _LOCK:
        data = load(provider)
        before = len(data["keys"])
        data["keys"] = [k for k in data["keys"] if k.get("id") != key_id]
        if len(data["keys"]) == before:
            return False, "未找到该 key"
        for i, k in enumerate(sorted(data["keys"], key=lambda x: x.get("order", 0))):
            k["order"] = i
        save(data, provider)
    sync(provider=provider)
    return True, "已删除"


def set_enabled(key_id, enabled, provider=None):
    with _LOCK:
        data = load(provider)
        for k in data["keys"]:
            if k.get("id") == key_id:
                k["enabled"] = bool(enabled)
                break
        else:
            return False, "未找到该 key"
        save(data, provider)
    sync(provider=provider)
    return True, "已启用" if enabled else "已停用"


def move(key_id, delta, provider=None):
    with _LOCK:
        data = load(provider)
        ordered = sorted(data["keys"], key=lambda k: (k.get("order", 0), k.get("id", "")))
        idx = next((i for i, k in enumerate(ordered) if k.get("id") == key_id), None)
        if idx is None:
            return False, "未找到该 key"
        j = max(0, min(len(ordered) - 1, idx + delta))
        ordered.insert(j, ordered.pop(idx))
        for i, k in enumerate(ordered):
            k["order"] = i
        save(data, provider)
    sync(provider=provider)
    return True, "已调整优先级"


def activate(key_id, provider=None):
    with _LOCK:
        data = load(provider)
        ordered = sorted(data["keys"], key=lambda k: (k.get("order", 0), k.get("id", "")))
        idx = next((i for i, k in enumerate(ordered) if k.get("id") == key_id), None)
        if idx is None:
            return False, "未找到该 key"
        entry = ordered[idx]
        if entry.get("state") == "exhausted":
            return False, f"该 key 已耗尽配额（{','.join(entry.get('exhausted_windows') or [])}），无法激活"
        entry["enabled"] = True
        ordered.insert(0, ordered.pop(idx))
        for i, k in enumerate(ordered):
            k["order"] = i
        save(data, provider)
    sync(provider=provider)
    return True, "已设为优先使用"


def test(key_id, provider=None):
    with _LOCK:
        data = load(provider)
        entry = next((k for k in data["keys"] if k.get("id") == key_id), None)
        if entry is None:
            return False, "未找到该 key", None
        ok, st = validate(entry["key"], provider)
        usage = fetch_usage(entry["key"], provider)
        entry["usage"] = usage
        entry["usage_ts"] = time.time()
        entry["state"] = "ok" if ok else "invalid"
        evaluate(entry, provider)
        save(data, provider)
    return ok, f"HTTP {st}", usage


def mark_active_exhausted(reason="upstream 429", provider=None):
    with _LOCK:
        data = load(provider)
        active = next((k for k in data["keys"] if k.get("id") == data.get("active_id")), None)
        if active is not None:
            active["last_error"] = f"{iso()} {reason}"
            active["last_error_ts"] = time.time()
        save(data, provider)
    return sync(force_poll=True, provider=provider)


def mark_all_exhausted(reason="upstream 429"):
    """A 429 came back: we cannot tell which provider served it, so refresh both."""
    return {name: mark_active_exhausted(reason, provider=name) for name in PROVIDERS}
