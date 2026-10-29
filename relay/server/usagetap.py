#!/usr/bin/env python3
"""Inline usage tap for the OpenCode relay.

Why this exists
---------------
Token counts are only in the *response body* (Anthropic `message_delta.usage`,
OpenAI `usage`), and nginx cannot parse bodies -- not even with Lua available.
Headers carry no token data and the upstream exposes no per-request usage API, so
the only way to report accurate tokens is to read the response as it streams.

What it does
------------
It sits between nginx and opencode.ai for the two inference routes:

    client -> nginx -> this tap (127.0.0.1:8790) -> https://opencode.ai/...

Every response byte is forwarded to the client unchanged. A copy is fed to an
incremental parser that extracts token usage, and one row per request is written
to the `usage_events` table in the relay database.

Safety
------
* Read-only on the response path: bytes are relayed as they arrive, never buffered
  or re-encoded, so streaming behaviour is identical.
* Any failure before the first response byte returns a JSON 502 instead of
  hanging. A failure mid-stream just closes the connection.
* The watchdog treats a dead tap as "bypass it": nginx is rewritten to talk to
  opencode.ai directly, so the relay keeps working without token statistics.
* Storage failures are swallowed after logging -- stats must never break traffic.
"""

import http.client
import json
import os
import re
import socket
import sqlite3
import ssl
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
try:
    from chinese_calendar import is_workday
except Exception:
    def is_workday(d):
        return d.weekday() < 5
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN_HOST = "127.0.0.1"
LISTEN_PORT = 8790
UPSTREAM_HOST = "opencode.ai"
UPSTREAM_PORT = 443
DB_PATH = "/var/lib/relay/relay.db"
AUTH_INCLUDE = "/etc/relay/upstream-auth.conf"
HEALTH_PATH = "/var/lib/relay/public/usagetap.json"

CONNECT_TIMEOUT = 8
READ_TIMEOUT = 3600          # matches nginx proxy_read_timeout
CHUNK = 16384
MAX_JSON_BODY = 8 * 1024 * 1024
TZ = timezone(timedelta(hours=8))

# local URI prefix -> (upstream host, upstream path prefix, route name)
ROUTES = (
    ("/mimo/v1/", "token-plan-cn.xiaomimimo.com", "/v1/", "mimo"),
    ("/glm/anthropic/", "open.bigmodel.cn", "/api/anthropic/", "glm"),
    ("/glm/v1/", "open.bigmodel.cn", "/api/coding/paas/v4/", "glm"),
    ("/zen/v1/", "opencode.ai", "/zen/v1/", "zen"),
    ("/v1/", "opencode.ai", "/zen/go/v1/", "go"),
)

# upstream host -> the nginx include holding its active credential
KEY_INCLUDES = {
    "opencode.ai": "/etc/relay/upstream-auth.conf",
    "open.bigmodel.cn": "/etc/relay/glm-auth.conf",
    "token-plan-cn.xiaomimimo.com": "/etc/relay/mimo-auth.conf",
}

SSL_CTX = ssl.create_default_context()

SCHEMA = """
CREATE TABLE IF NOT EXISTS usage_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts REAL NOT NULL,
  day TEXT NOT NULL,
  hour TEXT NOT NULL,
  route TEXT,
  protocol TEXT,
  model TEXT,
  sess TEXT,
  status INTEGER,
  streaming INTEGER,
  duration REAL,
  input_tokens INTEGER,
  output_tokens INTEGER,
  cache_read INTEGER,
  cache_write INTEGER,
  reasoning INTEGER,
  total_tokens INTEGER,
  client_gone INTEGER DEFAULT 0,
  raw TEXT
);
CREATE INDEX IF NOT EXISTS ix_usage_day ON usage_events(day);
CREATE INDEX IF NOT EXISTS ix_usage_model ON usage_events(model);

CREATE TABLE IF NOT EXISTS session_turns (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts REAL NOT NULL,
  day TEXT NOT NULL,
  sess TEXT,
  route TEXT,
  protocol TEXT,
  model TEXT,
  msgs INTEGER,
  sent_bytes INTEGER,
  status INTEGER,
  prompt TEXT,
  reply TEXT
);
CREATE INDEX IF NOT EXISTS ix_turn_sess ON session_turns(sess, ts);
CREATE INDEX IF NOT EXISTS ix_turn_day ON session_turns(day);
"""

STATE = {"rows": 0, "last": None, "error": None, "started": time.time(), "requests": 0,
         "rewrites": 0, "last_rewrite": None, "rewrite_skips": {}}
prune_last = [0.0]
_LOCK = threading.Lock()


# ── upstream credential (kept in sync with what nginx would send) ───────────

_key_cache = {}
_key_lock = threading.Lock()


def current_key(host=UPSTREAM_HOST):
    """Read the active credential for an upstream host from its include."""
    path = KEY_INCLUDES.get(host, AUTH_INCLUDE)
    with _key_lock:
        try:
            mtime = os.path.getmtime(path)
        except OSError:
            mtime = 0
        cached = _key_cache.get(host)
        if cached and mtime and cached[0] == mtime and cached[1]:
            return cached[1]
        try:
            with open(path, encoding="utf-8") as f:
                m = re.search(r'Bearer ([A-Za-z0-9_\-\.]{20,200})"', f.read())
            key = m.group(1) if m else None
        except OSError:
            key = None
        _key_cache[host] = (mtime, key)
        return key


# ── database ────────────────────────────────────────────────────────────────

_db_lock = threading.Lock()


def db():
    conn = sqlite3.connect(DB_PATH, timeout=25)
    conn.execute("PRAGMA busy_timeout=20000")
    return conn


def init_db():
    with _db_lock:
        conn = db()
        try:
            conn.executescript(SCHEMA)
            conn.commit()
        finally:
            conn.close()


def record_turn(row):
    try:
        with _db_lock:
            conn = db()
            try:
                conn.execute("INSERT INTO session_turns (ts, day, sess, route, protocol, model,"
                             " msgs, sent_bytes, status, prompt, reply)"
                             " VALUES (?,?,?,?,?,?,?,?,?,?,?)", row)
                conn.commit()
            finally:
                conn.close()
    except Exception as exc:
        print(f"turn archive failed: {exc}", file=sys.stderr, flush=True)


def record(row):
    try:
        with _db_lock:
            conn = db()
            try:
                conn.execute(
                    "INSERT INTO usage_events (ts, day, hour, route, protocol, model, sess, status,"
                    " streaming, duration, input_tokens, output_tokens, cache_read, cache_write,"
                    " reasoning, total_tokens, client_gone, raw)"
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row)
                conn.commit()
            finally:
                conn.close()
        with _LOCK:
            STATE["rows"] += 1
            STATE["last"] = datetime.now(TZ).isoformat(timespec="seconds")
            STATE["error"] = None
    except Exception as exc:  # statistics must never break traffic
        with _LOCK:
            STATE["error"] = f"{type(exc).__name__}: {exc}"
        print(f"usage record failed: {exc}", file=sys.stderr, flush=True)


# ── usage parsing ───────────────────────────────────────────────────────────

def _int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def _bigger(a, b):
    if a is None:
        return b
    if b is None:
        return a
    return a if a >= b else b


class UsageParser:
    """Incrementally extract token usage from a JSON or SSE response body."""

    def __init__(self, streaming):
        self.streaming = streaming
        self.buf = b""
        self.parts = {}
        self.saw_usage = False
        self.reply = []
        self.reply_len = 0
        self.raw_tail = []      # capped copy of the response, for a regex fallback

    def feed(self, chunk):
        self.buf += chunk
        if sum(len(x) for x in self.raw_tail) < 262144:
            self.raw_tail.append(chunk)
        if self.streaming:
            while True:
                nl = self.buf.find(b"\n")
                if nl < 0:
                    break
                line, self.buf = self.buf[:nl], self.buf[nl + 1:]
                line = line.strip()
                if line.startswith(b"data:"):
                    payload = line[5:].strip()
                    if payload and payload != b"[DONE]":
                        self._payload(payload)
        elif len(self.buf) > MAX_JSON_BODY:
            self.buf = self.buf[-4096:]

    def _payload(self, payload):
        try:
            obj = json.loads(payload)
        except Exception:
            return
        self._scan(obj)

    def _scan(self, obj, depth=0):
        if depth > 4 or not isinstance(obj, (dict, list)):
            return
        if isinstance(obj, list):
            for item in obj:
                self._scan(item, depth + 1)
            return
        usage = obj.get("usage")
        if isinstance(usage, dict):
            self._merge(usage)
        self._text(obj)
        for key in ("message", "delta", "response"):
            sub = obj.get(key)
            if isinstance(sub, dict):
                self._scan(sub, depth + 1)

    def _merge(self, u):
        def take(name, field):
            v = _int(u.get(field))
            if v is not None:
                self.parts[name] = _bigger(self.parts.get(name), v)
                self.saw_usage = True

        take("input", "input_tokens")
        take("input", "prompt_tokens")
        take("output", "output_tokens")
        take("output", "completion_tokens")
        take("cache_read", "cache_read_input_tokens")
        take("cache_write", "cache_creation_input_tokens")
        take("reasoning", "reasoning_tokens")
        details = u.get("prompt_tokens_details")
        if isinstance(details, dict):
            take("cache_read", "cached_tokens")
        cdetails = u.get("completion_tokens_details")
        if isinstance(cdetails, dict):
            take("reasoning", "reasoning_tokens")
        take("total", "total_tokens")

    def _add(self, text):
        if not text or self.reply_len >= TEXT_CAP:
            return
        text = str(text)
        room = TEXT_CAP - self.reply_len
        self.reply.append(text[:room])
        self.reply_len += min(len(text), room)

    def _text(self, obj, depth=0):
        if depth > 4 or not isinstance(obj, (dict, list)):
            return
        if isinstance(obj, list):
            for item in obj:
                self._text(item, depth + 1)
            return
        delta = obj.get("delta")
        if isinstance(delta, dict):
            self._add(delta.get("text"))
            self._add(delta.get("content"))
        for key in ("content", "message", "choices"):
            sub = obj.get(key)
            if isinstance(sub, list):
                for item in sub:
                    if isinstance(item, dict):
                        self._add(item.get("text"))
                        self._text(item, depth + 1)
            elif isinstance(sub, dict):
                self._add(sub.get("content"))
                self._text(sub, depth + 1)

    def reply_text(self):
        text = "".join(self.reply).strip()
        if not text and self.raw_tail:
            # fallback: pull every content delta straight out of the SSE text
            import re as _re
            raw = b"".join(self.raw_tail).decode("utf-8", "replace")
            parts = _re.findall(r'"content"\s*:\s*"((?:[^"\\]|\\.)*)"', raw)
            if not parts:
                parts = _re.findall(r'"text"\s*:\s*"((?:[^"\\]|\\.)*)"', raw)
            joined = ""
            for p in parts:
                try:
                    joined += json.loads('"' + p + '"')
                except Exception:
                    joined += p
            text = joined.strip()
        return clip(text)

    def result(self):
        if not self.streaming and not self.saw_usage and self.buf:
            try:
                self._scan(json.loads(self.buf))
            except Exception:
                pass
        p = self.parts
        if not p:
            return None
        inp, out = p.get("input"), p.get("output")
        cr, cw = p.get("cache_read") or 0, p.get("cache_write") or 0
        total = p.get("total")
        if total is None:
            total = (inp or 0) + (out or 0) + cr + cw
        return {
            "input": inp, "output": out, "cache_read": cr, "cache_write": cw,
            "reasoning": p.get("reasoning"), "total": total,
        }


TEXT_CAP = 4000


def clip(text):
    if not isinstance(text, str):
        return None
    text = text.strip()
    return text[:TEXT_CAP] + ("…" if len(text) > TEXT_CAP else "") if text else None


def extract_turn(body):
    """Newest user prompt + message count from a chat request body."""
    try:
        doc = json.loads(body)
    except Exception:
        return None, None
    if not isinstance(doc, dict):
        return None, None
    msgs = doc.get("messages") or doc.get("input") or []
    if not isinstance(msgs, list):
        return None, None
    prompt = None
    for m in reversed(msgs):
        if not isinstance(m, dict) or m.get("role") != "user":
            continue
        c = m.get("content")
        if isinstance(c, str):
            prompt = c
        elif isinstance(c, list):
            parts = [p.get("text") for p in c
                     if isinstance(p, dict) and p.get("type") in ("text", "input_text")]
            prompt = "\n".join(x for x in parts if x)
        if prompt:
            break
    return clip(prompt), len(msgs)


# The upstream (opencode zen) already returns a final usage chunk on its own --
# verified by probing with the tap bypassed. Injecting stream_options ourselves
# only added a "choices":[] chunk to every stream, which some clients mishandle,
# so it is now off by default (set RELAY_INJECT_USAGE=1 to re-enable).
INJECT_DISABLED = os.environ.get("RELAY_INJECT_USAGE", "0") != "1"


def maybe_inject_usage(body, path):
    """Ask an OpenAI-protocol stream to include the final usage chunk.

    Without stream_options.include_usage the OpenAI-style stream carries no token
    counts at all, which would leave those requests unmeasured.
    """
    if not body or b'"stream"' not in body:
        return body, False
    try:
        doc = json.loads(body)
    except Exception:
        return body, False
    if not isinstance(doc, dict) or not doc.get("stream"):
        return body, False
    if doc.get("stream_options", {}).get("include_usage"):
        return body, False
    doc.setdefault("stream_options", {})["include_usage"] = True
    return json.dumps(doc, ensure_ascii=False).encode("utf-8"), True



# ── STREAM_FALLBACK_MARK: streaming-only upstream failures ──────────────────
# Upstream answers these for `stream: true` on models whose streaming path is
# not deployed, while `stream: false` works. Matched case-insensitively on the
# response body, so a model genuinely unavailable in every mode is unaffected
# (the non-streaming retry reproduces its error and that is what reaches the
# client).
STREAM_ONLY_MARKERS = (
    "model_coming_soon",
    "does not support this protocol",
    "not support this protocol",
)


def is_stream_only_failure(status, body):
    """True when a streamed request failed only because it was streamed."""
    if status != 400 or not body:
        return False
    low = body.decode("utf-8", "replace").lower()
    return any(marker in low for marker in STREAM_ONLY_MARKERS)


def non_stream_body(body):
    """Return the same request body with stream disabled and stream_options dropped."""
    try:
        doc = json.loads(body)
    except Exception:
        return None
    if not isinstance(doc, dict) or not doc.get("stream"):
        return None
    doc["stream"] = False
    doc.pop("stream_options", None)
    return json.dumps(doc, ensure_ascii=False).encode("utf-8")


def completion_to_sse(payload):
    """Replay a non-streaming chat completion as SSE chunks a streaming client accepts."""
    try:
        doc = json.loads(payload)
    except Exception:
        return None
    if not isinstance(doc, dict) or doc.get("object") != "chat.completion":
        return None
    choices = doc.get("choices") or []
    if not choices:
        return None
    created = doc.get("created") or int(time.time())
    cid = doc.get("id") or f"relay-fallback-{created}"
    model = doc.get("model") or ""
    base = {"id": cid, "object": "chat.completion.chunk",
            "created": created, "model": model}
    out = []

    def emit(obj):
        out.append(b"data: " + json.dumps(obj, ensure_ascii=False).encode("utf-8") + b"\n\n")

    for index, choice in enumerate(choices):
        message = choice.get("message") or {}
        delta = {"role": message.get("role") or "assistant"}
        if message.get("content"):
            delta["content"] = message["content"]
        # Reasoning-capable models may have spent the budget on thinking; carry
        # it so clients that render reasoning still see what the model produced.
        if message.get("reasoning_content"):
            delta["reasoning_content"] = message["reasoning_content"]
        if message.get("reasoning"):
            delta["reasoning"] = message["reasoning"]
        if message.get("tool_calls"):
            delta["tool_calls"] = message["tool_calls"]
        emit({**base, "choices": [{"index": index, "delta": delta,
                                   "finish_reason": None}]})
        emit({**base, "choices": [{"index": index, "delta": {},
                                   "finish_reason": choice.get("finish_reason") or "stop"}]})
    usage = doc.get("usage")
    if isinstance(usage, dict):
        emit({**base, "choices": [], "usage": usage})
    out.append(b"data: [DONE]\n\n")
    return b"".join(out)


def open_upstream(method, target, headers):
    """Open a fresh upstream connection and send one request (used for the retry)."""
    conn = http.client.HTTPSConnection(
        UPSTREAM_HOST, UPSTREAM_PORT, timeout=CONNECT_TIMEOUT, context=SSL_CTX)
    conn.connect()
    try:
        conn.sock.settimeout(READ_TIMEOUT)
    except Exception:
        pass
    return conn

# ── GO_ALLOWLIST_MARK: OpenCode Go plan model allowlist ─────────────────────
# The Go plan is a paid subscription with a limited model set. Restricting it
# here means even a client with a stale config, or a manual request, cannot use
# a disallowed model through this relay.
GO_ALLOWLIST_PATH = "/etc/relay/go-allowlist.json"
_go_allow = {"mtime": None, "models": frozenset(), "loaded": False}


def go_allowlist():
    """Allowed model ids; reloaded whenever the allowlist file changes.

    Fail-closed: if the file has never loaded, the result is empty and the caller
    refuses the request. A missing file must not silently turn the plan
    restriction off; the relay guard restores it within two minutes.
    """
    try:
        st = os.stat(GO_ALLOWLIST_PATH)
    except OSError:
        return _go_allow["models"] if _go_allow["loaded"] else frozenset()
    if st.st_mtime != _go_allow["mtime"]:
        try:
            doc = json.load(open(GO_ALLOWLIST_PATH, encoding="utf-8"))
            models = frozenset(str(m) for m in (doc.get("models") or []) if m)
            if models:
                _go_allow["models"] = models
                _go_allow["loaded"] = True
            _go_allow["mtime"] = st.st_mtime
        except Exception:
            pass
    return _go_allow["models"]


def model_allowed(model):
    return model in go_allowlist()


# ── BUSY_HOURS_MARK: busy-hour routing away from deepseek ───────────────────
# OpenCode Go's deepseek capacity degrades during Beijing business hours, so in
# those windows a deepseek request is served by mimo-v2.6-flash instead. Only the
# route named in the config is touched: the target id is a Go-plan id and means
# nothing to the Zen / GLM / MiMo upstreams, so widening the scope by hand would
# just produce upstream errors.
#
# Everything here fails OPEN. Missing config, unparsable config, a disabled
# switch, or a target that is not in the current allowlist all mean "do not
# rewrite" -- the client keeps the model it asked for. This tap is also the only
# place the policy lives: when the watchdog decides the tap is dead it bypasses
# nginx straight to the upstream (see selfheal.py), and no policy runs at all.
BUSY_CONF_PATH = "/etc/relay/busy-hours.json"
BUSY_OFF_FLAG = "/var/lib/relay/busy-hours.off"
BUSY_DEFAULTS = {
    "enabled": True,
    "route": "go",
    "target_model": "mimo-v2.6-flash",
    "rewrite_prefixes": ["deepseek"],
    "windows": [{"start": "09:00", "end": "12:00"}, {"start": "14:00", "end": "18:00"}],
    "weekdays_only": True,
    "utc_offset_minutes": 480,
}
_busy_conf = {"mtime": None, "doc": dict(BUSY_DEFAULTS)}


def busy_conf():
    """Policy doc, reloaded whenever the file changes; defaults on any failure."""
    try:
        st = os.stat(BUSY_CONF_PATH)
    except OSError:
        return dict(BUSY_DEFAULTS)
    if st.st_mtime != _busy_conf["mtime"]:
        try:
            raw = json.load(open(BUSY_CONF_PATH, encoding="utf-8"))
            if isinstance(raw, dict):
                doc = dict(BUSY_DEFAULTS)
                doc.update({k: v for k, v in raw.items() if k in BUSY_DEFAULTS})
                if not isinstance(doc.get("windows"), list) or not doc["windows"]:
                    doc["windows"] = BUSY_DEFAULTS["windows"]
                if not isinstance(doc.get("rewrite_prefixes"), list):
                    doc["rewrite_prefixes"] = BUSY_DEFAULTS["rewrite_prefixes"]
                _busy_conf["doc"] = doc
            _busy_conf["mtime"] = st.st_mtime
        except Exception:
            _busy_conf["mtime"] = st.st_mtime   # do not re-parse a broken file per request
    return _busy_conf["doc"]


def _hhmm(text):
    """'HH:MM' -> minutes past local midnight, or None."""
    try:
        h, m = str(text).strip().split(":")
        h, m = int(h), int(m)
    except Exception:
        return None
    if not (0 <= h < 24 and 0 <= m < 60):
        return None
    return h * 60 + m


def busy_window_active(doc, nowdt):
    """True when nowdt falls inside a configured window.

    A window whose end is <= its start is treated as crossing midnight.
    For CN mainland, "weekdays_only" means workdays only (working hours on
    working days) -- includes makeup workdays and excludes weekends/public holidays.
    """
    local = nowdt.astimezone(timezone(timedelta(minutes=int(doc.get("utc_offset_minutes", 480)))))
    if doc.get("weekdays_only"):
        try:
            if not is_workday(local.date()):
                return False
        except Exception:
            if local.weekday() > 4:
                return False
    minutes = local.hour * 60 + local.minute
    for w in doc.get("windows") or []:
        a, b = _hhmm(w.get("start")), _hhmm(w.get("end"))
        if a is None or b is None:
            continue
        if (a <= minutes < b) if a < b else (minutes >= a or minutes < b):
            return True
    return False


def busy_off():
    """Temporary kill switch: a file, so no config edit and no restart."""
    return os.path.exists(BUSY_OFF_FLAG)


def busy_rewrite(route, model, allowed, nowdt=None):
    """(new_model, reason) or (None, why-not). Never raises."""
    try:
        doc = busy_conf()
        if not doc.get("enabled", True):
            return None, "disabled_in_config"
        if busy_off():
            return None, "switch_off"
        if route != doc.get("route", "go"):
            return None, "other_route"
        target = str(doc.get("target_model") or "")
        if not target or not str(model).startswith(tuple(doc.get("rewrite_prefixes") or ())):
            return None, "not_a_match"
        if model == target:
            return None, "already_target"
        if allowed is None or not allowed or target not in allowed:
            # Rewriting into a model the plan may refuse would turn a working
            # request into a 403. With an unknown or broken allowlist, keep the
            # model the client asked for.
            return None, "target_not_allowed"
        if not busy_window_active(doc, nowdt or datetime.now(TZ)):
            return None, "outside_window"
        return target, "rewritten"
    except Exception as exc:
        return None, "policy_error:%s" % type(exc).__name__


def busy_rewritten_body(body, model):
    """Request body with "model" replaced, or None if the body is not rewritable."""
    try:
        doc = json.loads(body)
    except Exception:
        return None
    if not isinstance(doc, dict) or not isinstance(doc.get("model"), str):
        return None
    doc["model"] = model
    try:
        return json.dumps(doc, ensure_ascii=False).encode("utf-8")
    except Exception:
        return None


# ── request handling ────────────────────────────────────────────────────────

HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
              "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "relay-usagetap"

    def log_message(self, *args):
        pass

    def do_GET(self):
        self._proxy("GET")

    def do_POST(self):
        self._proxy("POST")

    def do_PUT(self):
        self._proxy("PUT")

    def do_PATCH(self):
        self._proxy("PATCH")

    def do_DELETE(self):
        self._proxy("DELETE")

    def do_OPTIONS(self):
        self._proxy("OPTIONS")

    # helpers ---------------------------------------------------------------
    def _json(self, code, obj):
        payload = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(payload)
        except Exception:
            pass

    def _target(self):
        path = self.path
        for local, host, upstream, route in ROUTES:
            if path.startswith(local):
                return route, host, upstream + path[len(local):]
        return None, None, None

    def _proxy(self, method):
        started = time.time()
        route, host, target = self._target()
        if target is None:
            return self._json(404, {"error": "unknown route"})

        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        try:
            body = self.rfile.read(length) if length else b""
        except Exception:
            body = b""

        body, injected = maybe_inject_usage(body, self.path)

        key = current_key(host)
        headers = {}
        for name, value in self.headers.items():
            if name.lower() in HOP_BY_HOP:
                continue
            headers[name] = value
        headers["Host"] = host
        headers["Accept-Encoding"] = "identity"   # keep parsing simple
        if key:
            headers["Authorization"] = "Bearer " + key
            headers["x-api-key"] = key

        model = None
        sess = self.headers.get("x-opencode-session") or None
        try:
            doc = json.loads(body)
            if isinstance(doc, dict) and isinstance(doc.get("model"), str):
                model = doc["model"]
        except Exception:
            pass

        # BUSY_HOURS_MARK: rewrite before the allowlist check, so the substituted
        # model is the one validated and billed, and the body sent upstream matches
        # the "model" recorded for this turn.
        rewritten_from = None
        if route == "go" and method in ("POST", "PUT", "PATCH") and model:
            new_model, why = busy_rewrite(route, model, go_allowlist())
            if new_model:
                nb = busy_rewritten_body(body, new_model)
                if nb is not None:
                    rewritten_from, model, body = model, new_model, nb
                    with _LOCK:
                        STATE["rewrites"] += 1
                        STATE["last_rewrite"] = {
                            "ts": time.time(), "from": rewritten_from, "to": new_model,
                            "path": self.path[:120]}
                else:
                    why = "body_not_rewritable"
            if why not in ("rewritten", "outside_window", "not_a_match", "already_target"):
                with _LOCK:
                    STATE["rewrite_skips"][why] = STATE["rewrite_skips"].get(why, 0) + 1

        # GO_ALLOWLIST_MARK: refuse a disallowed model on the Go plan before it
        # can reach the upstream (and before any tokens are spent).
        if route == "go" and method in ("POST", "PUT", "PATCH") and model is not None:
            allowed_now = sorted(go_allowlist())
            if not allowed_now:
                with _LOCK:
                    STATE["requests"] += 1
                return self._json(403, {
                    "error": {
                        "type": "model_not_allowed",
                        "param": "model",
                        "code": "go_plan_restricted",
                        "message": "Go 套餐白名单当前不可用（中继配置缺失），"
                                   "暂时拒绝所有 Go 模型请求；自愈守护会恢复白名单。",
                        "allowed": [],
                    }
                })
            if not model_allowed(model):
                allowed = allowed_now
                with _LOCK:
                    STATE["requests"] += 1
                return self._json(403, {
                    "error": {
                        "type": "model_not_allowed",
                        "param": "model",
                        "code": "go_plan_restricted",
                        "message": "模型 %s 不在 OpenCode Go 套餐允许范围。"
                                   "Go 套餐仅允许：%s。"
                                   "其他模型请改用 Zen / GLM / MiMo 线路。"
                                   % (model, ", ".join(allowed)),
                        "allowed": allowed,
                    }
                })

        with _LOCK:
            STATE["requests"] += 1

        conn = None
        status = 0
        parser = UsageParser(bool(re.search(rb'"stream"\s*:\s*true', body)))
        client_gone = False
        try:
            conn = http.client.HTTPSConnection(
                host, UPSTREAM_PORT, timeout=CONNECT_TIMEOUT, context=SSL_CTX)
            conn.connect()
            try:
                conn.sock.settimeout(READ_TIMEOUT)
            except Exception:
                pass
            conn.putrequest(method, target, skip_host=True, skip_accept_encoding=True)
            for name, value in headers.items():
                conn.putheader(name, value)
            if body:
                conn.putheader("Content-Length", str(len(body)))
            conn.endheaders()
            if body:
                conn.send(body)
            resp = conn.getresponse()
        except Exception as exc:
            if conn:
                try:
                    conn.close()
                except Exception:
                    pass
            if not client_gone:
                self._json(502, {"error": {"type": "relay_tap_upstream",
                                           "message": f"{type(exc).__name__}: {exc}"}})
            return

        status = resp.status
        streamed_ctype = (resp.getheader("Content-Type") or "")
        if "text/event-stream" in streamed_ctype:
            parser.streaming = True
        if not model:
            model = resp.getheader("x-zen-model") or resp.getheader("x-opencode-upstream-model-id")

        # ── STREAM_FALLBACK_MARK: retry without streaming ───────────────────
        # Only when the client asked for a stream and the failure is the
        # streaming-only kind. The error body is small on a 400.
        if status >= 400 and (parser.streaming or b'"stream"' in body):
            try:
                err_body = resp.read(MAX_JSON_BODY)
            except Exception:
                err_body = b""
            if is_stream_only_failure(status, err_body):
                retry_body = non_stream_body(body)
                sse = None
                if retry_body is not None:
                    try:
                        tconn = http.client.HTTPSConnection(
                            UPSTREAM_HOST, UPSTREAM_PORT, timeout=CONNECT_TIMEOUT,
                            context=SSL_CTX)
                        tconn.connect()
                        try:
                            tconn.sock.settimeout(READ_TIMEOUT)
                        except Exception:
                            pass
                        tconn.putrequest(method, target, skip_host=True,
                                         skip_accept_encoding=True)
                        for hname, hvalue in headers.items():
                            if hname.lower() == "content-length":
                                continue
                            tconn.putheader(hname, hvalue)
                        tconn.putheader("Content-Length", str(len(retry_body)))
                        tconn.endheaders()
                        tconn.send(retry_body)
                        tresp = tconn.getresponse()
                        tpayload = tresp.read(MAX_JSON_BODY) if tresp.status == 200 else b""
                        tstatus = tresp.status
                        tresp.close()
                        tconn.close()
                        if tstatus == 200:
                            sse = completion_to_sse(tpayload)
                    except Exception:
                        sse = None
                if sse is not None:
                    # Serve the replayed stream to the client instead of the error.
                    try:
                        resp.close()
                    except Exception:
                        pass
                    if conn:
                        try:
                            conn.close()
                        except Exception:
                            pass
                    status = 200
                    parser.streaming = True
                    try:
                        self.send_response(200)
                        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                        self.send_header("Cache-Control", "no-cache")
                        if rewritten_from:
                            self.send_header("X-Relay-Rewritten-From", rewritten_from)
                            self.send_header("X-Relay-Rewritten-To", model or "")
                        self.send_header("Connection", "close")
                        self.send_header("Content-Length", str(len(sse)))
                        self.end_headers()
                        self.wfile.write(sse)
                        self.wfile.flush()
                    except Exception:
                        client_gone = True
                    parser.feed(sse)
                    try:
                        resp.close()
                    except Exception:
                        pass
                    usage = parser.result() or {}
                    nowdt = datetime.now(TZ)
                    raw = json.dumps({**usage, "injected": injected,
                                      "stream_fallback": True,
                                      **({"rewritten_from": rewritten_from} if rewritten_from else {})},
                                     ensure_ascii=False)[:400]
                    prompt, msgs = extract_turn(body)
                    if sess and (prompt or parser.reply_text()):
                        record_turn((time.time(), nowdt.strftime("%Y-%m-%d"),
                                     (sess or "")[:64], route,
                                     "anthropic" if "/messages" in self.path else "openai",
                                     (model or "unknown")[:80], msgs, len(body), status,
                                     prompt, parser.reply_text()))
                    record((
                        time.time(), nowdt.strftime("%Y-%m-%d"), nowdt.strftime("%Y-%m-%d %H"),
                        route, "anthropic" if "/messages" in self.path else "openai",
                        (model or "unknown")[:80], (sess or "")[:64] or None,
                        status, 1, round(time.time() - started, 3),
                        usage.get("input"), usage.get("output"), usage.get("cache_read"),
                        usage.get("cache_write"), usage.get("reasoning"), usage.get("total"),
                        1 if client_gone else 0, raw,
                    ))
                    self.close_connection = True
                    return
                # Retry failed: fall through and relay the original error body.
                self._pending_err_body = err_body

        # response head
        try:
            self.send_response(status)
            for name, value in resp.getheaders():
                if name.lower() in ("transfer-encoding", "connection", "content-length", "keep-alive"):
                    continue
                self.send_header(name, value)
            if rewritten_from:
                # BUSY_HOURS_MARK: the client still shows the model it asked for,
                # but must be able to see what actually answered.
                self.send_header("X-Relay-Rewritten-From", rewritten_from)
                self.send_header("X-Relay-Rewritten-To", model or "")
            self.send_header("Connection", "close")
            self.end_headers()
        except Exception:
            client_gone = True

        pending = getattr(self, "_pending_err_body", None)
        self._pending_err_body = None
        # ── UPSTREAM_ERROR_MARK: keep the upstream's own error text ─────────
        error_sink = [] if status >= 400 else None
        if not client_gone and pending is not None:
            if error_sink is not None:
                error_sink.append(pending[:4096])
            try:
                self.wfile.write(pending)
                self.wfile.flush()
            except Exception:
                client_gone = True
        if not client_gone:
            while True:
                if pending is not None:
                    pending = None
                    continue
                try:
                    chunk = resp.read(CHUNK)
                except Exception:
                    break
                if not chunk:
                    break
                try:
                    parser.feed(chunk)
                except Exception:
                    pass
                if error_sink is not None:
                    total = sum(len(piece) for piece in error_sink)
                    if total < 4096:
                        error_sink.append(chunk[:4096 - total])
                try:
                    self.wfile.write(chunk)
                    self.wfile.flush()
                except Exception:
                    client_gone = True
                    break
        try:
            resp.close()
        except Exception:
            pass
        if conn:
            try:
                conn.close()
            except Exception:
                pass

        usage = parser.result() or {}
        nowdt = datetime.now(TZ)
        raw_doc = {**usage, "injected": injected}
        if rewritten_from:
            # stats can show that a deepseek turn was actually served by mimo
            raw_doc["rewritten_from"] = rewritten_from
        if status >= 400:
            text = b"".join(error_sink or []).decode("utf-8", "replace").strip()
            raw_doc["upstream_status"] = status
            raw_doc["upstream_error"] = text[:1500] if text else "(empty body)"
        raw = json.dumps(raw_doc, ensure_ascii=False)[:2000]
        prompt, msgs = extract_turn(body)
        if sess and (prompt or parser.reply_text()):
            record_turn((time.time(), nowdt.strftime("%Y-%m-%d"), (sess or "")[:64],
                         route, "anthropic" if "/messages" in self.path else "openai",
                         (model or "unknown")[:80], msgs, len(body), status,
                         prompt, parser.reply_text()))
        record((
            time.time(), nowdt.strftime("%Y-%m-%d"), nowdt.strftime("%Y-%m-%d %H"),
            route, "anthropic" if "/messages" in self.path else "openai",
            (model or "unknown")[:80], (sess or "")[:64] or None,
            status, 1 if parser.streaming else 0, round(time.time() - started, 3),
            usage.get("input"), usage.get("output"), usage.get("cache_read"),
            usage.get("cache_write"), usage.get("reasoning"), usage.get("total"),
            1 if client_gone else 0, raw,
        ))
        self.close_connection = True


def health_loop(stop):
    while not stop.is_set():
        try:
            os.makedirs(os.path.dirname(HEALTH_PATH), exist_ok=True)
            tmp = HEALTH_PATH + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump({"ok": STATE["error"] is None, "rows": STATE["rows"],
                           "requests": STATE["requests"], "last": STATE["last"],
                           "error": STATE["error"], "uptime_s": int(time.time() - STATE["started"]),
                           "busy_hours": {
                               "rewrites": STATE["rewrites"],
                               "last": STATE["last_rewrite"],
                               "skips": STATE["rewrite_skips"],
                               "switch_off": busy_off(),
                               "window_active": busy_window_active(busy_conf(), datetime.now(TZ))},
                           "checked_at": datetime.now(TZ).isoformat(timespec="seconds")},
                          f, ensure_ascii=False, indent=2)
            os.replace(tmp, HEALTH_PATH)
            os.chmod(HEALTH_PATH, 0o644)
            # keep the transcript archive bounded like the request log
            if time.time() - prune_last[0] > 86400:
                prune_last[0] = time.time()
                cutoff = (datetime.now(TZ) - timedelta(days=180)).strftime("%Y-%m-%d")
                with _db_lock:
                    conn = db()
                    try:
                        conn.execute("DELETE FROM session_turns WHERE day < ?", (cutoff,))
                        conn.execute("DELETE FROM session_turns WHERE id NOT IN"
                                     " (SELECT id FROM session_turns ORDER BY ts DESC LIMIT 100000)")
                        conn.commit()
                    finally:
                        conn.close()
        except Exception:
            pass
        stop.wait(15)


def main():
    init_db()
    # WAL keeps dashboard readers from blocking the ingest writer
    try:
        conn = db()
        conn.execute("PRAGMA journal_mode=WAL")
        conn.commit()
        conn.close()
    except Exception:
        pass
    stop = threading.Event()
    threading.Thread(target=health_loop, args=(stop,), daemon=True).start()
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler)
    server.daemon_threads = True
    print(f"usage tap listening on {LISTEN_HOST}:{LISTEN_PORT}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()


if __name__ == "__main__":
    main()
