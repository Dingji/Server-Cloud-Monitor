#!/usr/bin/env python3
"""OpenCode relay stats: nginx access-log collector, SQLite storage and JSON API.

Stdlib only. One ingest thread tails the nginx log and inserts rows into SQLite;
a threaded HTTP server serves the dashboard and JSON API on 127.0.0.1:8787.
nginx exposes it at /stats/ behind HTTP basic auth.

Log format (relay_json v2, 18 pipe-separated fields):
    time|ip|status|req_len|bytes_sent|rt|upstream_rt|method|uri|model|
    upstream_addr|ttfb|upstream_status|conn_requests|session|content_type|proto|ua
Older 12-field lines are still parsed (new columns stay NULL).
"""
import csv
import glob
import io
import json
import os
import re
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from contextlib import closing
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
try:
    from chinese_calendar import is_workday
    HAS_CN_CAL = True
except Exception:
    HAS_CN_CAL = False

    def is_workday(d):
        return d.weekday() < 5
try:
    from chinese_calendar import get_holiday_detail
except Exception:
    def get_holiday_detail(d):
        return (None, None)

# Multi-key store: quota-aware selection of the upstream OpenCode key.
# Imported defensively so a missing module can never stop the collector.
try:
    import keystore
except Exception as _ks_exc:  # pragma: no cover
    keystore = None
    print(f"keystore unavailable: {_ks_exc}", file=sys.stderr)

DATA_DIR = "/var/lib/relay"
PUBLIC_DIR = os.path.join(DATA_DIR, "public")
DB_PATH = os.path.join(DATA_DIR, "relay.db")
STATUS_PATH = os.path.join(DATA_DIR, "public", "models-status.json")
QUOTA_PATH = os.path.join(DATA_DIR, "public", "quota.json")
SELFHEAL_PATH = os.path.join(DATA_DIR, "public", "selfheal.json")
STATE_PATH = os.path.join(DATA_DIR, "ingest.state")
UPSTREAM_AUTH = "/etc/relay/upstream-auth.conf"
MODELCHECK_PY = "/opt/relay/modelcheck.py"
USAGE_URL = "https://opencode.ai/zen/go/v1/usage"
MODELS_URL = "https://opencode.ai/zen/go/v1/models"
STATIC_DIR = "/opt/relay/static"
LOG_PATH = "/var/log/nginx/opencode-relay.access.log"
PROBE_UA = "relay-modelcheck"
HOST, PORT = "127.0.0.1", 8787
TZ = timezone(timedelta(hours=8))
RETENTION_DAYS = 180
BATCH = 400
QUOTA_TTL = 60  # seconds before a cached quota read is refreshed
MODELCHECK_LOCK = threading.Lock()

# ── busy-hours policy API ──────────────────────────────────────────────────
# The policy itself lives in the usage tap (usagetap.py, BUSY_HOURS_MARK): it is
# the component in the request path. The collector only reads and writes the
# policy file, so every window decision shown here is computed by the same code
# that rewrites real traffic -- a second implementation here would let the UI and
# the relay disagree about a request.
BUSY_CONF_PATH = "/etc/relay/busy-hours.json"
BUSY_OFF_FLAG = "/var/lib/relay/busy-hours.off"
BUSY_TAP_PATH = "/opt/relay/usagetap.py"
GO_ALLOW_PATH = "/etc/relay/go-allowlist.json"
TAP_TARGET_GO = "/etc/relay/upstream-target-go.conf"
TAP_HEALTH_PATH = os.path.join(PUBLIC_DIR, "usagetap.json")
POLICY_ROUTES = ("go", "zen", "glm", "mimo")
POLICY_MAX_WINDOWS = 8
HHMM_RE = re.compile(r"^([01]?\d|2[0-3]):([0-5]\d)$")
MODEL_RE = re.compile(r"^[a-z0-9][a-z0-9._+-]{1,63}$")

_tap_mod = {"mod": None, "error": None, "tried": False}


def tap_policy():
    """The usagetap module, or (None, reason). Import failure must not break stats."""
    if _tap_mod["tried"] and _tap_mod["mod"] is not None:
        return _tap_mod["mod"], None
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location("relay_usagetap", BUSY_TAP_PATH)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        _tap_mod.update(mod=mod, error=None, tried=True)
        return mod, None
    except Exception as exc:
        _tap_mod.update(mod=None, error="%s: %s" % (type(exc).__name__, exc), tried=True)
        return None, _tap_mod["error"]


def read_json_file(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None
    except Exception:
        return "invalid"


def busy_phase(config, data, mod_ok):
    """闲时 / 忙时 verdict for the UI.

    Computed here, next to the tap's own window judgement, and never on the client:
    'busy' must mean the same thing this page says and what rewrites the traffic.
    """
    at = data.get("at")
    if not mod_ok or "window_active_now" not in data:
        return {"state": "unknown", "label": "未知", "in_effect": False, "at": at,
                "reason": "取不到 tap 的时段判定（模块未载入或判定异常），页面不猜。"}
    active = bool(data.get("window_active_now"))
    win = " · ".join("%s-%s" % (w.get("start"), w.get("end")) for w in (config.get("windows") or []))
    if not config.get("enabled", True):
        ph = {"state": "idle", "label": "闲时", "in_effect": False, "at": at,
              "reason": "策略已停用（enabled=false）" + ("，尽管此刻落在窗口内（%s）" % win if active else "")}
    elif data.get("switch_off"):
        ph = {"state": "idle", "label": "闲时", "in_effect": False, "at": at,
              "reason": "临时停用标记生效" + ("，尽管此刻落在窗口内（%s）" % win if active else "")}
    elif not active:
        ph = {"state": "idle", "label": "闲时", "in_effect": False, "at": at,
              "reason": "此刻不在生效窗口内" + ("（%s）" % win if win else "（未配置窗口）")}
    else:
        prefix = "/".join(config.get("rewrite_prefixes") or []) or "-"
        ph = {"state": "busy", "label": "忙时", "in_effect": True, "at": at,
              "reason": "在生效窗口内（%s）· %s → %s" % (win, prefix, config.get("target_model") or "-")}
    # a year the holiday library does not cover can only be judged as plain Mon-Fri;
    # saying "忙时" about a public holiday without that caveat would be a lie
    cal = data.get("calendar") or {}
    if cal and not cal.get("available", True):
        ph["year_data_available"] = False
        ph["reason"] = ph["reason"] + "。" + (cal.get("note") or "")
    return ph


def busy_state(at=None):
    """Policy snapshot for the UI: config + switch + whether the tap is in the path."""
    mod, mod_err = tap_policy()
    raw = read_json_file(BUSY_CONF_PATH)
    file_missing = raw is None
    file_broken = raw == "invalid"
    if mod is not None:
        # the tap's own reader: defaults when the file is absent, last good doc when
        # it is unparsable. Reading it here would show a policy the tap is not using.
        doc = mod.busy_conf()
    else:
        doc = raw if isinstance(raw, dict) else {}
    allow = read_json_file(GO_ALLOW_PATH)
    allowed = sorted(str(m) for m in (allow.get("models") or []) if m) if isinstance(allow, dict) else []
    health = read_json_file(TAP_HEALTH_PATH)
    if not isinstance(health, dict):
        health = {}
    tap_state = health.get("busy_hours") or {}
    try:
        with open(TAP_TARGET_GO, encoding="utf-8") as f:
            target_in_path = "127.0.0.1:8790" in f.read()
    except OSError:
        target_in_path = False

    config = {
        "enabled": bool(doc.get("enabled", True)),
        "weekdays_only": bool(doc.get("weekdays_only", True)),
        "route": str(doc.get("route", "go")),
        "target_model": str(doc.get("target_model", "")),
        "rewrite_prefixes": [str(p) for p in (doc.get("rewrite_prefixes") or [])],
        "windows": [{"start": str(w.get("start", "")), "end": str(w.get("end", ""))}
                    for w in (doc.get("windows") or []) if isinstance(w, dict)],
        "utc_offset_minutes": int(doc.get("utc_offset_minutes", 480) or 0),
    }
    out = {
        "ok": True,
        "config_path": BUSY_CONF_PATH,
        "switch_path": BUSY_OFF_FLAG,
        "config_file_missing": file_missing,
        "config_file_invalid": file_broken,
        "config": config,
        "switch_off": os.path.exists(BUSY_OFF_FLAG),
        "go_allowlist": allowed,
        "policy_routes": list(POLICY_ROUTES),
        "tap": {
            "module_available": mod is not None,
            "module_error": mod_err,
            "in_request_path": target_in_path,
            "rewrites": tap_state.get("rewrites"),
            "last_rewrite": tap_state.get("last"),
            "skips": tap_state.get("skips") or {},
        },
        "calendar": busy_calendar(busy_local_year(doc)),
        "calendar_years": busy_calendar_years(),
    }
    if mod is not None:
        # a malformed preview time is a caller error and must reach the route as 400;
        # anything else inside the evaluation degrades into probe_error instead
        when = mod.datetime.now(mod.TZ) if not at else _parse_at(mod, doc, at)
        # previewing another year must show that year's calendar, or 试算 and the
        # holiday list disagree about whether the day is a workday
        out["calendar"] = busy_calendar(when.year)
        try:
            out["at"] = when.strftime("%Y-%m-%d %H:%M %a")
            out["window_active_now"] = mod.busy_window_active(doc, when)
            probe = _probe_model(config, allowed)
            to, why = mod.busy_rewrite(config["route"], probe, allowed or {config["target_model"]}, when)
            out["probe"] = {"model": probe, "would_rewrite_to": to, "reason": why}
        except Exception as exc:
            out["probe_error"] = "%s: %s" % (type(exc).__name__, exc)
    out["phase"] = busy_phase(config, out, mod is not None)
    return out


def _probe_model(config, allowed):
    """A real allowlisted model matching the first prefix, or a synthetic stand-in.

    Probing with a model that the plan would refuse anyway tells you nothing.
    """
    prefixes = config.get("rewrite_prefixes") or []
    if not prefixes:
        return ""
    first = str(prefixes[0])
    hit = next((m for m in allowed if m.startswith(first)), None)
    return hit or (first + "-probe")



def _parse_at(mod, doc, text):
    """Preview time in the policy's own UTC offset; 'HH:MM' means today."""
    tz = mod.timezone(mod.timedelta(minutes=int(doc.get("utc_offset_minutes", 480))))
    # clients may send the ISO 'T' separator; parse_qs already turned %20/+ into spaces
    text = str(text).strip().replace("T", " ")
    for fmt in ("%Y-%m-%d %H:%M", "%H:%M"):
        try:
            d = mod.datetime.strptime(text, fmt)
            if fmt == "%H:%M":
                now = mod.datetime.now(tz)
                d = d.replace(year=now.year, month=now.month, day=now.day)
            return d.replace(tzinfo=tz)
        except ValueError:
            continue
    raise ValueError("时间格式应为 HH:MM 或 YYYY-MM-DD HH:MM")


_CN_HOLIDAY_NAMES = {
    "New Year's Day": "元旦",
    "Spring Festival": "春节",
    "Tomb-sweeping Day": "清明节",
    "Labour Day": "劳动节",
    "Dragon Boat Festival": "端午节",
    "Mid-autumn Festival": "中秋节",
    "National Day": "国庆节",
}
_BUSY_CAL_CACHE = {}
_BUSY_CAL_YEARS = {}


def _year_has_data(y):
    try:
        is_workday(datetime(y, 1, 1).date())
        return True
    except Exception:
        return False


def _cn_holiday_name(d):
    try:
        a, b = get_holiday_detail(d)
    except Exception:
        return ""
    for v in (a, b):
        if isinstance(v, str) and v:
            return _CN_HOLIDAY_NAMES.get(v, v)
    return ""


def busy_calendar_years():
    """Contiguous year span the holiday library really covers, so the page can say
    which years fall back to plain Monday-Friday instead of looking broken."""
    if not _BUSY_CAL_YEARS:
        span = {"available": False, "min": None, "max": None}
        if HAS_CN_CAL:
            ys = [y for y in range(1995, 2101) if _year_has_data(y)]
            if ys:
                span = {"available": True, "min": min(ys), "max": max(ys)}
        _BUSY_CAL_YEARS.update(span)
    return dict(_BUSY_CAL_YEARS)


def busy_local_year(doc):
    """Year in the policy's own offset: the year the tap is about to be asked about."""
    try:
        off = int(doc.get("utc_offset_minutes", 480) or 0)
        return datetime.now(timezone(timedelta(minutes=off))).year
    except Exception:
        return datetime.now().year


def busy_calendar(year):
    """Workday deviations for `year`, produced by the same is_workday() the tap calls.

    A calendar the traffic path disagrees with would be worse than none, and the browser
    cannot reach any holiday CDN from this network anyway. Only days that differ from
    plain Monday-Friday are returned: statutory holidays ("off") and make-up workdays
    that fall on a weekend ("makeup").
    """
    if year in _BUSY_CAL_CACHE:
        return dict(_BUSY_CAL_CACHE[year])
    res = {"year": year, "available": False, "days": [], "off_count": 0, "makeup_count": 0,
           "source": "chinese_calendar", "note": ""}
    if not HAS_CN_CAL:
        res["note"] = "本机没有 chinese_calendar，忙时的「仅工作日」只能按周一到周五判定，不含调休。"
    elif not _year_has_data(year):
        rng = busy_calendar_years()
        res["note"] = ("%s 年没有官方节假日数据（库覆盖 %s-%s）。该年回落到按周一到周五判定："
                       "法定假日会被当成工作日改写，周末调休补班不会被改写。"
                       % (year, rng.get("min"), rng.get("max")))
    else:
        days = []
        d = datetime(year, 1, 1).date()
        while d.year == year:
            weekend = d.weekday() >= 5
            try:
                work = bool(is_workday(d))
            except Exception:
                break
            if work == weekend:
                days.append({"date": d.isoformat(), "weekday": d.weekday(),
                             "type": "makeup" if work else "off",
                             "name": _cn_holiday_name(d)})
            d = d + timedelta(days=1)
        res.update({
            "available": True, "days": days,
            "off_count": sum(1 for x in days if x["type"] == "off"),
            "makeup_count": sum(1 for x in days if x["type"] == "makeup"),
        })
    _BUSY_CAL_CACHE[year] = dict(res)
    return dict(res)


def busy_validate(payload):
    """(doc, errors, windows_empty). All-or-nothing: nothing is written while errors is non-empty."""
    errors = []
    allow = read_json_file(GO_ALLOW_PATH)
    allowed = set(str(m) for m in (allow.get("models") or []) if m) if isinstance(allow, dict) else set()

    enabled = payload.get("enabled")
    if not isinstance(enabled, bool):
        errors.append("enabled 必须是布尔值")
    weekdays = payload.get("weekdays_only")
    if not isinstance(weekdays, bool):
        errors.append("weekdays_only 必须是布尔值")
    route = str(payload.get("route") or "")
    if route not in POLICY_ROUTES:
        errors.append("route 只能是 %s 之一" % "/".join(POLICY_ROUTES))
    target = str(payload.get("target_model") or "").strip()
    if not MODEL_RE.match(target):
        errors.append("target_model 名称不合法（小写字母/数字/._+-，2-64 位）")
    elif allowed and target not in allowed:
        errors.append("target_model %s 不在 Go 套餐白名单里：改写成它只会让请求被白名单 403，"
                      "等于把改写变成停机" % target)

    prefixes = payload.get("rewrite_prefixes")
    if not isinstance(prefixes, list) or not prefixes:
        errors.append("rewrite_prefixes 至少要有一个（否则永远不会改写）")
        prefixes = []
    clean_prefixes = []
    for p in prefixes:
        p = str(p).strip().lower()
        if not MODEL_RE.match(p):
            errors.append("改写前缀 %r 不合法" % p)
        elif p in clean_prefixes:
            errors.append("改写前缀 %r 重复" % p)
        else:
            clean_prefixes.append(p)

    windows = payload.get("windows")
    if not isinstance(windows, list):
        errors.append("windows 必须是数组")
        windows = []
    if len(windows) > POLICY_MAX_WINDOWS:
        errors.append("窗口最多 %d 个" % POLICY_MAX_WINDOWS)
    clean_windows, seen = [], set()
    for w in windows:
        if not isinstance(w, dict):
            errors.append("窗口项必须是对象")
            continue
        s, e = str(w.get("start") or "").strip(), str(w.get("end") or "").strip()
        if not HHMM_RE.match(s) or not HHMM_RE.match(e):
            errors.append("窗口 %s-%s 时间格式应为 HH:MM（00-23 时，00-59 分）" % (s or "?", e or "?"))
            continue
        if s == e:
            errors.append("窗口 %s-%s 起止相同，等于空窗口" % (s, e))
            continue
        if (s, e) in seen:
            errors.append("窗口 %s-%s 重复" % (s, e))
            continue
        seen.add((s, e))
        clean_windows.append({"start": s, "end": e})
    # overlapping windows are allowed but confusing; they are also idempotent, so warn only
    try:
        offset = int(payload.get("utc_offset_minutes", 480))
    except (TypeError, ValueError):
        errors.append("utc_offset_minutes 必须是整数")
        offset = 480
    if not (-720 <= offset <= 840):
        errors.append("utc_offset_minutes 应在 -720..840 之间")

    doc = {
        "version": 1,
        "note": "由 Cloud Monitor 面板写入。手改也行，tap 按 mtime 自动重载。"
                "临时停用用 /opt/relay/busy_hours.py off。",
        "enabled": bool(enabled),
        "route": route,
        "target_model": target,
        "rewrite_prefixes": clean_prefixes,
        "weekdays_only": bool(weekdays),
        "utc_offset_minutes": offset,
        "windows": clean_windows,
    }
    return doc, errors, bool(errors == [] and not clean_windows)


def busy_write(doc):
    """Atomic write with a timestamped backup; returns the backup path."""
    prev = None
    if os.path.exists(BUSY_CONF_PATH):
        stamp = datetime.now(TZ).strftime("%Y%m%d-%H%M%S")
        prev = "%s/busy-hours.json.bak-%s" % (DATA_DIR, stamp)
        try:
            with open(BUSY_CONF_PATH, encoding="utf-8") as f:
                old = f.read()
            with open(prev, "w", encoding="utf-8") as f:
                f.write(old)
            os.chmod(prev, 0o600)
        except OSError:
            prev = None
        # keep the five most recent backups
        try:
            baks = sorted(glob.glob("%s/busy-hours.json.bak-*" % DATA_DIR))
            for stale in baks[:-5]:
                os.remove(stale)
        except OSError:
            pass
    tmp = BUSY_CONF_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.replace(tmp, BUSY_CONF_PATH)
    os.chmod(BUSY_CONF_PATH, 0o600)
    return prev


def busy_switch(off):
    if off:
        os.makedirs(os.path.dirname(BUSY_OFF_FLAG), exist_ok=True)
        with open(BUSY_OFF_FLAG, "w", encoding="utf-8") as f:
            f.write("disabled from panel at %s\n" % datetime.now(TZ).isoformat(timespec="seconds"))
    else:
        try:
            os.remove(BUSY_OFF_FLAG)
        except FileNotFoundError:
            pass
    return os.path.exists(BUSY_OFF_FLAG)


SCHEMA = """
CREATE TABLE IF NOT EXISTS requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts REAL NOT NULL,
  day TEXT NOT NULL,
  hour TEXT NOT NULL,
  route TEXT,
  method TEXT,
  ip TEXT,
  model TEXT,
  status INTEGER,
  bytes_in INTEGER,
  bytes_out INTEGER,
  rt REAL,
  urt REAL,
  ua TEXT,
  uri TEXT
);
CREATE INDEX IF NOT EXISTS ix_day ON requests(day);
CREATE INDEX IF NOT EXISTS ix_model ON requests(model);
CREATE INDEX IF NOT EXISTS ix_ts ON requests(ts);
CREATE INDEX IF NOT EXISTS ix_day_model ON requests(day, model);
/* Who has been knocking. Separate from `requests` on purpose: the hot insert lists 22
   columns and must not change shape, and this survives row pruning so a device list
   does not evaporate when old traffic is trimmed. */
CREATE TABLE IF NOT EXISTS client_src (
  ip TEXT PRIMARY KEY,
  class TEXT,
  xff TEXT,
  ua TEXT,
  first_ts REAL,
  last_ts REAL,
  hits INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_client_last ON client_src(last_ts);
"""

# Columns added after the first release; migrated in main().
MIGRATIONS = [
    ("uri", "TEXT"),
    ("ttfb", "REAL"),             # upstream_header_time: time to first byte from the edge
    ("attempts", "INTEGER"),      # upstream addresses tried for this request (>=1)
    ("upstream_addr", "TEXT"),    # edge address(es) nginx used, comma separated on retry
    ("upstream_status", "TEXT"),  # comma separated per-attempt statuses
    ("creq", "INTEGER"),          # requests already served on the same client connection
    ("sess", "TEXT"),             # x-opencode-session
    ("ctype", "TEXT"),            # response content type (text/event-stream = streaming)
    ("proto", "TEXT"),
]

INSERT_SQL = (
    "INSERT INTO requests (ts, day, hour, route, method, ip, model, status, bytes_in, bytes_out,"
    " rt, urt, ua, uri, ttfb, attempts, upstream_addr, upstream_status, creq, sess, ctype, proto) "
    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
)

CLIENT_UPSERT_SQL = (
    "INSERT INTO client_src (ip, class, xff, ua, first_ts, last_ts, hits) VALUES (?,?,?,?,?,?,?)"
    " ON CONFLICT(ip) DO UPDATE SET"
    "  last_ts = MAX(client_src.last_ts, excluded.last_ts),"
    "  first_ts = MIN(client_src.first_ts, excluded.first_ts),"
    "  hits = client_src.hits + excluded.hits,"
    "  class = COALESCE(NULLIF(client_src.class,''), excluded.class),"
    "  xff   = CASE WHEN excluded.xff != '' THEN excluded.xff ELSE client_src.xff END,"
    "  ua    = CASE WHEN excluded.ua != '' THEN excluded.ua ELSE client_src.ua END"
)


def local_subnet():
    """The server's own IPv4 network, so 'same VPC subnet' can be told apart from
    'some other private space'. Best effort; empty when it cannot be determined."""
    try:
        import socket as _s
        ip = _s.gethostbyname(_s.gethostname())
        return tuple(int(x) for x in ip.split("."))[:3] if ip else ()
    except Exception:
        return ()


LOCAL_NET = local_subnet()   # e.g. (172, 31, 101) for this box's /24 inside the VPC /20


def src_class(ip):
    """Classify the peer address nginx actually saw.

    MAC is deliberately not attempted: this host's only NIC address is an Aliyun VPC
    address, the ARP table resolves every neighbour to the vRouter's synthetic
    ee:ff:ff:ff:ff:ff, and a MAC never crosses a router in the first place -- so any
    'MAC' column here would be a lie. The internal address, when it exists, arrives as
    X-Forwarded-For from the client's own gateway and is kept per source IP.
    """
    try:
        o = [int(x) for x in str(ip).split(".")]
    except ValueError:
        return "other"
    if len(o) != 4 or any(x < 0 or x > 255 for x in o):
        return "other"
    if o[0] == 127:
        return "loopback"
    if o[0] == 169 and o[1] == 254:
        return "link-local"
    if o[0] == 100 and 64 <= o[1] <= 127:
        return "carrier-nat"
    if o[0] == 10 or o[0] == 192 and o[1] == 168 or o[0] == 172 and 16 <= o[1] <= 31:
        mine = LOCAL_NET
        if mine and o[:3] == list(mine):
            return "same-subnet"
        return "private"
    return "public"


def xff_from_line(line):
    """The 19th relay_json field ($http_x_forwarded_for). Older log lines simply have
    no 19th field, and a client that never passes through a proxy has it empty."""
    parts = line.rstrip("\n").split("|")
    if len(parts) < 19:
        return ""
    return (parts[18] or "").strip()[:180]


def note_client(batch, ts, ip, ua, xff):
    """Fold one request's origin into the batch accumulator (ip -> row parts)."""
    if not ip:
        return
    e = batch.get(ip)
    if e is None:
        batch[ip] = [src_class(ip), (xff or "")[:180], (ua or "").replace('"', "")[:160], ts, ts, 1]
    else:
        e[3] = min(e[3], ts)
        e[4] = max(e[4], ts)
        e[5] += 1
        if xff:
            e[1] = (xff or "")[:180]
        if ua:
            e[2] = (ua or "").replace('"', "")[:160]


# Human names for source IPs. A file, not a DB column: it must survive the row-pruning
# that trims traffic history, and it should be copyable between machines.
CLIENT_LABELS_PATH = os.environ.get("CM_CLIENT_LABELS", "/etc/relay/client-labels.json")


def client_labels():
    raw = read_json_file(CLIENT_LABELS_PATH)
    return raw if isinstance(raw, dict) else {}


def client_label_set(ip, label):
    ip = str(ip or "").strip()[:64]
    if not ip:
        raise ValueError("缺少 ip")
    d = client_labels()
    label = str(label or "").strip()[:40]
    if label:
        d[ip] = label
    else:
        d.pop(ip, None)
    tmp = CLIENT_LABELS_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
    os.replace(tmp, CLIENT_LABELS_PATH)
    try:
        os.chmod(CLIENT_LABELS_PATH, 0o644)
    except OSError:
        pass
    return d

# Shared ingest state for /api/health
STATE = {"rows": 0, "last_ingest": None, "started": time.time(), "error": None,
         "ingesting": 0, "offset": 0, "lines_skipped": 0}


def connect():
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    return conn


def num(value, default=0.0):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def opt_num(value):
    """float or None for '-', '' and unparseable values."""
    if value is None:
        return None
    value = value.strip()
    if value in ("", "-"):
        return None
    try:
        return float(value)
    except ValueError:
        return None


def count_attempts(upstream_addr):
    """1 for a normal request, >1 when nginx retried another upstream address."""
    parts = [p.strip() for p in (upstream_addr or "").split(",")]
    return max(1, len([p for p in parts if p not in ("", "-")]))


def parse_line(line):
    """Parse one relay_json access log line into a row tuple (v1 or v2 format)."""
    parts = line.rstrip("\n").split("|")
    if len(parts) < 12:
        return None
    iso, ip, status, req_len, bytes_sent, rt, urt, method, uri, model, up_addr = parts[:11]
    try:
        dt = datetime.fromisoformat(iso).astimezone(TZ)
    except ValueError:
        return None
    if uri.startswith("/zen/v1/"):
        route = "zen"
    elif uri.startswith("/v1/"):
        route = "go"
    else:
        return None  # dashboard/status endpoints are not relay traffic

    ttfb = attempts = creq = sess = ctype = proto = up_status = None
    if len(parts) >= 18:
        ttfb = opt_num(parts[11])
        up_status = (parts[12] or "")[:120] or None
        creq = int(num(parts[13], 0)) or None
        sess = (parts[14] or "")[:64] or None
        ctype = (parts[15] or "").split(";")[0].strip()[:60] or None
        proto = (parts[16] or "")[:16] or None
        ua = parts[17]
    else:
        ua = parts[11]

    return (
        dt.timestamp(),
        dt.strftime("%Y-%m-%d"),
        dt.strftime("%Y-%m-%d %H"),
        route,
        method,
        ip,
        (model or "unknown")[:80],
        int(num(status)),
        int(num(req_len)),
        int(num(bytes_sent)),
        num(rt),
        num(urt),
        ua.replace('"', "")[:160],
        uri[:120],
        ttfb,
        count_attempts(up_addr),
        (up_addr or "")[:120] or None,
        up_status,
        creq,
        sess,
        ctype,
        proto,
    )


def load_ingest_state():
    try:
        with open(STATE_PATH, encoding="utf-8") as f:
            st = json.load(f)
        return int(st.get("inode") or 0), int(st.get("offset") or 0)
    except (OSError, ValueError, TypeError):
        return 0, 0


def save_ingest_state(inode, offset):
    try:
        tmp = STATE_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"inode": inode, "offset": offset,
                       "at": datetime.now(TZ).isoformat(timespec="seconds")}, f)
        os.replace(tmp, STATE_PATH)
    except OSError:
        pass


def ingest_loop(stop):
    """Tail the access log.

    The read position survives restarts (inode + offset in ingest.state), so a
    relay-stats restart no longer silently drops everything written while it was
    down - that gap is what used to make the reports incomplete.
    """
    conn = connect()
    inode, _ = load_ingest_state()
    fh = None
    pending = []
    last_prune = 0.0
    last_save = 0.0
    while not stop.is_set():
        try:
            st = os.stat(LOG_PATH)
            if fh is None or st.st_ino != inode:
                if fh:
                    fh.close()
                fh = open(LOG_PATH, "r", encoding="utf-8", errors="replace")
                inode = st.st_ino
                saved_inode, saved_off = load_ingest_state()
                # Same file and a sane offset: resume exactly where we stopped.
                # Otherwise (rotated/new file) read it from the top.
                start = saved_off if (saved_inode == inode and 0 <= saved_off <= st.st_size) else 0
                fh.seek(start)
                STATE["error"] = None
            elif st.st_size < fh.tell():
                fh.seek(0)  # truncated in place

            clients = {}
            while len(pending) < BATCH:
                before = fh.tell()
                line = fh.readline()
                if not line:
                    break
                if not line.endswith("\n"):
                    fh.seek(before)  # partial write, wait for the rest
                    break
                row = parse_line(line)
                if row:
                    pending.append(row)
                    note_client(clients, row[0], row[5], row[12], xff_from_line(line))
                else:
                    STATE["lines_skipped"] += 1

            pos = fh.tell()
            if pending:
                conn.executemany(INSERT_SQL, pending)
                if clients:
                    conn.executemany(CLIENT_UPSERT_SQL, [
                        [ip, v[0], v[1], v[2], v[3], v[4], v[5]] for ip, v in clients.items()])
                conn.commit()
                STATE["rows"] += len(pending)
                STATE["last_ingest"] = datetime.now(TZ).isoformat(timespec="seconds")
                trigger_quota_watch(pending)
                pending.clear()
            if pos != STATE["offset"] or time.time() - last_save > 5:
                save_ingest_state(inode, pos)
                STATE["offset"] = pos
                last_save = time.time()

            now = time.time()
            if now - last_prune > 3600:
                cutoff = (datetime.now(TZ) - timedelta(days=RETENTION_DAYS)).strftime("%Y-%m-%d")
                conn.execute("DELETE FROM requests WHERE day < ?", (cutoff,))
                # token usage and the session archive grow on their own schedules
                try:
                    conn.execute("DELETE FROM usage_events WHERE day < ?", (cutoff,))
                except sqlite3.OperationalError:
                    pass
                # bound the memory archive by rows too, not only by age
                try:
                    conn.execute(
                        "DELETE FROM session_turns WHERE id NOT IN"
                        " (SELECT id FROM session_turns ORDER BY ts DESC LIMIT 100000)")
                except sqlite3.OperationalError:
                    pass
                conn.commit()
                # keep the -wal file small; the dashboard is read-mostly
                try:
                    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                    free = conn.execute("PRAGMA freelist_count").fetchone()[0]
                    pages = conn.execute("PRAGMA page_count").fetchone()[0]
                    if pages and free * 4 > pages:      # >25% free pages -> compact
                        conn.execute("VACUUM")
                        STATE["last_vacuum"] = datetime.now(TZ).isoformat(timespec="seconds")
                except sqlite3.Error:
                    pass
                # report HTMLs are 200KB each; keep a month
                try:
                    import glob as _glob
                    rcut = (datetime.now(TZ) - timedelta(days=30)).strftime("%Y-%m-%d")
                    for path in _glob.glob(os.path.join(DATA_DIR, "public", "reports", "report-*.html")):
                        day = os.path.basename(path)[len("report-"):-len(".html")]
                        if day < rcut:
                            os.remove(path)
                except OSError:
                    pass
                last_prune = now
        except Exception as exc:  # keep serving even if ingestion hiccups
            STATE["error"] = f"{type(exc).__name__}: {exc}"
            time.sleep(2)
        time.sleep(0.5)
    if fh:
        fh.close()
    conn.close()


def read_upstream_key():
    """Current official OpenCode key (stored as an nginx include file)."""
    try:
        with open(UPSTREAM_AUTH, encoding="utf-8") as f:
            m = re.search(r'Bearer ([A-Za-z0-9_\-]+)"', f.read())
        return m.group(1) if m else None
    except OSError:
        return None


def mask_key(key):
    if not key:
        return ""
    return key[:10] + "…" + key[-6:] if len(key) > 20 else key[:4] + "…"


def upstream_get(url, key, timeout=25):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + key,
                                               "User-Agent": "opencode/latest/2.0.26/cli",
                                               "x-opencode-session": "relay-quota"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        try:
            return exc.code, json.loads(raw)
        except ValueError:
            return exc.code, {"raw": raw[:200]}
    except Exception as exc:
        return 0, {"error": f"{type(exc).__name__}: {exc}"}


def fetch_quota(force=False):
    """Fetch Go plan usage (5h rolling / weekly / monthly) and cache it."""
    path = QUOTA_PATH
    if not force and os.path.exists(path) and time.time() - os.path.getmtime(path) < QUOTA_TTL:
        try:
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            pass
    key = read_upstream_key()
    if not key:
        return {"error": "no upstream key configured"}
    status, data = upstream_get(USAGE_URL, key)
    if status != 200:
        return {"error": f"usage endpoint HTTP {status}", "detail": data}
    usage = data.get("usage", data)
    payload = {
        "fetched_at": datetime.now(TZ).isoformat(timespec="seconds"),
        "key_masked": mask_key(key),
        "usage": usage,
    }
    try:
        os.makedirs(PUBLIC_DIR, exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
        os.chmod(path, 0o644)
    except OSError:
        pass
    return payload


def validate_key(new_key):
    status, _ = upstream_get(MODELS_URL, new_key)
    return status == 200, status


def write_upstream_key(key):
    """Atomically swap the official key and reload nginx; keeps a backup."""
    body = (f'proxy_set_header Authorization "Bearer {key}";\n'
            f'proxy_set_header x-api-key "{key}";\n')
    if os.path.exists(UPSTREAM_AUTH):
        with open(UPSTREAM_AUTH, "r", encoding="utf-8") as f:
            if key in f.read():
                return True, "unchanged"
    try:
        with open(UPSTREAM_AUTH, "r", encoding="utf-8") as f:
            old = f.read()
        with open(UPSTREAM_AUTH + ".bak", "w", encoding="utf-8") as f:
            f.write(old)
    except OSError:
        pass
    tmp = UPSTREAM_AUTH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(body)
    os.chmod(tmp, 0o600)
    os.replace(tmp, UPSTREAM_AUTH)
    test = subprocess.run(["nginx", "-t"], capture_output=True, text=True)
    if test.returncode != 0:
        # roll back
        with open(UPSTREAM_AUTH, "w", encoding="utf-8") as f:
            f.write(old)
        return False, (test.stderr or "").strip()[-300:]
    subprocess.run(["systemctl", "reload", "nginx"], check=False)
    return True, "reloaded"


def run_modelcheck():
    """Run the daily model probe in the background."""
    if not MODELCHECK_LOCK.acquire(blocking=False):
        return False, "already running"

    def target():
        try:
            subprocess.run([sys.executable, MODELCHECK_PY], capture_output=True, text=True, timeout=900)
        except Exception:
            pass
        finally:
            MODELCHECK_LOCK.release()
    threading.Thread(target=target, daemon=True).start()
    return True, "started"


def day_list(days):
    today = datetime.now(TZ).date()
    return [(today - timedelta(days=i)).strftime("%Y-%m-%d") for i in range(days - 1, -1, -1)]


def pct(values, p):
    if not values:
        return 0.0
    values = sorted(values)
    idx = min(len(values) - 1, int(round((p / 100.0) * (len(values) - 1))))
    return round(values[idx], 3)


def bucketize(values, edges):
    """Count values into '< edge[0]', 'edge[i-1]-edge[i]', '>= last' buckets."""
    labels = []
    counts = [0] * (len(edges) + 1)
    for i, edge in enumerate(edges):
        prev = edges[i - 1] if i else 0
        labels.append(f"{prev}-{edge}" if i else f"<{edge}")
    labels.append(f">{edges[-1]}")
    for v in values:
        for i, edge in enumerate(edges):
            if v < edge:
                counts[i] += 1
                break
        else:
            counts[-1] += 1
    return [{"bucket": labels[i], "count": counts[i]} for i in range(len(labels))]


SIZE_EDGES = [1024, 10 * 1024, 100 * 1024, 1024 * 1024, 3 * 1024 * 1024]
SIZE_LABELS = ["<1KB", "1-10KB", "10-100KB", "100KB-1MB", "1-3MB", ">3MB"]

DAILY_SQL = (
    "SELECT day, COUNT(*) reqs, COALESCE(SUM(bytes_out),0) bout, COALESCE(SUM(bytes_in),0) bin_, "
    "SUM(CASE WHEN status < 400 THEN 1 ELSE 0 END) ok, AVG(rt) avg_rt, AVG(ttfb) avg_ttfb, "
    "SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END) retried, COALESCE(SUM(COALESCE(attempts, 1)),0) attempts, "
    "COUNT(DISTINCT sess) sess, COUNT(DISTINCT ip) ips, COUNT(DISTINCT model) models, "
    "SUM(CASE WHEN status >= 500 THEN 1 ELSE 0 END) e5, "
    "SUM(CASE WHEN status >= 400 AND status < 500 THEN 1 ELSE 0 END) e4 "
    "FROM requests WHERE day >= ? AND ua NOT LIKE ? GROUP BY day"
)


def q_daily(since, days, fargs):
    """Per-day rollup shared by /api/daily and the CSV export."""
    conn = connect()
    try:
        data = {r["day"]: r for r in conn.execute(DAILY_SQL, (since,) + fargs).fetchall()}
    finally:
        conn.close()
    out = []
    for d in day_list(days):
        r = data.get(d)
        reqs = (r["reqs"] if r else 0) or 0
        out.append({"day": d, "requests": reqs,
                    "bytes_out": (r["bout"] if r else 0),
                    "bytes_in": (r["bin_"] if r else 0),
                    "errors": ((r["reqs"] - r["ok"]) if r else 0),
                    "errors_4xx": (r["e4"] if r else 0),
                    "errors_5xx": (r["e5"] if r else 0),
                    "retried": (r["retried"] if r else 0),
                    "attempts": (r["attempts"] if r else 0),
                    "sessions": (r["sess"] if r else 0),
                    "clients": (r["ips"] if r else 0),
                    "models": (r["models"] if r else 0),
                    "success_rate": round(100.0 * (r["ok"] or 0) / reqs, 2) if r and reqs else 100.0,
                    "avg_latency": round(r["avg_rt"], 3) if r and r["avg_rt"] else 0,
                    "avg_ttfb": round(r["avg_ttfb"], 3) if r and r["avg_ttfb"] else 0})
    return out


MODELS_SQL = (
    "SELECT model, route, COUNT(*) reqs, COALESCE(SUM(bytes_out),0) bout, AVG(rt) avg_rt, "
    "AVG(ttfb) avg_ttfb, SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END) retried, "
    "COUNT(DISTINCT sess) sess, "
    "SUM(CASE WHEN status < 400 THEN 1 ELSE 0 END) ok, MAX(ts) last_ts FROM requests WHERE day >= ? "
    "AND ua NOT LIKE ? GROUP BY model, route ORDER BY reqs DESC"
)


def q_models(since, fargs):
    """Per-model rollup with a per-model p95, shared by /api/models and export."""
    conn = connect()
    try:
        rows = conn.execute(MODELS_SQL, (since,) + fargs).fetchall()
        out = []
        for r in rows:
            rts = [x["rt"] for x in conn.execute(
                "SELECT rt FROM requests WHERE day >= ? AND model = ? AND rt IS NOT NULL "
                "AND ua NOT LIKE ? LIMIT 20000", (since, r["model"]) + fargs)]
            out.append({
                "model": r["model"], "route": r["route"], "requests": r["reqs"], "bytes_out": r["bout"],
                "avg_latency": round(r["avg_rt"] or 0, 3),
                "p95_latency": pct(rts, 95),
                "avg_ttfb": round(r["avg_ttfb"], 3) if r["avg_ttfb"] else 0,
                "retried": r["retried"] or 0,
                "sessions": r["sess"] or 0,
                "errors": r["reqs"] - r["ok"],
                "success_rate": round(100.0 * r["ok"] / r["reqs"], 2) if r["reqs"] else 100.0,
                "last_seen": datetime.fromtimestamp(r["last_ts"], TZ).isoformat(timespec="seconds") if r["last_ts"] else None,
            })
    finally:
        conn.close()
    return out


ERRORS_SQL = (
    "SELECT ts, model, route, uri, status, bytes_out, rt, ttfb, attempts, upstream_status, sess "
    "FROM requests WHERE day >= ? AND status >= 400 AND ua NOT LIKE ? ORDER BY ts DESC LIMIT ?"
)


def q_errors(since, fargs, limit=50):
    """Recent failures with their failure-mode columns."""
    conn = connect()
    try:
        rows = conn.execute(ERRORS_SQL, (since,) + fargs + (limit,)).fetchall()
    finally:
        conn.close()
    return [{
        "time": datetime.fromtimestamp(r["ts"], TZ).isoformat(timespec="seconds"),
        "model": r["model"], "route": r["route"], "uri": r["uri"], "status": r["status"],
        "bytes_out": r["bytes_out"], "latency": round(r["rt"] or 0, 3),
        "ttfb": round(r["ttfb"], 3) if r["ttfb"] is not None else None,
        "attempts": r["attempts"], "upstream_status": r["upstream_status"],
        "session": (r["sess"] or "")[:18]} for r in rows]


# ═══════════════════════════════════════════════════════════════════
#  Client / IP analytics and new traffic dimensions
# ═══════════════════════════════════════════════════════════════════
PROBE_PAT = "%" + PROBE_UA + "%"

# Normalise a raw User-Agent into a human readable client family.
UA_FAMILIES = [
    ("OpenCode CLI", re.compile(r"opencode/(latest|\d)", re.I)),
    ("中继巡检", re.compile(r"relay-modelcheck", re.I)),
    ("curl", re.compile(r"\bcurl/", re.I)),
    ("wget", re.compile(r"\bwget/", re.I)),
    ("Python", re.compile(r"python-requests|python-urllib|aiohttp|httpx|python/", re.I)),
    ("Node/JS", re.compile(r"node-fetch|axios|undici|okhttp|node/", re.I)),
    ("Go", re.compile(r"go-http-client", re.I)),
    ("Postman", re.compile(r"postman", re.I)),
    ("Chrome/Edge", re.compile(r"chrome|crios|edg", re.I)),
    ("Firefox", re.compile(r"firefox", re.I)),
    ("Safari", re.compile(r"safari", re.I)),
]


def ua_family(ua):
    ua = (ua or "").strip()
    if not ua:
        return "(空)"
    for name, pat in UA_FAMILIES:
        if pat.search(ua):
            return name
    return (ua.split("/")[0] or ua)[:28]


def delta_pct(cur, prev):
    """Percent change, or None when there is no baseline to compare against."""
    if prev:
        return round(100.0 * (cur - prev) / prev, 1)
    return None if not cur else 100.0


def q_clients(conn, since, since_ts, limit=60):
    """Per-source-IP usage rollup: volume, share, quality, activity window."""
    today = datetime.now(TZ).strftime("%Y-%m-%d")
    rows = conn.execute(
        "SELECT ip, COUNT(*) reqs, COALESCE(SUM(bytes_out),0) bout, COALESCE(SUM(bytes_in),0) bin_, "
        "COUNT(DISTINCT sess) sess, COUNT(DISTINCT model) models, COUNT(DISTINCT day) days, "
        "COUNT(DISTINCT hour) hours, "
        "SUM(CASE WHEN status>=400 THEN 1 ELSE 0 END) errors, "
        "SUM(CASE WHEN status>=500 THEN 1 ELSE 0 END) e5, "
        "SUM(CASE WHEN status>=400 AND status<500 THEN 1 ELSE 0 END) e4, "
        "SUM(CASE WHEN attempts>1 THEN 1 ELSE 0 END) retried, "
        "SUM(CASE WHEN ctype LIKE 'text/event-stream%' THEN 1 ELSE 0 END) streaming, "
        "AVG(rt) avg_rt, AVG(ttfb) avg_ttfb, AVG(creq) avg_creq, "
        "MIN(ts) first_ts, MAX(ts) last_ts, "
        "SUM(CASE WHEN day = ? THEN 1 ELSE 0 END) today_reqs, "
        "SUM(CASE WHEN day = ? THEN bytes_out ELSE 0 END) today_bout "
        "FROM requests WHERE day >= ? AND ua NOT LIKE ? GROUP BY ip ORDER BY reqs DESC",
        (today, today, since, PROBE_PAT)).fetchall()
    total = sum(r["reqs"] for r in rows) or 1
    tot_bout = sum(r["bout"] for r in rows) or 1
    # per-IP latency samples for P95/P99 (bounded)
    lat = {}
    for r in conn.execute(
            "SELECT ip, rt FROM requests WHERE day >= ? AND rt IS NOT NULL AND ua NOT LIKE ? "
            "LIMIT 300000", (since, PROBE_PAT)):
        lat.setdefault(r["ip"], []).append(r["rt"])
    # dominant model per IP
    topmod = {}
    for r in conn.execute(
            "SELECT ip, model, COUNT(*) c FROM requests WHERE day >= ? AND ua NOT LIKE ? "
            "GROUP BY ip, model", (since, PROBE_PAT)):
        cur = topmod.get(r["ip"])
        if cur is None or r["c"] > cur[1]:
            topmod[r["ip"]] = (r["model"], r["c"])
    # IPs whose very first request ever falls inside this window => new clients
    new_ips = {r["ip"] for r in conn.execute(
        "SELECT ip, MIN(ts) f FROM requests WHERE ua NOT LIKE ? GROUP BY ip HAVING f >= ?",
        (PROBE_PAT, since_ts))}
    out = []
    for r in rows[:limit]:
        reqs = r["reqs"] or 0
        errs = r["errors"] or 0
        samples = lat.get(r["ip"], [])
        out.append({
            "ip": r["ip"], "requests": reqs,
            "share": round(100.0 * reqs / total, 2),
            "bytes_out": r["bout"], "bytes_in": r["bin_"],
            "bytes_share": round(100.0 * r["bout"] / tot_bout, 2),
            "avg_bytes_out": round(r["bout"] / reqs) if reqs else 0,
            "sessions": r["sess"], "models": r["models"],
            "days": r["days"], "hours": r["hours"],
            "errors": errs, "errors_4xx": r["e4"] or 0, "errors_5xx": r["e5"] or 0,
            "success_rate": round(100.0 * (reqs - errs) / reqs, 2) if reqs else 100.0,
            "retried": r["retried"] or 0,
            "streaming": r["streaming"] or 0,
            "streaming_rate": round(100.0 * (r["streaming"] or 0) / reqs, 2) if reqs else 0.0,
            "avg_latency": round(r["avg_rt"] or 0, 3),
            "p95_latency": pct(samples, 95),
            "p99_latency": pct(samples, 99),
            "avg_ttfb": round(r["avg_ttfb"], 3) if r["avg_ttfb"] else 0,
            "avg_conn_requests": round(r["avg_creq"] or 0, 2),
            "requests_today": r["today_reqs"] or 0,
            "bytes_out_today": r["today_bout"] or 0,
            "top_model": (topmod.get(r["ip"]) or ("-", 0))[0],
            "new": r["ip"] in new_ips,
            "first_seen": datetime.fromtimestamp(r["first_ts"], TZ).isoformat(timespec="seconds") if r["first_ts"] else None,
            "last_seen": datetime.fromtimestamp(r["last_ts"], TZ).isoformat(timespec="seconds") if r["last_ts"] else None,
        })
    return {
        "clients": out,
        "total_ips": len(rows),
        "total_requests": total,
        "new_ips": len(new_ips),
        "window_ips": sum(1 for r in rows if r["ip"] in new_ips),
    }


def q_client_detail(conn, ip, since, days):
    """Everything we know about one source IP inside the window."""
    base = " FROM requests WHERE day >= ? AND ua NOT LIKE ? AND ip = ?"
    args = (since, PROBE_PAT, ip)
    models = conn.execute(
        "SELECT model, route, COUNT(*) c, COALESCE(SUM(bytes_out),0) bout, "
        "AVG(rt) avg_rt, AVG(ttfb) avg_ttfb, SUM(CASE WHEN status<400 THEN 1 ELSE 0 END) ok" + base +
        " GROUP BY model, route ORDER BY c DESC", args).fetchall()
    routes = conn.execute(
        "SELECT route, COUNT(*) c, COALESCE(SUM(bytes_out),0) bout" + base +
        " GROUP BY route ORDER BY c DESC", args).fetchall()
    statuses = conn.execute(
        "SELECT status, COUNT(*) c" + base + " GROUP BY status ORDER BY c DESC", args).fetchall()
    endpoints = conn.execute(
        "SELECT uri, COUNT(*) c, COALESCE(SUM(bytes_out),0) bout, AVG(rt) avg_rt" + base +
        " GROUP BY uri ORDER BY c DESC LIMIT 12", args).fetchall()
    daily = conn.execute(
        "SELECT day, COUNT(*) c, COALESCE(SUM(bytes_out),0) bout, "
        "SUM(CASE WHEN status>=400 THEN 1 ELSE 0 END) errs" + base +
        " GROUP BY day", args).fetchall()
    dmap = {r["day"]: r for r in daily}
    recent = conn.execute(
        "SELECT ts, model, route, method, uri, status, bytes_out, rt, ttfb" + base +
        " ORDER BY ts DESC LIMIT 20", args).fetchall()
    uas = conn.execute(
        "SELECT ua, COUNT(*) c, MAX(ts) last_ts" + base +
        " GROUP BY ua ORDER BY c DESC LIMIT 6", args).fetchall()
    return {
        "ip": ip,
        "models": [{"model": r["model"], "route": r["route"], "requests": r["c"],
                    "bytes_out": r["bout"], "avg_latency": round(r["avg_rt"] or 0, 3),
                    "avg_ttfb": round(r["avg_ttfb"] or 0, 3),
                    "errors": r["c"] - (r["ok"] or 0)} for r in models],
        "routes": [{"route": r["route"], "requests": r["c"], "bytes_out": r["bout"]} for r in routes],
        "statuses": [{"status": r["status"], "count": r["c"]} for r in statuses],
        "endpoints": [{"uri": r["uri"], "requests": r["c"], "bytes_out": r["bout"],
                       "avg_latency": round(r["avg_rt"] or 0, 3)} for r in endpoints],
        "daily": [{"day": d, "requests": (dmap[d]["c"] if d in dmap else 0),
                   "bytes_out": (dmap[d]["bout"] if d in dmap else 0),
                   "errors": (dmap[d]["errs"] if d in dmap else 0)} for d in day_list(days)],
        "recent": [{"time": datetime.fromtimestamp(r["ts"], TZ).isoformat(timespec="seconds"),
                    "model": r["model"], "route": r["route"], "method": r["method"],
                    "uri": r["uri"], "status": r["status"], "bytes_out": r["bytes_out"],
                    "latency": round(r["rt"] or 0, 3), "ttfb": r["ttfb"]} for r in recent],
        "user_agents": [{"ua": r["ua"], "family": ua_family(r["ua"]), "requests": r["c"],
                         "last_seen": datetime.fromtimestamp(r["last_ts"], TZ).isoformat(timespec="seconds")}
                        for r in uas],
    }


def q_compare(conn, days, frag, fargs):
    """Current window vs. the immediately preceding equal-length window."""
    today = datetime.now(TZ).date()
    cur_start = (today - timedelta(days=days - 1)).strftime("%Y-%m-%d")
    prev_start = (today - timedelta(days=2 * days - 1)).strftime("%Y-%m-%d")
    tomorrow = (today + timedelta(days=1)).strftime("%Y-%m-%d")

    def agg(start, end):
        return conn.execute(
            "SELECT COUNT(*) reqs, COALESCE(SUM(bytes_out),0) bout, COALESCE(SUM(bytes_in),0) bin_, "
            "SUM(CASE WHEN status<400 THEN 1 ELSE 0 END) ok, "
            "SUM(CASE WHEN status>=400 AND status<500 THEN 1 ELSE 0 END) e4, "
            "SUM(CASE WHEN status>=500 THEN 1 ELSE 0 END) e5, "
            "COUNT(DISTINCT ip) ips, COUNT(DISTINCT sess) sess, COUNT(DISTINCT model) models, "
            "AVG(rt) rt, SUM(CASE WHEN ctype LIKE 'text/event-stream%' THEN 1 ELSE 0 END) stream "
            "FROM requests WHERE day >= ? AND day < ?" + frag, (start, end) + fargs).fetchone()

    cur, prev = agg(cur_start, tomorrow), agg(prev_start, cur_start)

    def metric(label, c, p, unit=""):
        return {"label": label, "current": c, "previous": p,
                "delta": delta_pct(c, p), "unit": unit}

    metrics = [
        metric("请求数", cur["reqs"] or 0, prev["reqs"] or 0),
        metric("出站流量", cur["bout"] or 0, prev["bout"] or 0, "bytes"),
        metric("入站流量", cur["bin_"] or 0, prev["bin_"] or 0, "bytes"),
        metric("错误数", (cur["e4"] or 0) + (cur["e5"] or 0), (prev["e4"] or 0) + (prev["e5"] or 0)),
        metric("独立客户端", cur["ips"] or 0, prev["ips"] or 0),
        metric("会话数", cur["sess"] or 0, prev["sess"] or 0),
        metric("模型数", cur["models"] or 0, prev["models"] or 0),
        metric("流式请求", cur["stream"] or 0, prev["stream"] or 0),
        metric("平均延迟", round(cur["rt"] or 0, 3), round(prev["rt"] or 0, 3), "s"),
    ]
    return {"current_start": cur_start, "previous_start": prev_start,
            "days": days, "metrics": metrics}


class Handler(BaseHTTPRequestHandler):
    server_version = "relay-stats"

    def log_message(self, fmt, *args):  # keep the journal quiet
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8", cache="no-store"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False)
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        url = urlparse(self.path)
        if not url.path.startswith("/api/"):
            return self._send(404, {"error": "not found"})
        # CSRF guard: custom headers cannot be set by a cross-site page without a
        # CORS preflight, and we never answer preflights.
        if self.headers.get("X-Relay-Action") != "1":
            return self._send(403, {"error": "missing X-Relay-Action header"})
        try:
            length = int(self.headers.get("Content-Length") or 0)
            payload = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, TypeError):
            payload = {}
        try:
            return self._action(url.path[5:], payload)
        except Exception as exc:
            return self._send(500, {"error": f"{type(exc).__name__}: {exc}"})

    def _action(self, name, payload):
        if name == "busy-hours/save":
            doc, errors, no_windows = busy_validate(payload or {})
            if errors:
                # The panel's node proxy surfaces detail|error|message; the KMP client
                # reads only `error`. Put the human-readable text in both or the UI
                # shows a bare "HTTP 400".
                why = "校验未通过：" + "；".join(errors)
                return self._send(400, {"ok": False, "errors": errors,
                                        "error": why, "detail": why})
            prev = busy_write(doc)
            state = busy_state()
            note = "策略已保存" + ("，当前处于改写时段" if state.get("window_active_now")
                                   else "，当前不在改写时段")
            body = {"ok": True, "config": state["config"], "backup": prev,
                    "detail": note,
                    "window_active_now": state.get("window_active_now"),
                    "probe": state.get("probe")}
            if no_windows:
                body["warning"] = "窗口为空：策略等价于永不改写。要停用请用启用开关，而不是清空窗口。"
                body["detail"] = note + "（注意：" + body["warning"] + "）"
            return self._send(200, body)

        if name == "busy-hours/switch":
            off = bool((payload or {}).get("off"))
            now_off = busy_switch(off)
            return self._send(200, {"ok": True, "switch_off": now_off,
                                    "detail": "已临时停用改写，策略文件未改动" if now_off else "已恢复改写"})

        if name == "clients/label":
            ip = str((payload or {}).get("ip") or "")
            label = str((payload or {}).get("label") or "")
            try:
                d = client_label_set(ip, label)
            except (ValueError, OSError) as exc:
                why = "标签写入失败：%s" % exc
                return self._send(400, {"ok": False, "error": why, "detail": why})
            return self._send(200, {"ok": True, "labels": d,
                                    "detail": "已保存设备标签（%s）" % CLIENT_LABELS_PATH})

        if name == "quota/refresh":
            return self._send(200, fetch_quota(force=True))

        if name == "modelcheck":
            started, detail = run_modelcheck()
            return self._send(200, {"started": started, "detail": detail,
                                    "hint": "结果稍后出现在模型巡检表，可用 /api/model-status 轮询"})

        if name == "selfheal/run":
            # Ask the watchdog to run immediately (it owns all remediation logic).
            try:
                r = subprocess.run(["systemctl", "start", "relay-selfheal.service"],
                                   capture_output=True, text=True, timeout=120)
                return self._send(200, {"ok": r.returncode == 0,
                                        "detail": (r.stderr or r.stdout or "").strip()[-200:]})
            except Exception as exc:
                return self._send(500, {"error": f"{type(exc).__name__}: {exc}"})

        if name == "keys/meta":
            if not keys_enabled():
                return self._send(503, {"error": "key store 未加载"})
            prov = (payload.get("provider") or "opencode-go").strip()
            ok, msg = keystore.set_meta(prov, payload.get("meta") or {})
            return self._send(200 if ok else 400,
                              {"ok": ok, "detail": msg, "keys": keystore.status(prov)})

        if name == "keys/add":
            if not keys_enabled():
                return self._send(503, {"error": "key store 未加载"})
            ok, msg = keystore.add(payload.get("key") or "", payload.get("label") or "",
                                   provider=(payload.get("provider") or "opencode-go").strip())
            return self._send(200 if ok else 400, {"ok": ok, "detail": msg, "keys": keystore.status()})

        if name in ("keys/remove", "keys/enable", "keys/move", "keys/activate", "keys/test"):
            if not keys_enabled():
                return self._send(503, {"error": "key store 未加载"})
            kid = (payload.get("id") or "").strip()
            prov = (payload.get("provider") or "opencode-go").strip()
            if name == "keys/remove":
                ok, msg = keystore.remove(kid, provider=prov)
            elif name == "keys/enable":
                ok, msg = keystore.set_enabled(kid, bool(payload.get("enabled", True)), provider=prov)
            elif name == "keys/move":
                ok, msg = keystore.move(kid, int(payload.get("delta") or 0), provider=prov)
            elif name == "keys/activate":
                ok, msg = keystore.activate(kid, provider=prov)
            else:
                ok, msg, usage = keystore.test(kid, provider=prov)
                return self._send(200 if ok else 400,
                                  {"ok": ok, "detail": msg, "usage": usage,
                                   "keys": keystore.status(prov)})
            return self._send(200 if ok else 400,
                              {"ok": ok, "detail": msg, "keys": keystore.status(prov)})

        if name == "keys/refresh":
            if not keys_enabled():
                return self._send(503, {"error": "key store 未加载"})
            prov = (payload.get("provider") or "opencode-go").strip()
            res = keystore.sync(force_poll=True, provider=prov)
            return self._send(200, {"ok": True, "detail": res,
                                    "keys": keystore.status(prov)})

        if name == "key":
            new_key = (payload.get("key") or "").strip()
            if not new_key:
                return self._send(400, {"error": "missing key"})
            if not re.fullmatch(r"[A-Za-z0-9_\-\.~+/=:]{16,400}", new_key):
                return self._send(400, {"error": "key 格式不合法"})
            if keys_enabled():
                # multi-key mode: add it (if new) and prefer it for new requests
                added, msg = keystore.add(new_key, payload.get("label") or "面板切换")
                if not added and "已存在" in msg:
                    added, msg = True, "该 key 已存在"
                if not added:
                    return self._send(400, {"error": msg})
                kid = next((k["id"] for k in keystore.status()["keys"]
                            if k["masked"] == mask_key(new_key)), None)
                if kid:
                    ok2, msg2 = keystore.activate(kid)
                    msg += "；" + msg2
                quota = fetch_quota(force=True)
                started, _ = run_modelcheck()
                return self._send(200, {"ok": True, "key_masked": mask_key(new_key),
                                        "detail": msg, "quota": quota,
                                        "modelcheck_started": started,
                                        "keys": keystore.status()})
            ok, status = validate_key(new_key)
            if not ok:
                return self._send(400, {"error": f"该 key 无法访问官方 API (HTTP {status})，未做任何更改"})
            written, detail = write_upstream_key(new_key)
            if not written:
                return self._send(500, {"error": f"nginx 配置校验失败: {detail}"})
            quota = fetch_quota(force=True)
            started, _ = run_modelcheck()
            return self._send(200, {"ok": True, "key_masked": mask_key(new_key), "detail": detail,
                                    "quota": quota, "modelcheck_started": started})

        return self._send(404, {"error": "unknown action"})

    def do_GET(self):
        url = urlparse(self.path)
        params = parse_qs(url.query)
        path = url.path
        try:
            if path in ("/", "/index.html"):
                return self._static("index.html", "text/html; charset=utf-8")
            if path in ("/app.js", "/style.css", "/vendor/echarts.min.js"):
                ctype = "application/javascript; charset=utf-8" if path.endswith(".js") else "text/css; charset=utf-8"
                return self._static(path.lstrip("/"), ctype)
            if path.startswith("/api/"):
                return self._api(path[5:], params)
            return self._send(404, {"error": "not found"})
        except BrokenPipeError:
            pass
        except Exception as exc:
            return self._send(500, {"error": f"{type(exc).__name__}: {exc}"})

    def _static(self, rel, ctype):
        full = os.path.normpath(os.path.join(STATIC_DIR, rel))
        if not full.startswith(STATIC_DIR) or not os.path.isfile(full):
            return self._send(404, {"error": "not found"})
        with open(full, "rb") as f:
            data = f.read()
        # vendor libs are versioned and safe to cache, UI files must not be stale
        cache = "max-age=86400" if rel.startswith("vendor/") else "no-cache"
        return self._send(200, data, ctype, cache=cache)

    def _days(self, params, default=1):
        try:
            return max(1, min(180, int(params.get("days", [default])[0])))
        except ValueError:
            return default

    # ── query helpers ───────────────────────────────────────────────
    @staticmethod
    def _filter(name, probe_pat):
        """Returns (sql_fragment, args) excluding synthetic probe traffic."""
        if name == "health":
            return "", ()
        return " AND ua NOT LIKE ?", probe_pat

    def _api(self, name, params):
        probe_pat = ("%" + PROBE_UA + "%",)
        days = self._days(params)
        since = day_list(days)[0]
        frag, fargs = self._filter(name, probe_pat)
        # one connection per request, always closed (the old code leaked them to the GC)
        with closing(connect()) as conn:
            return self._dispatch(name, params, conn, days, since, frag, fargs)

    def _dispatch(self, name, params, conn, days, since, frag, fargs):
        if name == "health":
            total = conn.execute("SELECT COUNT(*) c FROM requests").fetchone()["c"]
            return self._send(200, {
                "ok": True,
                "rows_total": total,
                "rows_ingested": STATE["rows"],
                "last_ingest": STATE["last_ingest"],
                "uptime_s": round(time.time() - STATE["started"]),
                "ingest_error": STATE["error"],
                "ingest_offset": STATE["offset"],
                "lines_skipped": STATE["lines_skipped"],
                "db_bytes": os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0,
            })

        if name == "summary":
            row = conn.execute(
                "SELECT COUNT(*) reqs, COALESCE(SUM(bytes_out),0) bout, COALESCE(SUM(bytes_in),0) bin_, "
                "SUM(CASE WHEN status < 400 THEN 1 ELSE 0 END) ok, "
                "SUM(CASE WHEN status = 401 THEN 1 ELSE 0 END) unauth, "
                "SUM(CASE WHEN status >= 400 AND status < 500 THEN 1 ELSE 0 END) e4, "
                "SUM(CASE WHEN status >= 500 THEN 1 ELSE 0 END) e5, "
                "SUM(CASE WHEN status = 504 THEN 1 ELSE 0 END) timeouts, "
                "SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END) retried, "
                "COALESCE(SUM(COALESCE(attempts, 1)),0) attempts_total, "
                "SUM(CASE WHEN ctype LIKE 'text/event-stream%' THEN 1 ELSE 0 END) streaming, "
                "COUNT(DISTINCT sess) sess, COUNT(DISTINCT model) models, COUNT(DISTINCT ip) ips, "
                "AVG(rt) avg_rt, AVG(creq) avg_creq, AVG(bytes_in) avg_bin, AVG(bytes_out) avg_bout "
                "FROM requests WHERE day >= ?" + frag, (since,) + fargs).fetchone()
            rts = [r["rt"] for r in conn.execute(
                "SELECT rt FROM requests WHERE day >= ? AND rt IS NOT NULL" + frag + " LIMIT 200000",
                (since,) + fargs)]
            ttfbs = [r["ttfb"] for r in conn.execute(
                "SELECT ttfb FROM requests WHERE day >= ? AND ttfb IS NOT NULL" + frag + " LIMIT 200000",
                (since,) + fargs)]
            today = datetime.now(TZ).strftime("%Y-%m-%d")
            trow = conn.execute(
                "SELECT COUNT(*) reqs, COALESCE(SUM(bytes_out),0) bout FROM requests WHERE day = ?" + frag,
                (today,) + fargs).fetchone()
            allrow = conn.execute("SELECT COUNT(*) reqs, COALESCE(SUM(bytes_out),0) bout FROM requests").fetchone()
            # ── extra traffic dimensions ────────────────────────
            since_ts = datetime.strptime(since, "%Y-%m-%d").replace(tzinfo=TZ).timestamp()
            clients_today = conn.execute(
                "SELECT COUNT(DISTINCT ip) c FROM requests WHERE day = ?" + frag, (today,) + fargs).fetchone()["c"]
            new_clients = conn.execute(
                "SELECT COUNT(*) c FROM (SELECT ip, MIN(ts) f FROM requests WHERE ua NOT LIKE ? "
                "GROUP BY ip HAVING f >= ?)", (PROBE_PAT, since_ts)).fetchone()["c"]
            peak = conn.execute(
                "SELECT hour, COUNT(*) c FROM requests WHERE day = ?" + frag +
                " GROUP BY hour ORDER BY c DESC LIMIT 1", (today,) + fargs).fetchone()
            # 401 responses are unauthorised probes from the internet, not relay failures
            effective = (row["reqs"] or 0) - (row["unauth"] or 0)
            reqs = row["reqs"] or 0
            errors = max(0, effective - (row["ok"] or 0))
            return self._send(200, {
                "days": days,
                "since": since,
                "requests": reqs,
                "requests_today": trow["reqs"] or 0,
                "bytes_out": row["bout"] or 0,
                "bytes_out_today": trow["bout"] or 0,
                "bytes_in": row["bin_"] or 0,
                "avg_bytes_in": round(row["avg_bin"] or 0),
                "avg_bytes_out": round(row["avg_bout"] or 0),
                "avg_bytes_total": round(((row["bin_"] or 0) + (row["bout"] or 0)) / reqs) if reqs else 0,
                "reqs_per_day": round(reqs / days, 1) if days else 0,
                "success": row["ok"] or 0,
                "errors": errors,
                "errors_4xx": row["e4"] or 0,
                "errors_5xx": row["e5"] or 0,
                "timeouts": row["timeouts"] or 0,
                "unauthorized": row["unauth"] or 0,
                "error_rate": round(100.0 * errors / effective, 2) if effective else 0.0,
                "success_rate": round(100.0 * (row["ok"] or 0) / effective, 2) if effective else 100.0,
                "avg_latency": round(row["avg_rt"] or 0, 3),
                "p50_latency": pct(rts, 50),
                "p90_latency": pct(rts, 90),
                "p95_latency": pct(rts, 95),
                "p99_latency": pct(rts, 99),
                "max_latency": round(max(rts), 3) if rts else 0,
                "avg_ttfb": round(sum(ttfbs) / len(ttfbs), 3) if ttfbs else 0,
                "p50_ttfb": pct(ttfbs, 50),
                "p95_ttfb": pct(ttfbs, 95),
                "retried": row["retried"] or 0,
                "failover_rate": round(100.0 * (row["retried"] or 0) / reqs, 2) if reqs else 0.0,
                "attempts_total": row["attempts_total"] or 0,
                "attempts_per_request": round((row["attempts_total"] or 0) / reqs, 3) if reqs else 0.0,
                "streaming": row["streaming"] or 0,
                "streaming_rate": round(100.0 * (row["streaming"] or 0) / reqs, 2) if reqs else 0.0,
                "sessions": row["sess"] or 0,
                "avg_conn_requests": round(row["avg_creq"] or 0, 2),
                "models": row["models"] or 0,
                "clients": row["ips"] or 0,
                "clients_today": clients_today or 0,
                "new_clients": new_clients or 0,
                "peak_hour": (peak["hour"][-2:] + ":00") if peak else None,
                "peak_hour_requests": (peak["c"] if peak else 0),
                "totals": {"requests": allrow["reqs"] or 0, "bytes_out": allrow["bout"] or 0},
            })

        if name == "daily":
            return self._send(200, {"daily": q_daily(since, days, fargs)})

        if name == "hourly":
            today = datetime.now(TZ).strftime("%Y-%m-%d")
            rows = conn.execute(
                "SELECT hour, COUNT(*) reqs, COALESCE(SUM(bytes_out),0) bout, AVG(ttfb) avg_ttfb, "
                "SUM(CASE WHEN status < 400 THEN 1 ELSE 0 END) ok FROM requests WHERE day = ?" + frag
                + " GROUP BY hour", (today,) + fargs).fetchall()
            data = {r["hour"]: r for r in rows}
            out = []
            for i in range(24):
                key = f"{today} {i:02d}"
                r = data.get(key)
                out.append({"hour": f"{i:02d}:00", "requests": (r["reqs"] if r else 0),
                            "bytes_out": (r["bout"] if r else 0),
                            "avg_ttfb": round(r["avg_ttfb"], 3) if r and r["avg_ttfb"] else 0,
                            "errors": ((r["reqs"] - r["ok"]) if r else 0)})
            return self._send(200, {"hourly": out})

        if name == "models":
            return self._send(200, {"models": q_models(since, fargs)})

        if name == "routes":
            rows = conn.execute(
                "SELECT route, COUNT(*) reqs, COALESCE(SUM(bytes_out),0) bout, AVG(rt) avg_rt, AVG(ttfb) avg_ttfb, "
                "SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END) retried, "
                "SUM(CASE WHEN status < 400 THEN 1 ELSE 0 END) ok FROM requests WHERE day >= ?"
                + frag + " GROUP BY route", (since,) + fargs).fetchall()
            return self._send(200, {"routes": [dict(r) for r in rows]})

        if name == "status":
            rows = conn.execute(
                "SELECT status, COUNT(*) c, COALESCE(SUM(attempts),0) attempts FROM requests WHERE day >= ?"
                + frag + " GROUP BY status ORDER BY status", (since,) + fargs).fetchall()
            return self._send(200, {"status": [dict(r) for r in rows]})

        if name == "endpoints":
            rows = conn.execute(
                "SELECT uri, COUNT(*) c, COALESCE(SUM(bytes_out),0) bout, AVG(rt) avg_rt, AVG(ttfb) avg_ttfb "
                "FROM requests WHERE day >= ?" + frag
                + " GROUP BY uri ORDER BY c DESC LIMIT 20", (since,) + fargs).fetchall()
            return self._send(200, {"endpoints": [
                {"uri": r["uri"], "requests": r["c"], "bytes_out": r["bout"],
                 "avg_latency": round(r["avg_rt"] or 0, 3),
                 "avg_ttfb": round(r["avg_ttfb"] or 0, 3)} for r in rows]})

        if name == "latency":
            rows = conn.execute(
                "SELECT rt, ttfb FROM requests WHERE day >= ? AND rt IS NOT NULL" + frag + " LIMIT 200000",
                (since,) + fargs).fetchall()
            buckets = [0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60, 120, 300, 600, 1e9]
            labels = ["<50ms", "50-100ms", "100-250ms", "250-500ms", "0.5-1s", "1-2s", "2-5s", "5-10s",
                      "10-30s", "30-60s", "1-2min", "2-5min", "5-10min", ">10min"]
            counts = [0] * len(buckets)
            tcounts = [0] * len(buckets)
            for r in rows:
                for i, edge in enumerate(buckets):
                    if r["rt"] < edge:
                        counts[i] += 1
                        break
                if r["ttfb"] is not None:
                    for i, edge in enumerate(buckets):
                        if r["ttfb"] < edge:
                            tcounts[i] += 1
                            break
            return self._send(200, {"histogram": [{"bucket": labels[i], "count": counts[i],
                                                   "ttfb": tcounts[i]} for i in range(len(labels))]})

        if name == "ttfb":
            rows = conn.execute(
                "SELECT ttfb, rt, day, route FROM requests WHERE day >= ? AND ttfb IS NOT NULL" + frag
                + " LIMIT 200000", (since,) + fargs).fetchall()
            vals = [r["ttfb"] for r in rows]
            by_route = {}
            daily = {}
            for r in rows:
                by_route.setdefault(r["route"], []).append(r["ttfb"])
                daily.setdefault(r["day"], []).append(r["ttfb"])
            edges = [0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60]
            labels = ["<0.1s", "0.1-0.25s", "0.25-0.5s", "0.5-1s", "1-2s", "2-5s", "5-10s", "10-30s", "30-60s", ">60s"]
            counts = [0] * len(labels)
            for v in vals:
                for i, edge in enumerate(edges):
                    if v < edge:
                        counts[i] += 1
                        break
                else:
                    counts[-1] += 1
            total_lat = [r["rt"] for r in rows]
            return self._send(200, {
                "histogram": [{"bucket": labels[i], "count": counts[i]} for i in range(len(labels))],
                "by_route": [{"route": k,
                              "p50": pct(v, 50), "p95": pct(v, 95),
                              "avg": round(sum(v) / len(v), 3), "count": len(v)}
                             for k, v in sorted(by_route.items())],
                "daily": [{"day": d, "avg_ttfb": round(sum(v) / len(v), 3), "count": len(v)}
                          for d, v in sorted(daily.items())],
                "overall": {"p50": pct(vals, 50), "p95": pct(vals, 95), "p99": pct(vals, 99),
                            "avg": round(sum(vals) / len(vals), 3) if vals else 0,
                            "avg_total": round(sum(total_lat) / len(total_lat), 3) if total_lat else 0,
                            "samples": len(vals)},
            })

        if name == "failover":
            daily = conn.execute(
                "SELECT day, COUNT(*) reqs, SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END) retried, "
                "COALESCE(SUM(COALESCE(attempts, 1)),0) attempts, "
                "SUM(CASE WHEN status >= 500 THEN 1 ELSE 0 END) failed "
                "FROM requests WHERE day >= ?" + frag + " GROUP BY day", (since,) + fargs).fetchall()
            dmap = {r["day"]: r for r in daily}
            out = []
            for d in day_list(days):
                r = dmap.get(d)
                out.append({"day": d, "requests": (r["reqs"] if r else 0),
                            "retried": (r["retried"] if r else 0),
                            "first_try": ((r["reqs"] - r["retried"]) if r else 0),
                            "attempts": (r["attempts"] if r else 0),
                            "failed": (r["failed"] if r else 0)})
            ups = conn.execute(
                "SELECT substr(upstream_status, 1, 40) us, COUNT(*) c FROM requests "
                "WHERE day >= ? AND upstream_status IS NOT NULL AND upstream_status != ''" + frag
                + " GROUP BY us ORDER BY c DESC LIMIT 12", (since,) + fargs).fetchall()
            addrs = conn.execute(
                "SELECT upstream_addr, COUNT(*) c FROM requests WHERE day >= ? AND upstream_addr != ''" + frag
                + " GROUP BY upstream_addr ORDER BY c DESC LIMIT 10", (since,) + fargs).fetchall()
            rows = [dict(r) for r in conn.execute(
                "SELECT ts, model, route, status, attempts, upstream_status, rt FROM requests "
                "WHERE day >= ? AND attempts > 1" + frag + " ORDER BY ts DESC LIMIT 40", (since,) + fargs)]
            recent = [{"time": datetime.fromtimestamp(r["ts"], TZ).isoformat(timespec="seconds"),
                       "model": r["model"], "route": r["route"], "status": r["status"],
                       "attempts": r["attempts"], "upstream_status": r["upstream_status"],
                       "latency": round(r["rt"] or 0, 3)} for r in rows]
            return self._send(200, {"daily": out,
                                    "upstream_status": [dict(r) for r in ups],
                                    "upstreams": [{"addr": r["upstream_addr"], "requests": r["c"]} for r in addrs],
                                    "recent": recent})

        if name == "connections":
            rows = conn.execute(
                "SELECT creq, COUNT(*) c FROM requests WHERE day >= ? AND creq IS NOT NULL" + frag
                + " GROUP BY creq", (since,) + fargs).fetchall()
            dict_rows = {r["creq"]: r["c"] for r in rows}
            labels, counts = [], []
            for lo, hi, label in ((1, 1, "1"), (2, 3, "2-3"), (4, 10, "4-10"), (11, 50, "11-50"),
                                  (51, 200, "51-200"), (201, 10 ** 9, ">200")):
                labels.append(label)
                counts.append(sum(c for k, c in dict_rows.items() if lo <= k <= hi))
            total = sum(counts)
            reuse = sum(c for k, c in dict_rows.items() if k > 1)
            return self._send(200, {
                "buckets": [{"bucket": labels[i], "count": counts[i]} for i in range(len(labels))],
                "avg_requests_per_conn": round(sum(k * c for k, c in dict_rows.items()) / total, 2) if total else 0,
                "max_requests_per_conn": max(dict_rows) if dict_rows else 0,
                "reuse_rate": round(100.0 * reuse / total, 2) if total else 0.0,
                "connections": len(dict_rows),
            })

        if name == "streaming":
            daily = conn.execute(
                "SELECT day, SUM(CASE WHEN ctype LIKE 'text/event-stream%' THEN 1 ELSE 0 END) s, "
                "COUNT(*) c FROM requests WHERE day >= ? AND ctype IS NOT NULL" + frag + " GROUP BY day",
                (since,) + fargs).fetchall()
            dmap = {r["day"]: r for r in daily}
            out = []
            for d in day_list(days):
                r = dmap.get(d)
                s = (r["s"] if r else 0) or 0
                c = (r["c"] if r else 0) or 0
                out.append({"day": d, "streaming": s, "other": c - s})
            dur = conn.execute(
                "SELECT rt FROM requests WHERE day >= ? AND ctype LIKE 'text/event-stream%'" + frag
                + " LIMIT 100000", (since,) + fargs).fetchall()
            edges = [1, 2, 5, 10, 30, 60, 300]
            labels = ["<1s", "1-2s", "2-5s", "5-10s", "10-30s", "30-60s", "1-5min", ">5min"]
            counts = [0] * len(labels)
            for r in dur:
                for i, edge in enumerate(edges):
                    if r["rt"] < edge:
                        counts[i] += 1
                        break
                else:
                    counts[-1] += 1
            by_model = conn.execute(
                "SELECT model, SUM(CASE WHEN ctype LIKE 'text/event-stream%' THEN 1 ELSE 0 END) s, "
                "COUNT(*) c FROM requests WHERE day >= ? AND ctype IS NOT NULL" + frag
                + " GROUP BY model ORDER BY c DESC LIMIT 12", (since,) + fargs).fetchall()
            return self._send(200, {
                "daily": out,
                "duration": [{"bucket": labels[i], "count": counts[i]} for i in range(len(labels))],
                "by_model": [{"model": r["model"], "streaming": r["s"] or 0, "total": r["c"]} for r in by_model],
            })

        if name == "sessions":
            daily = conn.execute(
                "SELECT day, COUNT(DISTINCT sess) s, COUNT(*) c FROM requests "
                "WHERE day >= ? AND sess IS NOT NULL" + frag + " GROUP BY day", (since,) + fargs).fetchall()
            dmap = {r["day"]: r for r in daily}
            out = [{"day": d,
                    "sessions": (dmap[d]["s"] if d in dmap else 0),
                    "requests": (dmap[d]["c"] if d in dmap else 0)} for d in day_list(days)]
            top = conn.execute(
                "SELECT sess, COUNT(*) c, COUNT(DISTINCT model) m, COUNT(DISTINCT ip) ips, "
                "MIN(ts) first_ts, MAX(ts) last_ts FROM requests WHERE day >= ? AND sess IS NOT NULL" + frag
                + " GROUP BY sess ORDER BY c DESC LIMIT 15", (since,) + fargs).fetchall()
            per = conn.execute(
                "SELECT c FROM (SELECT COUNT(*) c FROM requests WHERE day >= ? AND sess IS NOT NULL" + frag
                + " GROUP BY sess)", (since,) + fargs).fetchall()
            edges = [2, 6, 21, 101, 501]
            labels = ["1", "2-5", "6-20", "21-100", "101-500", ">500"]
            counts = [0] * len(labels)
            for r in per:
                for i, edge in enumerate(edges):
                    if r["c"] < edge:
                        counts[i] += 1
                        break
                else:
                    counts[-1] += 1
            return self._send(200, {
                "daily": out,
                "distribution": [{"bucket": labels[i], "count": counts[i]} for i in range(len(labels))],
                "top": [{"session": r["sess"][:18], "requests": r["c"], "models": r["m"], "clients": r["ips"],
                         "first": datetime.fromtimestamp(r["first_ts"], TZ).isoformat(timespec="seconds"),
                         "last": datetime.fromtimestamp(r["last_ts"], TZ).isoformat(timespec="seconds")}
                        for r in top],
            })

        if name == "sizes":
            req_sizes = [r["bytes_in"] for r in conn.execute(
                "SELECT bytes_in FROM requests WHERE day >= ?" + frag + " LIMIT 200000", (since,) + fargs)]
            out_sizes = [r["bytes_out"] for r in conn.execute(
                "SELECT bytes_out FROM requests WHERE day >= ?" + frag + " LIMIT 200000", (since,) + fargs)]
            return self._send(200, {
                "request": bucketize(req_sizes, SIZE_EDGES),
                "response": bucketize(out_sizes, SIZE_EDGES),
                "avg_request": round(sum(req_sizes) / len(req_sizes)) if req_sizes else 0,
                "avg_response": round(sum(out_sizes) / len(out_sizes)) if out_sizes else 0,
                "max_request": max(req_sizes) if req_sizes else 0,
                "max_response": max(out_sizes) if out_sizes else 0,
            })

        if name == "weekday":
            rows = conn.execute(
                "SELECT CAST(strftime('%w', day) AS INTEGER) w, CAST(substr(hour, 12, 2) AS INTEGER) h, "
                "COUNT(*) c FROM requests WHERE day >= ?" + frag + " GROUP BY w, h", (since,) + fargs).fetchall()
            grid = [[0] * 24 for _ in range(7)]
            for r in rows:
                if r["w"] is not None and r["h"] is not None:
                    grid[r["w"]][r["h"]] += r["c"]
            cells = [[h, w, grid[w][h]] for w in range(7) for h in range(24)]
            return self._send(200, {"cells": cells, "max": max((c[2] for c in cells), default=0)})

        if name == "clients":
            try:
                limit = min(100, max(1, int(params.get("limit", ["60"])[0])))
            except ValueError:
                limit = 60
            since_ts = datetime.strptime(since, "%Y-%m-%d").replace(tzinfo=TZ).timestamp()
            return self._send(200, q_clients(conn, since, since_ts, limit))

        if name == "client-detail":
            ip = (params.get("ip", [""])[0] or "").strip()
            if not re.fullmatch(r"[0-9A-Za-z:._\-]{3,64}", ip):
                return self._send(400, {"error": "invalid ip"})
            return self._send(200, q_client_detail(conn, ip, since, days))

        if name == "compare":
            return self._send(200, q_compare(conn, days, frag, fargs))

        if name == "recent":
            try:
                limit = min(100, max(1, int(params.get("limit", ["25"])[0])))
            except ValueError:
                limit = 25
            rows = conn.execute(
                "SELECT ts, ip, model, route, method, uri, status, bytes_out, bytes_in, rt, ttfb, sess, ctype "
                "FROM requests WHERE day >= ?" + frag + " ORDER BY ts DESC LIMIT ?",
                (since,) + fargs + (limit,)).fetchall()
            return self._send(200, {"recent": [{
                "time": datetime.fromtimestamp(r["ts"], TZ).isoformat(timespec="seconds"),
                "ip": r["ip"], "model": r["model"], "route": r["route"], "method": r["method"],
                "uri": r["uri"], "status": r["status"], "bytes_out": r["bytes_out"],
                "bytes_in": r["bytes_in"], "latency": round(r["rt"] or 0, 3),
                "ttfb": round(r["ttfb"], 3) if r["ttfb"] is not None else None,
                "session": (r["sess"] or "")[:18],
                "streaming": bool((r["ctype"] or "").startswith("text/event-stream")),
            } for r in rows]})

        if name == "client-origins":
            labels = client_labels()
            try:
                lim = max(1, min(1000, int(num((params.get("limit") or [""])[0], 200) or 200)))
            except (TypeError, ValueError):
                lim = 200
            rows = conn.execute(
                "SELECT ip, class, xff, ua, first_ts, last_ts, hits FROM client_src"
                " ORDER BY last_ts DESC LIMIT ?", (lim,)).fetchall()
            items, by_class = [], {}
            for r in rows:
                cls = r["class"] or src_class(r["ip"])
                by_class[cls] = by_class.get(cls, 0) + (r["hits"] or 0)
                items.append({"ip": r["ip"], "class": cls, "xff": r["xff"] or "",
                              "ua": (r["ua"] or "")[:120], "label": labels.get(r["ip"], ""),
                              "hits": r["hits"] or 0, "first_ts": r["first_ts"],
                              "last_ts": r["last_ts"]})
            return self._send(200, {
                "ok": True, "clients": items, "by_class": by_class,
                "labels_path": CLIENT_LABELS_PATH,
                # 说清楚为什么没有 MAC：不是没做，是这台机器上做不出来。
                "note": "本机唯一网卡是阿里云 VPC 地址，ARP 中所有邻居都解析为 vRouter 的合成 MAC"
                        " ee:ff:ff:ff:ff:ff，且二层帧不跨路由 —— MAC 无法获得。内网地址仅在客户端"
                        "自己的网关带上 X-Forwarded-For 时可见。",
            })

        if name == "useragents":
            rows = conn.execute(
                "SELECT ua, COUNT(*) c, COALESCE(SUM(bytes_out),0) bout, COALESCE(SUM(bytes_in),0) bin_, "
                "GROUP_CONCAT(DISTINCT ip) ip_list, COUNT(DISTINCT sess) sess, "
                "SUM(CASE WHEN status<400 THEN 1 ELSE 0 END) ok, MAX(ts) last_ts "
                "FROM requests WHERE day >= ?" + frag + " GROUP BY ua", (since,) + fargs).fetchall()
            fam = {}
            for r in rows:
                f = ua_family(r["ua"])
                e = fam.setdefault(f, {"family": f, "requests": 0, "bytes_out": 0, "bytes_in": 0,
                                       "ips": set(), "sessions": 0, "ok": 0, "samples": [], "last_ts": 0})
                e["requests"] += r["c"]
                e["bytes_out"] += r["bout"]
                e["bytes_in"] += r["bin_"]
                e["sessions"] += r["sess"] or 0
                e["ok"] += r["ok"] or 0
                e["last_ts"] = max(e["last_ts"], r["last_ts"] or 0)
                if r["ip_list"]:
                    e["ips"].update(r["ip_list"].split(","))
                if len(e["samples"]) < 4 and r["ua"]:
                    e["samples"].append(r["ua"][:80])
            out = []
            for e in fam.values():
                e["success_rate"] = round(100.0 * e["ok"] / e["requests"], 2) if e["requests"] else 100.0
                e["clients"] = len(e["ips"])
                e["last_seen"] = (datetime.fromtimestamp(e["last_ts"], TZ).isoformat(timespec="seconds")
                                  if e["last_ts"] else None)
                del e["last_ts"], e["ips"]
                out.append(e)
            out.sort(key=lambda x: x["requests"], reverse=True)
            return self._send(200, {"user_agents": out})

        if name == "methods":
            rows = conn.execute(
                "SELECT method, COUNT(*) c, COALESCE(SUM(bytes_out),0) bout, COALESCE(SUM(bytes_in),0) bin_, "
                "SUM(CASE WHEN status<400 THEN 1 ELSE 0 END) ok, COUNT(DISTINCT ip) ips "
                "FROM requests WHERE day >= ?" + frag + " GROUP BY method ORDER BY c DESC", (since,) + fargs).fetchall()
            return self._send(200, {"methods": [{"method": r["method"], "requests": r["c"],
                                                 "bytes_out": r["bout"], "bytes_in": r["bin_"],
                                                 "clients": r["ips"], "errors": r["c"] - (r["ok"] or 0)}
                                                for r in rows]})

        if name == "protocols":
            rows = conn.execute(
                "SELECT COALESCE(NULLIF(proto,''), '未知') p, COUNT(*) c, COALESCE(SUM(bytes_out),0) bout "
                "FROM requests WHERE day >= ?" + frag + " GROUP BY p ORDER BY c DESC", (since,) + fargs).fetchall()
            return self._send(200, {"protocols": [{"protocol": r["p"], "requests": r["c"],
                                                   "bytes_out": r["bout"]} for r in rows]})

        if name == "content-types":
            rows = conn.execute(
                "SELECT COALESCE(NULLIF(ctype,''), '(无)') ct, COUNT(*) c, COALESCE(SUM(bytes_out),0) bout, "
                "AVG(rt) avg_rt FROM requests WHERE day >= ?" + frag
                + " GROUP BY ct ORDER BY c DESC LIMIT 12", (since,) + fargs).fetchall()
            return self._send(200, {"content_types": [{"ctype": r["ct"], "requests": r["c"],
                                                       "bytes_out": r["bout"],
                                                       "avg_latency": round(r["avg_rt"] or 0, 3)}
                                                      for r in rows]})

        if name == "models-daily":
            try:
                topn = min(12, max(1, int(params.get("top", ["6"])[0])))
            except ValueError:
                topn = 6
            top = conn.execute(
                "SELECT model, COUNT(*) c FROM requests WHERE day >= ?" + frag +
                " GROUP BY model ORDER BY c DESC LIMIT ?", (since,) + fargs + (topn,)).fetchall()
            names = [r["model"] for r in top]
            data_rows = conn.execute(
                "SELECT day, model, COUNT(*) c, COALESCE(SUM(bytes_out),0) bout FROM requests WHERE day >= ?"
                + frag + " GROUP BY day, model", (since,) + fargs).fetchall()
            by_day = {}
            for r in data_rows:
                by_day.setdefault(r["day"], {})[r["model"]] = (r["c"], r["bout"])
            series = {nm: {"requests": [], "bytes_out": []} for nm in names}
            for d in day_list(days):
                for nm in names:
                    c, b = by_day.get(d, {}).get(nm, (0, 0))
                    series[nm]["requests"].append(c)
                    series[nm]["bytes_out"].append(b)
            return self._send(200, {"days": day_list(days), "series": series, "models": names})

        if name == "errors":
            try:
                limit = min(300, max(1, int(params.get("limit", ["30"])[0])))
            except ValueError:
                limit = 30
            return self._send(200, {"errors": q_errors(since, fargs, limit)})

        if name == "busy-hours":
            at = (params.get("at") or [""])[0]
            try:
                return self._send(200, busy_state(at or None))
            except ValueError as exc:          # bad ?at= is a caller error
                return self._send(400, {"error": str(exc)})
            except Exception as exc:
                return self._send(500, {"error": "%s: %s" % (type(exc).__name__, exc)})

        if name == "selfheal":
            if not os.path.exists(SELFHEAL_PATH):
                return self._send(200, {"available": False, "hint": "自愈守护尚未运行过"})
            try:
                with open(SELFHEAL_PATH, encoding="utf-8") as f:
                    return self._send(200, json.load(f))
            except (OSError, ValueError) as exc:
                return self._send(200, {"available": False, "error": str(exc)})

        if name == "export":
            table = params.get("table", ["daily"])[0]
            export_days = self._days(params, 7)
            start = day_list(export_days)[0]
            buf = io.StringIO()
            writer = csv.writer(buf)
            if table == "models":
                writer.writerow(["model", "route", "requests", "success_rate", "errors", "avg_latency_s",
                                 "p95_latency_s", "avg_ttfb_s", "retried", "sessions", "bytes_out"])
                for m in q_models(start, fargs):
                    writer.writerow([m["model"], m["route"], m["requests"], m["success_rate"], m["errors"],
                                     m["avg_latency"], m["p95_latency"], m["avg_ttfb"], m["retried"],
                                     m["sessions"], m["bytes_out"]])
            elif table == "errors":
                writer.writerow(["time", "model", "route", "uri", "status", "latency_s", "ttfb_s", "attempts",
                                 "upstream_status", "session"])
                for e in q_errors(start, fargs, 500):
                    writer.writerow([e["time"], e["model"], e["route"], e["uri"], e["status"], e["latency"],
                                     e["ttfb"] if e["ttfb"] is not None else "", e["attempts"],
                                     e["upstream_status"] or "", e["session"]])
            elif table == "clients":
                since_ts = datetime.strptime(start, "%Y-%m-%d").replace(tzinfo=TZ).timestamp()
                writer.writerow(["ip", "requests", "share_pct", "requests_today", "sessions", "models",
                                 "days_active", "success_rate_pct", "errors", "avg_latency_s", "p95_latency_s",
                                 "avg_ttfb_s", "bytes_in", "bytes_out", "top_model", "first_seen", "last_seen"])
                cconn = connect()
                try:
                    data = q_clients(cconn, start, since_ts, 1000)["clients"]
                finally:
                    cconn.close()
                for c in data:
                    writer.writerow([c["ip"], c["requests"], c["share"], c["requests_today"], c["sessions"],
                                     c["models"], c["days"], c["success_rate"], c["errors"], c["avg_latency"],
                                     c["p95_latency"], c["avg_ttfb"], c["bytes_in"], c["bytes_out"],
                                     c["top_model"], c["first_seen"], c["last_seen"]])
            else:
                writer.writerow(["day", "requests", "errors", "errors_4xx", "errors_5xx", "success_rate",
                                 "retried", "attempts", "sessions", "clients", "models",
                                 "avg_latency_s", "avg_ttfb_s", "bytes_in", "bytes_out"])
                for d in q_daily(start, export_days, fargs):
                    writer.writerow([d["day"], d["requests"], d["errors"], d["errors_4xx"], d["errors_5xx"],
                                     d["success_rate"], d["retried"], d["attempts"], d["sessions"],
                                     d["clients"], d["models"], d["avg_latency"], d["avg_ttfb"],
                                     d["bytes_in"], d["bytes_out"]])
            # BOM so Excel opens the UTF-8 CSV correctly
            return self._send(200, "\ufeff" + buf.getvalue(), ctype="text/csv; charset=utf-8")

        if name == "model-status":
            if not os.path.exists(STATUS_PATH):
                return self._send(200, {"error": "model status not generated yet"})
            with open(STATUS_PATH, "r", encoding="utf-8") as f:
                return self._send(200, json.load(f))

        if name == "quota":
            return self._send(200, fetch_quota(force=False))

        if name == "session-memory":
            limit = 60
            try:
                limit = max(1, min(500, int(params.get("limit", [60])[0])))
            except ValueError:
                pass
            return self._send(200, session_memory(conn, since, limit))

        if name == "session-turns":
            sess = (params.get("sess", [""])[0] or "").strip()
            if not sess:
                return self._send(400, {"error": "missing sess"})
            fmt = (params.get("format", ["json"])[0] or "json").lower()
            if fmt in ("md", "markdown", "json", "csv"):
                body, ctype, err = session_export(conn, sess, fmt)
                if err:
                    return self._send(200, {"error": err, "session": sess})
                ext = {"md": "md", "markdown": "md", "json": "json", "csv": "csv"}[fmt]
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Disposition",
                                 f'attachment; filename="{sess[:40]}-memory.{ext}"')
                self.send_header("Content-Length", str(len(body.encode("utf-8"))))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body.encode("utf-8"))
                return
            return self._send(200, session_turns(conn, sess))

        if name == "tokens":
            return self._send(200, token_stats(conn, since))

        if name == "providers":
            if not keys_enabled():
                return self._send(200, {"available": False, "error": "key store 未加载"})
            out = {"available": True, "providers": []}
            for prov in keystore.PROVIDERS:
                st = keystore.status(prov)
                out["providers"].append({"id": prov, "label": st["label"],
                                         "windows": st["windows"],
                                         "active_id": st["active_id"],
                                         "exhausted": st["exhausted"],
                                         "key_count": len(st["keys"]),
                                         "usable": len([k for k in st["keys"]
                                                        if k["enabled"] and k["state"] != "exhausted"])})
            return self._send(200, out)

        if name == "keys":
            if not keys_enabled():
                return self._send(200, {"available": False, "error": "key store 未加载"})
            prov = (params.get("provider", ["opencode-go"])[0] or "opencode-go").strip()
            if prov not in keystore.PROVIDERS:
                return self._send(400, {"error": f"未知 provider: {prov}"})
            payload = keystore.status(prov)
            payload["available"] = True
            return self._send(200, payload)

        if name == "key":
            key = read_upstream_key()
            stamp = None
            if key and os.path.exists(UPSTREAM_AUTH):
                stamp = datetime.fromtimestamp(os.path.getmtime(UPSTREAM_AUTH), TZ).isoformat(timespec="seconds")
            return self._send(200, {"key_masked": mask_key(key), "configured": bool(key), "updated_at": stamp})

        return self._send(404, {"error": "unknown endpoint"})



# ── multi-key management ────────────────────────────────────────────────────
_KEY_WATCH_LOCK = threading.Lock()
_KEY_LAST_WATCH = [0.0]
# A 429 means the active key just ran out; re-checking is cheap but not free.
QUOTA_WATCH_COOLDOWN = 20.0


def keys_enabled():
    return keystore is not None


def trigger_quota_watch(rows):
    """If this batch contained an upstream 429, re-check quota and switch keys.

    Always off the ingest thread: tailing must never block on an HTTP call.
    """
    if keystore is None:
        return
    hit = {("glm" if (len(r) > 8 and str(r[8]).startswith("/glm/")) else "opencode-go")
           for r in rows if len(r) > 7 and r[7] == 429}
    if not hit:
        return
    with _KEY_WATCH_LOCK:
        if time.time() - _KEY_LAST_WATCH[0] < QUOTA_WATCH_COOLDOWN:
            return
        _KEY_LAST_WATCH[0] = time.time()

    def target():
        for prov in hit:
            try:
                keystore.mark_active_exhausted("upstream 429", provider=prov)
            except Exception:
                pass
    threading.Thread(target=target, daemon=True).start()


def key_poll_loop(stop):
    """Keep per-key usage fresh; switch the active key when one is exhausted."""
    if keystore is None:
        return
    try:
        for _prov in keystore.PROVIDERS:
            keystore.ensure_config(provider=_prov)
    except Exception:
        pass
    while not stop.is_set():
        try:
            keystore.sync_all()
        except Exception:
            pass
        stop.wait(60)


# ── token usage (written by /opt/relay/usagetap.py) ─────────────────────────
def token_stats(conn, since):
    """Aggregate the tap's per-request token usage.

    input_tokens is normalised by the tap to mean *uncached* input for both
    protocols (OpenAI's prompt_tokens includes cached tokens, Anthropic reports
    them separately), so input + cache_read + cache_write + output is the whole
    picture for every request.
    """
    try:
        conn.execute("SELECT 1 FROM usage_events LIMIT 1")
    except sqlite3.OperationalError:
        return {"available": False,
                "error": "usage_events 尚未建立：token 采集（usagetap）还没跑过"}

    row = conn.execute(
        "SELECT COUNT(*) n,"
        " SUM(CASE WHEN total_tokens IS NOT NULL THEN 1 ELSE 0 END) measured,"
        " SUM(CASE WHEN total_tokens IS NULL THEN 1 ELSE 0 END) unmeasured,"
        " SUM(COALESCE(input_tokens,0)) inp,"
        " SUM(COALESCE(output_tokens,0)) outp,"
        " SUM(COALESCE(cache_read,0)) cr,"
        " SUM(COALESCE(cache_write,0)) cw,"
        " SUM(COALESCE(reasoning,0)) rsn,"
        " SUM(COALESCE(total_tokens,0)) total,"
        " SUM(COALESCE(streaming,0)) streaming,"
        " SUM(COALESCE(client_gone,0)) gone,"
        " AVG(CASE WHEN total_tokens IS NOT NULL THEN total_tokens END) avgr,"
        " MAX(total_tokens) maxr"
        " FROM usage_events WHERE day >= ?", (since,)).fetchone()

    out = {
        "available": True,
        "since": since,
        "requests": row["n"] or 0,
        "measured": row["measured"] or 0,
        "unmeasured": row["unmeasured"] or 0,
        "coverage": round(100.0 * (row["measured"] or 0) / row["n"], 1) if row["n"] else 0.0,
        "input": row["inp"] or 0,
        "output": row["outp"] or 0,
        "cache_read": row["cr"] or 0,
        "cache_write": row["cw"] or 0,
        "reasoning": row["rsn"] or 0,
        "total": row["total"] or 0,
        "streaming": row["streaming"] or 0,
        "client_gone": row["gone"] or 0,
        "avg_per_request": round(row["avgr"] or 0, 1),
        "max_request": row["maxr"] or 0,
    }
    out["cache_hit_rate"] = round(
        100.0 * out["cache_read"] / (out["input"] + out["cache_read"]), 2
    ) if (out["input"] + out["cache_read"]) else 0.0
    out["output_share"] = round(100.0 * out["output"] / out["total"], 1) if out["total"] else 0.0
    out["reasoning_share"] = round(100.0 * out["reasoning"] / out["output"], 1) if out["output"] else 0.0

    out["daily"] = [dict(r) for r in conn.execute(
        "SELECT day, COUNT(*) n,"
        " SUM(CASE WHEN total_tokens IS NOT NULL THEN 1 ELSE 0 END) measured,"
        " SUM(COALESCE(input_tokens,0)) input,"
        " SUM(COALESCE(output_tokens,0)) output,"
        " SUM(COALESCE(cache_read,0)) cache_read,"
        " SUM(COALESCE(reasoning,0)) reasoning,"
        " SUM(COALESCE(total_tokens,0)) total"
        " FROM usage_events WHERE day >= ? GROUP BY day ORDER BY day", (since,))]

    out["models"] = [dict(r) for r in conn.execute(
        "SELECT COALESCE(model,'unknown') model, COUNT(*) n,"
        " SUM(COALESCE(input_tokens,0)) input,"
        " SUM(COALESCE(output_tokens,0)) output,"
        " SUM(COALESCE(cache_read,0)) cache_read,"
        " SUM(COALESCE(reasoning,0)) reasoning,"
        " SUM(COALESCE(total_tokens,0)) total,"
        " AVG(CASE WHEN total_tokens IS NOT NULL THEN total_tokens END) avg_total"
        " FROM usage_events WHERE day >= ? GROUP BY model ORDER BY total DESC LIMIT 15", (since,))]

    out["protocols"] = [dict(r) for r in conn.execute(
        "SELECT COALESCE(protocol,'?') protocol, COUNT(*) n,"
        " SUM(CASE WHEN total_tokens IS NOT NULL THEN 1 ELSE 0 END) measured,"
        " SUM(COALESCE(input_tokens,0)) input,"
        " SUM(COALESCE(output_tokens,0)) output,"
        " SUM(COALESCE(cache_read,0)) cache_read,"
        " SUM(COALESCE(total_tokens,0)) total"
        " FROM usage_events WHERE day >= ? GROUP BY protocol ORDER BY total DESC", (since,))]

    out["routes"] = [dict(r) for r in conn.execute(
        "SELECT COALESCE(route,'?') route, COUNT(*) n, SUM(COALESCE(total_tokens,0)) total"
        " FROM usage_events WHERE day >= ? GROUP BY route ORDER BY total DESC", (since,))]

    out["sessions"] = [dict(r) for r in conn.execute(
        "SELECT sess, COUNT(*) n, SUM(COALESCE(total_tokens,0)) total,"
        " MAX(ts) last FROM usage_events WHERE day >= ? AND sess IS NOT NULL AND sess <> ''"
        " GROUP BY sess ORDER BY total DESC LIMIT 10", (since,))]

    out["buckets"] = [dict(r) for r in conn.execute(
        "SELECT CASE"
        " WHEN total_tokens IS NULL THEN '未采集'"
        " WHEN total_tokens < 1000 THEN '<1K'"
        " WHEN total_tokens < 5000 THEN '1-5K'"
        " WHEN total_tokens < 20000 THEN '5-20K'"
        " WHEN total_tokens < 50000 THEN '20-50K'"
        " WHEN total_tokens < 100000 THEN '50-100K'"
        " ELSE '>100K' END bucket, COUNT(*) c,"
        " MIN(CASE WHEN total_tokens IS NULL THEN 999999999 ELSE total_tokens END) ord"
        " FROM usage_events WHERE day >= ? GROUP BY bucket ORDER BY ord", (since,))]

    health = os.path.join(DATA_DIR, "public", "usagetap.json")
    if os.path.exists(health):
        try:
            with open(health, encoding="utf-8") as f:
                out["tap"] = json.load(f)
        except (OSError, ValueError):
            pass
    return out


# ── session memory (usage_events + session_turns from the usage tap) ────────
def session_memory(conn, since, limit=60):
    """Per-session memory metrics: how much each session remembers, on its own."""
    try:
        conn.execute("SELECT 1 FROM session_turns LIMIT 1")
        have_turns = True
    except sqlite3.OperationalError:
        have_turns = False

    rows = [dict(r) for r in conn.execute(
        "SELECT sess, COUNT(*) turns,"
        " SUM(COALESCE(total_tokens,0)) total,"
        " SUM(COALESCE(input_tokens,0)) input,"
        " SUM(COALESCE(output_tokens,0)) output,"
        " SUM(COALESCE(cache_read,0)) cache_read,"
        " MAX(total_tokens) max_ctx,"
        " AVG(CASE WHEN total_tokens IS NOT NULL THEN total_tokens END) avg_ctx,"
        " COUNT(DISTINCT model) models,"
        " COUNT(DISTINCT route) routes,"
        " MIN(ts) first_ts, MAX(ts) last_ts"
        " FROM usage_events WHERE day >= ? AND sess IS NOT NULL AND sess <> ''"
        " GROUP BY sess ORDER BY total DESC LIMIT ?", (since, limit))]

    arch = {}
    if have_turns:
        for r in conn.execute(
                "SELECT sess, COUNT(*) archived, MAX(msgs) max_msgs,"
                " SUM(LENGTH(COALESCE(prompt,'')) + LENGTH(COALESCE(reply,''))) chars"
                " FROM session_turns WHERE day >= ? AND sess IS NOT NULL AND sess <> ''"
                " GROUP BY sess", (since,)):
            arch[r["sess"]] = dict(r)

    for r in rows:
        a = arch.get(r["sess"]) or {}
        r["archived_turns"] = a.get("archived") or 0
        r["max_msgs"] = a.get("max_msgs")
        r["archived_chars"] = a.get("chars") or 0
        base = (r["input"] or 0) + (r["cache_read"] or 0)
        r["cache_hit_rate"] = round(100.0 * (r["cache_read"] or 0) / base, 1) if base else 0.0
        r["latest_context"] = r["max_ctx"] or 0
    return {"since": since, "sessions": rows, "archive_available": have_turns}


def session_turns(conn, sess, limit=300):
    """One session's archived conversation, newest first."""
    try:
        conn.execute("SELECT 1 FROM session_turns LIMIT 1")
    except sqlite3.OperationalError:
        return {"available": False, "error": "会话存档尚未启用（session_turns 表不存在）"}
    rows = [dict(r) for r in conn.execute(
        "SELECT ts, day, route, protocol, model, msgs, sent_bytes, status, prompt, reply"
        " FROM session_turns WHERE sess = ? ORDER BY ts DESC LIMIT ?", (sess, limit))]
    return {"available": True, "session": sess, "count": len(rows), "turns": rows}


def session_export(conn, sess, fmt="md"):
    """Export a session's memory so it can be replayed into a fresh session."""
    data = session_turns(conn, sess, limit=1000)
    if not data.get("available"):
        return None, "text/plain; charset=utf-8", "session archive is not enabled"
    turns = list(reversed(data["turns"]))
    if fmt == "json":
        return json.dumps({"session": sess, "turns": turns}, ensure_ascii=False, indent=2), \
            "application/json; charset=utf-8", None
    if fmt == "csv":
        import csv as _csv
        import io as _io
        buf = _io.StringIO()
        w = _csv.writer(buf)
        w.writerow(["time", "day", "route", "protocol", "model", "messages", "status", "prompt", "reply"])
        for t in turns:
            ts = datetime.fromtimestamp(t["ts"], TZ).strftime("%Y-%m-%d %H:%M:%S") if t["ts"] else ""
            w.writerow([ts, t["day"], t["route"], t["protocol"], t["model"], t["msgs"], t["status"],
                        t["prompt"] or "", t["reply"] or ""])
        return "\ufeff" + buf.getvalue(), "text/csv; charset=utf-8", None

    # markdown: readable transcript, good for seeding a new session
    first = turns[0]["ts"] if turns else None
    last = turns[-1]["ts"] if turns else None
    fmt_ts = lambda v: datetime.fromtimestamp(v, TZ).strftime("%Y-%m-%d %H:%M:%S") if v else "—"
    lines = [f"# 会话记忆导出 · {sess}", "",
             f"- 轮次：{len(turns)}",
             f"- 时间范围：{fmt_ts(first)} → {fmt_ts(last)}",
             f"- 模型：{', '.join(sorted({t['model'] or '?' for t in turns}))}",
             "",
             "> 用途：把这段记忆粘进新会话作为上下文，或作为归档保存。", ""]
    for i, t in enumerate(turns, 1):
        lines.append(f"## 第 {i} 轮 · {fmt_ts(t['ts'])} · {t['model']} · "
                     f"{t['protocol']} · 历史消息 {t['msgs']} 条")
        lines.append("")
        lines.append("**用户**")
        lines.append("")
        lines.append(t["prompt"] or "（未记录）")
        lines.append("")
        lines.append("**助手**")
        lines.append("")
        lines.append(t["reply"] or "（未记录）")
        lines.append("")
    return "\n".join(lines), "text/markdown; charset=utf-8", None


def main():
    conn = connect()
    conn.executescript(SCHEMA)
    cols = {r["name"] for r in conn.execute("PRAGMA table_info(requests)")}
    for col, ctype in MIGRATIONS:
        if col not in cols:
            conn.execute(f"ALTER TABLE requests ADD COLUMN {col} {ctype}")
    conn.commit()
    conn.close()
    stop = threading.Event()
    t = threading.Thread(target=ingest_loop, args=(stop,), daemon=True)
    t.start()
    threading.Thread(target=key_poll_loop, args=(stop,), daemon=True).start()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    print(f"relay-stats listening on {HOST}:{PORT}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()


if __name__ == "__main__":
    main()
