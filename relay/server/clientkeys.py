#!/usr/bin/env python3
"""Per-distribution relay keys: issue, revoke, and token ceilings per key.

The relay used to have exactly one shared token. Anyone who knew it could spend
the whole plan, and nobody could be told to stop. This module is the store of
client-facing keys (/etc/relay/client-keys.json): a client is handed one of
these, the usage tap validates it on every request and charges the tokens it
consumes against that key's hour / week / month ceilings, and only then is the
configured upstream credential substituted for it. The client never sees an
official key and cannot use one.

Two readers, one writer:
  * usagetap.py  -- gate(). Re-reads the store by mtime, so issuing or revoking
                    a key takes effect on the next request with no restart.
  * collector.py -- the panel API (/api/client-keys) and this file's CLI.

Failures are handled in the direction the relay already uses: a broken store
file keeps the last good copy in memory; if there is no copy at all (a tap
restart onto a corrupt file) the gate falls back to whatever credential the
edge accepted, so traffic survives with quotas temporarily off. Nothing here is
ever logged in the clear -- a key is identified by the sha256 prefix that is
already stored in usage_events.client_key.
"""
import argparse
import functools
import hashlib
import json
import os
import re
import secrets
import sqlite3
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone

STORE_PATH = "/etc/relay/client-keys.json"
EDGE_INCLUDE = "/etc/relay/relay-auth.conf"
LEGACY_ENV = "/etc/relay/relay.env"
DB_PATH = "/var/lib/relay/relay.db"
KEY_PREFIX = "rk_"
TZ = timezone(timedelta(hours=8))
WINDOWS = ("hour", "week", "month")
WINDOW_LABEL = {"hour": "近 1 小时", "week": "本周", "month": "本月"}
# A rejected request must not lose the client's place in line; this is how long
# a key's consumed totals are cached before the tap asks the database again.
USAGE_TTL = 15.0

_LOCK = threading.Lock()
# Mutations and the nginx reload must not block the tap's per-request store read:
# `nginx -t` takes about a second, and _LOCK is held on the request path.
_WRITE_LOCK = threading.RLock()
_store_cache = {"mtime": None, "doc": None, "loaded": False}
_usage_cache = {}


# ── records ─────────────────────────────────────────────────────────────────

def fingerprint(secret):
    """The id this key gets in usage_events.client_key (same hash the tap uses)."""
    return hashlib.sha256(secret.encode("utf-8", "replace")).hexdigest()[:16]


def new_secret():
    return KEY_PREFIX + secrets.token_hex(16)


def mask(secret):
    if not secret:
        return ""
    if len(secret) <= 12:
        return secret[:4] + "…"
    return "%s…%s" % (secret[:8], secret[-6:])


def legacy_token():
    """The original shared token, kept as an always-usable master entry."""
    try:
        with open(LEGACY_ENV, encoding="utf-8") as f:
            m = re.search(r'set\s+\$relay_token\s+"([^"]+)"', f.read())
        return m.group(1) if m else ""
    except OSError:
        return ""


def empty_doc():
    return {"version": 1, "updated_at": None, "keys": []}


def _now_iso():
    return datetime.now(TZ).isoformat(timespec="seconds")


def _fresh(secret, label="", note="", quota=None, internal=False):
    limits = {}
    for w in WINDOWS:
        v = (quota or {}).get(w)
        try:
            v = int(v)
        except (TypeError, ValueError):
            v = 0
        limits[w] = v if v and v > 0 else 0
    return {
        "id": "ck_" + secrets.token_hex(3),
        "label": (label or "").strip()[:60],
        "note": (note or "").strip()[:200],
        "key": secret,
        "fingerprint": fingerprint(secret),
        "enabled": True,
        "internal": bool(internal),
        "quota": limits,
        "created_at": _now_iso(),
    }


# ── store I/O ───────────────────────────────────────────────────────────────

def _backups(doc_path):
    keep = 5
    try:
        names = sorted(n for n in os.listdir(os.path.dirname(doc_path))
                       if n.startswith(os.path.basename(doc_path) + ".bak-"))
    except OSError:
        return
    for stale in names[:-keep] if len(names) > keep else []:
        try:
            os.remove(os.path.join(os.path.dirname(doc_path), stale))
        except OSError:
            pass


def read_doc():
    """Current store, or None when it cannot be read/parsed."""
    try:
        with open(STORE_PATH, encoding="utf-8") as f:
            doc = json.load(f)
    except (OSError, ValueError):
        return None
    if not isinstance(doc, dict) or not isinstance(doc.get("keys"), list):
        return None
    return doc


def write_doc(doc):
    """Atomic 0600 write, with the previous version kept as a timestamped .bak."""
    doc["updated_at"] = _now_iso()
    os.makedirs(os.path.dirname(STORE_PATH), exist_ok=True)
    prev = None
    if os.path.exists(STORE_PATH):
        try:
            with open(STORE_PATH, encoding="utf-8") as f:
                prev = f.read()
        except OSError:
            prev = None
    stamp = datetime.now(TZ).strftime("%Y%m%d%H%M%S")
    bak = "%s.bak-%s" % (STORE_PATH, stamp)
    if prev is not None:
        with open(bak, "w", encoding="utf-8") as f:
            f.write(prev)
        os.chmod(bak, 0o600)
    tmp = STORE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.chmod(tmp, 0o600)
    os.replace(tmp, STORE_PATH)
    _backups(STORE_PATH)
    return os.path.basename(bak)


def load_doc():
    """Store for the request path: mtime-cached, last good copy kept on errors."""
    try:
        mtime = os.stat(STORE_PATH).st_mtime
    except OSError:
        return _store_cache["doc"] or empty_doc()
    with _LOCK:
        if _store_cache["mtime"] == mtime and _store_cache["doc"] is not None:
            return _store_cache["doc"]
        doc = read_doc()
        if doc is not None:
            _store_cache.update({"mtime": mtime, "doc": doc, "loaded": True})
            return doc
        # Unreadable but changed: keep serving the previous copy forever rather
        # than locking every client out because of one bad write.
        _store_cache["mtime"] = mtime
        return _store_cache["doc"] or empty_doc()


def store_loaded():
    """Has the store parsed at least once in this process? The gate uses it to tell
    "this key was revoked" apart from "the store was never readable here"."""
    return bool(_store_cache["loaded"])


def get_by_id(doc, key_id):
    for rec in doc.get("keys") or []:
        if rec.get("id") == key_id:
            return rec
    return None


def by_fingerprint(doc):
    out = {}
    for rec in doc.get("keys") or []:
        fp = rec.get("fingerprint")
        if fp:
            out[fp] = rec
    return out


# ── the nginx edge gate ─────────────────────────────────────────────────────
# nginx has to know which credentials exist, because the watchdog can bypass the
# usage tap (set_target.py direct) and the static /models-*.json files are served
# without it. If identity were only checked in the tap, a bypass would leave
# "any non-empty bearer" standing between the internet and the official key.
# So the enabled keys are rendered into the include nginx already includes in
# every credential location, and revoking a key reloads nginx -- the same way
# keystore.py already swaps the upstream credential.

SAFE_SECRET = re.compile(r"^[A-Za-z0-9_.\-]{16,200}$")


def usable_secret(secret):
    """Can this credential be inlined into an nginx config safely?"""
    return bool(secret) and bool(SAFE_SECRET.match(secret))


def render_include(doc=None):
    doc = doc if doc is not None else (read_doc() or empty_doc())
    lines = [
        "# Relay credential check, included inside every credentialed location.",
        "#",
        "# GENERATED from /etc/relay/client-keys.json -- do not edit by hand.",
        "# Manage it from the panel (OpenCode 中继 -> 分发 Key) or with",
        "#   python3 /opt/relay/clientkeys.py issue|revoke|enable|delete",
        "#",
        "# Accepted client credentials:",
        "#   Authorization: Bearer <key>   <- opencode clients on any machine",
        "#   x-api-key: <key>              <- Anthropic-style callers",
        "# Whatever arrives is replaced upstream by /etc/relay/upstream-auth.conf,",
        "# so a client never holds an official key. Because that replacement happens",
        "# in nginx itself, the client's own credential is forwarded to the usage tap",
        "# under two headers the tap strips before it proxies on -- without them the",
        "# tap could not tell which key made a request, and no ceiling could be billed.",
        "proxy_set_header X-Relay-Client-Auth $http_authorization;",
        "proxy_set_header X-Relay-Client-Apikey $http_x_api_key;",
        "set $relay_ok 0;",
    ]
    skipped = []
    for rec in doc.get("keys") or []:
        secret = rec.get("key") or ""
        if not rec.get("enabled", True):
            continue
        if not usable_secret(secret):
            skipped.append(rec.get("id") or mask(secret))
            continue
        lines.append('if ($http_authorization = "Bearer %s") { set $relay_ok 1; }' % secret)
        lines.append('if ($http_x_api_key = "%s") { set $relay_ok 1; }' % secret)
    if skipped:
        lines.append("# skipped (not safe to inline): %s" % ", ".join(skipped))
    if len(lines) == 1:
        # No enabled key at all. Emptying the list would lock every client out
        # including the master token, so refuse rather than render a 401-always.
        return None, "没有可用的启用 key，拒绝下发空的凭据名单"
    lines.append("if ($relay_ok = 0) { return 401; }")
    return "\n".join(lines) + "\n", "ok"


def apply_include(doc=None):
    """Write the include and reload nginx, rolling back if the config test fails."""
    body, why = render_include(doc)
    if body is None:
        return False, why
    with _WRITE_LOCK:
        try:
            with open(EDGE_INCLUDE, encoding="utf-8") as f:
                old = f.read()
        except OSError:
            old = None
        if old == body:
            return True, "unchanged"
        tmp = EDGE_INCLUDE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(body)
        os.chmod(tmp, 0o600)
        os.replace(tmp, EDGE_INCLUDE)
        test = subprocess.run(["nginx", "-t"], capture_output=True, text=True)
        if test.returncode != 0:
            if old is not None:
                with open(EDGE_INCLUDE, "w", encoding="utf-8") as f:
                    f.write(old)
            return False, "nginx -t 未通过，凭据名单已回滚：" + (test.stderr or test.stdout or "").strip()[-300:]
        reload_ = subprocess.run(["systemctl", "reload", "nginx"], capture_output=True, text=True)
        if reload_.returncode != 0:
            if old is not None:
                with open(EDGE_INCLUDE, "w", encoding="utf-8") as f:
                    f.write(old)
            return False, "nginx reload 失败，已回滚：" + (reload_.stderr or reload_.stdout or "").strip()[-300:]
    keys = (doc if doc is not None else read_doc()) or empty_doc()
    n = sum(1 for r in (keys.get("keys") or []) if r.get("enabled", True))
    return True, "凭据名单已下发（%d 枚启用），nginx 已平滑重载" % n


# ── quota windows ───────────────────────────────────────────────────────────

def window_starts(now=None):
    """(start_epoch, reset_hint_epoch) for each ceiling window.

    hour is a rolling window: relief comes as the oldest consumption ages out, so
    its reset hint is that oldest request. week and month are calendar-aligned in
    Beijing time (Monday 00:00 / the 1st 00:00) and reset on the dot.
    """
    dt = now or datetime.now(TZ)
    epoch = dt.timestamp()
    monday = (dt - timedelta(days=dt.weekday())).replace(
        hour=0, minute=0, second=0, microsecond=0)
    first = dt.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    return {
        "hour": {"since": epoch - 3600.0, "reset": epoch + 3600.0, "kind": "rolling"},
        "week": {"since": monday.timestamp(),
                 "reset": (monday + timedelta(days=7)).timestamp(), "kind": "calendar"},
        "month": {"since": first.timestamp(),
                  "reset": (first.replace(day=28) + timedelta(days=7)).replace(day=1).timestamp(),
                  "kind": "calendar"},
    }


def iso(epoch):
    return datetime.fromtimestamp(epoch, TZ).isoformat(timespec="seconds")


def _sum_expr(alias="used"):
    """total_tokens when the upstream reported it, else input+output."""
    return ("COALESCE(SUM(CASE WHEN total_tokens IS NOT NULL AND total_tokens>0"
            " THEN total_tokens ELSE COALESCE(input_tokens,0)+COALESCE(output_tokens,0) END),0) AS %s" % alias)


def consumed(fp, since):
    """(tokens charged to this key since `since`, oldest charging request ts).

    Read with an ordinary connection, not mode=ro: this database is in WAL and
    the quota check sits on the request path, where a spurious "unable to open"
    would quietly turn the ceilings off.
    """
    try:
        conn = sqlite3.connect(DB_PATH, timeout=10)
        try:
            conn.execute("PRAGMA busy_timeout=8000")
            row = conn.execute(
                "SELECT %s, MIN(CASE WHEN COALESCE(total_tokens,0)>0"
                " OR COALESCE(input_tokens,0)+COALESCE(output_tokens,0)>0 THEN ts END)"
                " FROM usage_events WHERE client_key=? AND ts>=?" % _sum_expr(),
                (fp, since)).fetchone()
        finally:
            conn.close()
    except sqlite3.Error:
        return None, None
    return int(row[0] or 0), row[1]


def consumed_cached(fp, since):
    key = (fp, int(since))
    now = time.time()
    with _LOCK:
        hit = _usage_cache.get(key)
        if hit and now - hit[0] < USAGE_TTL:
            return hit[1], hit[2]
    used, first_ts = consumed(fp, since)
    with _LOCK:
        if len(_usage_cache) > 4096:
            _usage_cache.clear()
        _usage_cache[key] = (now, used, first_ts)
    return used, first_ts


def quota_state(rec, starts=None, now=None):
    """Per-window {limit, used, remaining, over, resets_at} for one key record."""
    starts = starts or window_starts(now)
    fp = rec.get("fingerprint") or ""
    limits = rec.get("quota") or {}
    out = {}
    for w in WINDOWS:
        limit = int(limits.get(w) or 0)
        win = starts[w]
        entry = {"limit": limit, "used": None, "remaining": None, "over": False,
                 "kind": win["kind"], "resets_at": iso(win["reset"]), "since": iso(win["since"])}
        if not limit or not fp:
            out[w] = entry
            continue
        used, first_ts = consumed_cached(fp, win["since"])
        entry["used"] = used
        if used is None:
            entry["error"] = "用量查询失败"
        else:
            entry["remaining"] = max(0, limit - used)
            entry["over"] = used >= limit
            if win["kind"] == "rolling" and first_ts:
                # In a rolling window the first relief is when the oldest
                # counted request falls out of it, not at a fixed clock time.
                entry["resets_at"] = iso(first_ts + 3600.0)
        out[w] = entry
    return out


def over_window(rec, starts=None, now=None):
    """(window, state) for the tightest window that is at its ceiling, else None."""
    state = quota_state(rec, starts, now)
    for w in WINDOWS:
        if state[w].get("over"):
            return w, state[w], state
    return None


# ── the gate used by the tap ────────────────────────────────────────────────

class Gate(object):
    """Answer for one request: allowed key record, or the reason it is refused."""

    def __init__(self, rec, deny=None, detail=None, state=None):
        self.rec = rec
        self.deny = deny            # ("invalid"|"disabled"|"quota"|"no_store", http_status)
        self.detail = detail or {}
        self.state = state or {}

    @property
    def ok(self):
        return self.deny is None


def gate(token, enforce_quota=True, method="POST"):
    """Check the credential this request presents against the store.

    `token` is the client's own key as it arrived, not the Authorization header the
    tap forwards: nginx replaces that one with the upstream credential before the
    request ever reaches the tap. Auth applies to every method; the ceilings only
    to the ones that can spend tokens, so an out-of-quota client can still list
    models.
    """
    if not token:
        return Gate(None, ("invalid", 401), {"error": "missing credential"})
    fp = fingerprint(token)
    doc = load_doc()
    rec = by_fingerprint(doc).get(fp)
    if rec is None:
        # A store that has never loaded (fresh boot, wiped file) must not be
        # mistaken for "this key is revoked": accept whatever the edge accepted.
        if not _store_cache["loaded"]:
            return Gate(None, None, {"fallback": "no store; edge-accepted"})
        return Gate(None, ("invalid", 401), {"error": "unknown key"})
    if not rec.get("enabled", True):
        return Gate(rec, ("disabled", 403), {"error": "key revoked"})
    if enforce_quota and method in ("POST", "PUT", "PATCH") and not rec.get("internal"):
        hit = over_window(rec)
        if hit:
            w, win, state = hit
            return Gate(rec, ("quota", 429), {
                "error": "quota exceeded", "window": w, "label": WINDOW_LABEL[w],
                "limit": win["limit"], "used": win["used"],
                "resets_at": win["resets_at"], "kind": win["kind"]}, state)
    return Gate(rec)


def block_body(g):
    """The JSON error body a refused request gets, in the shape clients parse."""
    kind, status = g.deny
    reason, why = g.detail.get("error", ""), g.detail.get("error", "")
    if kind == "invalid":
        reason, why = "authentication_error", "中继凭据无效：这枚 key 不存在或已被删除。请向发放人索取新的 key。"
    elif kind == "disabled":
        reason, why = "key_disabled", "这枚中继 key 已被停用。需要恢复请找发放人在面板「分发 Key」里重新启用。"
    elif kind == "quota":
        w = g.detail.get("window")
        resets = str(g.detail.get("resets_at", "")).replace("T", " ")[:16]
        reason = "relay_key_quota_exceeded"
        relief = ("额度按滚动 1 小时统计，最早的一笔会在 %s 释放出额度" % resets
                  if g.detail.get("kind") == "rolling"
                  else "%s 窗口在 %s 整点重置" % (WINDOW_LABEL.get(w, w), resets))
        why = ("这枚中继 key 的%s额度已用尽：已用 %s / 上限 %s token。%s；"
               "需要更多请找发放人在面板「分发 Key」调高上限，或换一枚 key。" % (
                   WINDOW_LABEL.get(w, w),
                   format(int(g.detail.get("used") or 0), ","),
                   format(int(g.detail.get("limit") or 0), ","), relief))
    elif kind == "no_store":
        reason, why = "relay_store_unavailable", "中继的 key 仓库暂不可用，请稍后重试。"
    return status, {"error": {"type": reason, "code": reason, "message": why,
                             "window": g.detail.get("window"),
                             "limit": g.detail.get("limit"),
                             "used": g.detail.get("used"),
                             "resets_at": g.detail.get("resets_at")}}


# ── panel-facing views ──────────────────────────────────────────────────────

def public_view(rec, state=None):
    return {
        "id": rec.get("id"),
        "label": rec.get("label") or "",
        "note": rec.get("note") or "",
        "masked": mask(rec.get("key") or ""),
        "fingerprint": rec.get("fingerprint"),
        "enabled": bool(rec.get("enabled", True)),
        "internal": bool(rec.get("internal")),
        "quota": rec.get("quota") or {},
        "windows": state or {},
        "created_at": rec.get("created_at"),
    }


def list_status():
    doc = load_doc()
    starts = window_starts()
    rows, totals = [], {w: {"used": 0, "limit": 0} for w in WINDOWS}
    for rec in doc.get("keys") or []:
        state = quota_state(rec, starts)
        for w in WINDOWS:
            if state[w].get("used"):
                totals[w]["used"] += state[w]["used"]
            if state[w].get("limit"):
                totals[w]["limit"] += state[w]["limit"]
        rows.append(public_view(rec, state))
    return {"ok": True, "keys": rows, "totals": totals,
            "labels": WINDOW_LABEL, "window_starts": {w: iso(starts[w]["since"]) for w in WINDOWS},
            "reset_at": {w: iso(starts[w]["reset"]) for w in WINDOWS},
            "store": STORE_PATH, "updated_at": doc.get("updated_at")}


# ── mutations ───────────────────────────────────────────────────────────────

def serialized(fn):
    """One panel write at a time: read-modify-write of the store must not interleave."""
    @functools.wraps(fn)
    def inner(*args, **kwargs):
        with _WRITE_LOCK:
            return fn(*args, **kwargs)
    return inner


@serialized
def create(label="", note="", quota=None, internal=False):
    doc = read_doc() or empty_doc()
    secret = new_secret()
    rec = _fresh(secret, label, note, quota, internal)
    doc["keys"] = (doc.get("keys") or []) + [rec]
    backup = write_doc(doc)
    return rec, backup


@serialized
def update(key_id, fields):
    doc = read_doc()
    if doc is None:
        return False, "key 仓库读取失败，未做任何更改"
    rec = get_by_id(doc, key_id)
    if rec is None:
        return False, "找不到这枚 key（%s）" % key_id
    if "label" in fields:
        rec["label"] = str(fields["label"] or "").strip()[:60]
    if "note" in fields:
        rec["note"] = str(fields["note"] or "").strip()[:200]
    if "enabled" in fields:
        rec["enabled"] = bool(fields["enabled"])
    if "quota" in fields:
        q = fields["quota"] or {}
        current = dict(rec.get("quota") or {})
        limits = {}
        for w in WINDOWS:
            # A window the caller did not mention keeps its ceiling: a partial
            # payload must never silently turn a limit into "unlimited".
            try:
                v = int(q.get(w, current.get(w, 0)) or 0)
            except (TypeError, ValueError):
                return False, "%s 额度必须是整数（token 数，0=不限）" % WINDOW_LABEL[w]
            if v < 0:
                return False, "%s 额度不能是负数" % WINDOW_LABEL[w]
            limits[w] = v
        rec["quota"] = limits
    backup = write_doc(doc)
    return True, "已保存" + ("（备份 %s）" % backup if backup else "")


@serialized
def remove(key_id):
    doc = read_doc()
    if doc is None:
        return False, "key 仓库读取失败，未做任何更改"
    rec = get_by_id(doc, key_id)
    if rec is None:
        return False, "找不到这枚 key（%s）" % key_id
    if rec.get("internal"):
        return False, "主令牌是兜底入口，不能删除；要收紧就把它从客户端配置里撤掉"
    doc["keys"] = [r for r in doc["keys"] if r.get("id") != key_id]
    backup = write_doc(doc)
    return True, "已删除 %s（备份 %s）" % (mask(rec.get("key") or ""), backup)


@serialized
def seed_master(note="主令牌（旧共享令牌）"):
    """Make the historical shared token a store entry so it keeps working."""
    doc = read_doc() or empty_doc()
    token = legacy_token()
    if not token:
        return False, "%s 里读不到旧共享令牌" % LEGACY_ENV
    if any(r.get("key") == token for r in doc.get("keys") or []):
        return True, "主令牌已在仓库里，未重复添加"
    rec = _fresh(token, note, "上线分发 key 之前的全局令牌；保留为兜底入口，不受额度限制",
                 internal=True)
    rec["id"] = "ck_master"
    doc["keys"] = [rec] + (doc.get("keys") or [])
    write_doc(doc)
    return True, "已把旧共享令牌登记为主令牌条目（内部、不限额）"


# ── CLI (the way to act when the panel itself is the thing that is broken) ───

def main(argv=None):
    p = argparse.ArgumentParser(description="relay client-facing key store")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list")
    ci = sub.add_parser("issue")
    ci.add_argument("label")
    ci.add_argument("--hour", type=int, default=0)
    ci.add_argument("--week", type=int, default=0)
    ci.add_argument("--month", type=int, default=0)
    ci.add_argument("--note", default="")
    ci.add_argument("--internal", action="store_true")
    for name in ("revoke", "enable", "delete"):
        sp = sub.add_parser(name)
        sp.add_argument("id")
    sl = sub.add_parser("limit")
    sl.add_argument("id")
    sl.add_argument("--hour", type=int)
    sl.add_argument("--week", type=int)
    sl.add_argument("--month", type=int)
    sub.add_parser("seed-master")
    sub.add_parser("render")
    sub.add_parser("apply")
    a = p.parse_args(argv)

    def done(ok, msg, changed=True):
        """Report a mutation, then push the credential list to nginx if it worked."""
        print(("OK  " if ok else "FAIL ") + msg)
        if ok and changed:
            aok, amsg = apply_include()
            print(("OK  " if aok else "WARN ") + amsg)
        return 0 if ok else 1

    if a.cmd == "list":
        for r in list_status()["keys"]:
            used = " ".join("%s=%s/%s" % (w, format((r["windows"][w].get("used") or 0), ","),
                                          format(r["windows"][w]["limit"], ",") if r["windows"][w]["limit"] else "不限")
                            for w in WINDOWS)
            print("%s %-9s %-14s %-22s %s %s" % (
                "ON " if r["enabled"] else "OFF", r["id"], r["masked"], r["label"], used,
                "内部" if r["internal"] else ""))
    elif a.cmd == "issue":
        rec, _ = create(a.label, a.note, {"hour": a.hour, "week": a.week, "month": a.month},
                        a.internal)
        print("id    : %s" % rec["id"])
        print("label : %s" % rec["label"])
        ok, msg = apply_include()
        print(("OK  " if ok else "WARN ") + msg)
        print("key   : %s   (只显示这一次，分发后请自行保存)" % rec["key"])
        return 0 if ok else 1
    elif a.cmd in ("revoke", "enable"):
        return done(*update(a.id, {"enabled": a.cmd == "enable"}))
    elif a.cmd == "limit":
        doc = read_doc() or empty_doc()
        rec = get_by_id(doc, a.id)
        if rec is None:
            print("找不到 %s" % a.id)
            return 1
        # Windows left out of the command line keep their current ceiling.
        limits = dict(rec.get("quota") or {})
        limits.update({w: getattr(a, w) for w in WINDOWS if getattr(a, w) is not None})
        return done(*update(a.id, {"quota": limits}))
    elif a.cmd == "delete":
        return done(*remove(a.id))
    elif a.cmd == "seed-master":
        return done(*seed_master())
    elif a.cmd == "render":
        body, why = render_include()
        if body is None:
            print("FAIL " + why)
            return 1
        # Never echo a credential to a terminal during a deploy: show the shape only.
        print("\n".join(re.sub(r'"(Bearer )?[A-Za-z0-9_.\-]{16,200}"', '"<credential>"', ln)
                        if ln.startswith("if (") else ln for ln in body.splitlines()))
        return 0
    elif a.cmd == "apply":
        ok, msg = apply_include()
        print(("OK  " if ok else "FAIL ") + msg)
        return 0 if ok else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
