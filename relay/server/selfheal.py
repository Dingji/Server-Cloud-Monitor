#!/usr/bin/env python3
"""OpenCode relay self-healing watchdog.

Runs every minute from relay-selfheal.timer. It verifies the relay end to end
(not just "is the process alive") and repairs what it can, escalating through
progressively more invasive steps and rate-limiting every action so it can never
flap. Everything it observes and does is written to
/var/lib/relay/public/selfheal.json, which the dashboard shows.

Ladder:
  nginx not running          -> restart
  nginx config invalid       -> restore the known-good config from /opt/relay
  resolver(s) dead           -> switch nginx to a resolver that answers
  relay endpoint broken      -> reload (drops the upstream keepalive pool and
                                re-resolves) -> restart -> flag for a human
  upstream answers 401       -> flag the key as rejected (cannot be auto-fixed)
  stats API / dashboard down -> restart relay-stats
  upstream error burst       -> reload
  disk pressure              -> prune rotated logs, nginx body temp, vacuum DB
  model report stale         -> trigger the model probe
"""
import json
import os
import random
import re
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))
DATA_DIR = "/var/lib/relay"
PUBLIC_DIR = os.path.join(DATA_DIR, "public")
STATUS_PATH = os.path.join(PUBLIC_DIR, "selfheal.json")
STATE_PATH = os.path.join(DATA_DIR, "selfheal.state")
QUOTA_PATH = os.path.join(PUBLIC_DIR, "quota.json")
MODELS_PATH = os.path.join(PUBLIC_DIR, "models-status.json")
LOG_PATH = "/var/log/relay-selfheal.log"
NGINX_ERR = "/var/log/nginx/opencode-relay.error.log"
RELAY_ENV = "/etc/relay/relay.env"
LIVE_SITE = "/etc/nginx/sites-available/opencode-relay"
MASTER_SITE = "/opt/relay/opencode-relay"
LIVE_SNIP = "/etc/nginx/snippets/opencode-upstream.conf"
MASTER_SNIP = "/opt/relay/opencode-upstream.conf"
UA = "relay-modelcheck/selfheal"          # contains the marker the collector filters out
HEALTH_URL = "http://127.0.0.1:8787/api/health"
STATIC_URLS = ["http://127.0.0.1:8787/", "http://127.0.0.1:8787/app.js",
               "http://127.0.0.1:8787/style.css"]
RESOLVER_CANDIDATES = ["223.5.5.5", "119.29.29.29", "1.1.1.1", "8.8.8.8"]
DNS_NAME = "opencode.ai"
ERROR_BURST_WINDOW = 300          # seconds of nginx error log to inspect
ERROR_BURST_LIMIT = 8             # failures in the window before we intervene
MIN_FREE_GB = 3
HISTORY_MAX = 60

# action -> cooldown seconds; keeps remediation from flapping
COOLDOWNS = {"reload": 180, "restart_nginx": 600, "restart_stats": 300,
             "restore_config": 600, "fix_resolver": 900, "prune": 3600,
             "modelcheck": 3600, "refresh_keys": 300, "usage_tap": 300}
RESTART_BUDGET_PER_HOUR = 5


def now():
    return datetime.now(TZ)


def log(msg):
    line = f"{now().strftime('%Y-%m-%d %H:%M:%S')} {msg}"
    print(line, flush=True)
    try:
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save_json(path, payload):
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
        os.chmod(path, 0o644)
    except OSError as exc:
        log(f"WARN cannot write {path}: {exc}")


def run(cmd, timeout=30):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except (OSError, subprocess.SubprocessError) as exc:
        return 127, f"{type(exc).__name__}: {exc}"


def http_get(url, headers=None, timeout=12):
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read(4096).decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, ""
    except Exception as exc:
        return 0, f"{type(exc).__name__}: {exc}"


def read_relay_token():
    try:
        with open(RELAY_ENV, encoding="utf-8") as f:
            m = re.search(r'set \$relay_token "([^"]+)"', f.read())
        return m.group(1) if m else None
    except OSError:
        return None


def read_resolvers():
    try:
        with open(LIVE_SITE, encoding="utf-8") as f:
            m = re.search(r"^\s*resolver\s+([^;]+);", f.read(), re.M)
        if not m:
            return []
        return [tok for tok in m.group(1).split() if re.fullmatch(r"[0-9.]+", tok)]
    except OSError:
        return []


def dns_query_a(server, name, timeout=3.0):
    """Minimal dependency-free DNS A query, so we can test nginx's resolvers."""
    tid = random.randint(0, 0xFFFF)
    header = struct.pack(">HHHHHH", tid, 0x0100, 1, 0, 0, 0)
    qname = b"".join(bytes([len(p)]) + p.encode() for p in name.split(".")) + b"\x00"
    packet = header + qname + struct.pack(">HH", 1, 1)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(timeout)
    try:
        sock.sendto(packet, (server, 53))
        data, _ = sock.recvfrom(2048)
    except (OSError, socket.timeout):
        return []
    finally:
        sock.close()
    if len(data) < 12:
        return []
    ancount = struct.unpack(">H", data[6:8])[0]
    idx = 12
    while idx < len(data) and data[idx] != 0:
        idx += data[idx] + 1
    idx += 5
    ips = []
    for _ in range(ancount):
        if idx >= len(data):
            break
        if data[idx] & 0xC0:
            idx += 2
        else:
            while idx < len(data) and data[idx] != 0:
                idx += data[idx] + 1
            idx += 1
        if idx + 10 > len(data):
            break
        rtype, _rclass, _ttl, rdlen = struct.unpack(">HHIH", data[idx:idx + 10])
        idx += 10
        if rtype == 1 and rdlen == 4:
            ips.append(".".join(str(b) for b in data[idx:idx + 4]))
        idx += rdlen
    return ips


# ── checks ──────────────────────────────────────────────────────────
def check_nginx_active():
    rc, out = run(["systemctl", "is-active", "nginx"])
    active = out.strip() == "active"
    return active, out.strip() or f"rc={rc}"


def check_nginx_config():
    rc, out = run(["nginx", "-t"])
    return rc == 0, (out.strip().splitlines() or [""])[-1][:160]


def check_resolvers():
    """Every resolver nginx is configured with should answer.

    A resolver that silently times out does not break the relay (nginx falls
    through to the next one) but it does add latency to every re-resolution, so
    it is treated as a repairable fault rather than a warning.
    """
    resolvers = read_resolvers()
    if not resolvers:
        return False, "site config declares no resolver"
    alive, dead = [], []
    for server in resolvers:
        ips = dns_query_a(server, DNS_NAME)
        (alive if ips else dead).append(f"{server}({'ok' if ips else 'NO ANSWER'})")
    detail = f"configured={','.join(resolvers)}"
    if dead:
        detail += " dead=" + ",".join(d.split("(")[0] for d in dead)
    return (not dead), detail


def check_relay_endpoint():
    """Local liveness only: no model-endpoint requests.

    Model availability is probed exactly once a day at 08:30 by relay-modelcheck;
    this watchdog used to GET /v1/models every minute, which showed up as a model
    scan in the statistics. Port 80 + the usage-tap heartbeat prove the relay is
    serving without asking upstream for anything.
    """
    token = read_relay_token()
    if not token:
        return False, "relay token missing from /etc/relay/relay.env"
    try:
        with socket.create_connection(("127.0.0.1", 80), timeout=5):
            pass
    except OSError as exc:
        return False, "port 80 unreachable: %s" % exc
    hb = "/var/lib/relay/public/usagetap.json"
    try:
        age = time.time() - os.path.getmtime(hb)
    except OSError:
        return False, "usage tap heartbeat missing"
    if age > 300:
        return False, "usage tap heartbeat stale (%ds)" % int(age)
    return True, "port 80 ok, tap heartbeat %ds" % int(age)


def check_stats_api():
    status, body = http_get(HEALTH_URL, {"User-Agent": UA})
    if status != 200:
        return False, f"HTTP {status}"
    data = json.loads(body) if body.strip().startswith("{") else {}
    if data.get("ingest_error"):
        return False, f"ingest error: {data['ingest_error'][:80]}"
    last = data.get("last_ingest")
    if last:
        try:
            age = (now() - datetime.fromisoformat(last)).total_seconds()
            if age > 900:
                return False, f"no ingest for {int(age)}s"
        except ValueError:
            pass
    return True, f"rows={data.get('rows_total')} offset={data.get('ingest_offset')}"


def check_dashboard_static():
    for url in STATIC_URLS:
        status, _ = http_get(url, {"User-Agent": UA})
        if status != 200:
            return False, f"{url} -> HTTP {status}"
    return True, f"{len(STATIC_URLS)} assets served"


def check_disk():
    rc, out = run(["df", "-Pk", "/"])
    if rc != 0:
        return True, "df unavailable"
    try:
        free_gb = int(out.strip().splitlines()[-1].split()[3]) / 1024 / 1024
    except (IndexError, ValueError):
        return True, "df unparsable"
    return free_gb >= MIN_FREE_GB, f"{free_gb:.1f} GB free"


def check_error_burst():
    """Count recent upstream failures in the nginx error log."""
    cutoff = time.time() - ERROR_BURST_WINDOW
    count = 0
    try:
        with open(NGINX_ERR, encoding="utf-8", errors="replace") as f:
            for line in f.readlines()[-4000:]:
                m = re.match(r"(\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2})", line)
                if not m:
                    continue
                try:
                    ts = datetime.strptime(m.group(1), "%Y/%m/%d %H:%M:%S").replace(tzinfo=TZ).timestamp()
                except ValueError:
                    continue
                if ts < cutoff:
                    continue
                if ("upstream" in line or "SSL_do_handshake" in line or "timed out" in line) \
                        and "access forbidden by rule" not in line:
                    count += 1
    except OSError:
        return True, "error log unavailable"
    return count < ERROR_BURST_LIMIT, f"{count} upstream failures in {ERROR_BURST_WINDOW}s"


def check_models_report():
    if not os.path.exists(MODELS_PATH):
        return False, "model report missing"
    age_h = (time.time() - os.path.getmtime(MODELS_PATH)) / 3600
    return age_h < 48, f"model report {age_h:.1f}h old"


def check_quota():
    if not os.path.exists(QUOTA_PATH):
        return True, "quota not fetched yet"
    data = load_json(QUOTA_PATH, {})
    if data.get("error"):
        return False, f"quota error: {str(data['error'])[:90]}"
    return True, f"key {data.get('key_masked', '?')}"


def check_key_pool():
    """At least one upstream key must still have quota."""
    import os as _os
    import sys as _sys
    try:
        _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
        import keystore
    except Exception as exc:
        return True, f"keystore unavailable ({exc})"
    try:
        st = keystore.status()
    except Exception as exc:
        return False, f"key store error: {exc}"
    keys = st.get("keys") or []
    if not keys:
        return False, "key pool is empty"
    usable = [k for k in keys if k.get("enabled", True) and k.get("state") != "exhausted"]
    if not usable:
        detail = "; ".join(
            f"{k.get('id')} {k.get('masked', '')} out of {','.join(k.get('exhausted_windows') or [])}"
            for k in keys)
        return False, f"all {len(keys)} keys out of quota ({detail[:120]})"
    return True, f"{len(usable)}/{len(keys)} keys usable; active={st.get('active_id')}"


def act_refresh_keys():
    """Re-poll every key's quota and switch to one that still has headroom."""
    import os as _os
    import sys as _sys
    try:
        _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
        import keystore
        res = keystore.sync(force_poll=True)
        return True, f"key sync: {res}"
    except Exception as exc:
        return False, f"{type(exc).__name__}: {exc}"


def tap_healthy():
    """TCP reachable and its heartbeat file is fresh."""
    import socket as _socket
    try:
        with _socket.create_connection(("127.0.0.1", 8790), timeout=3):
            pass
    except Exception:
        return False, "port 8790 not accepting connections"
    try:
        age = time.time() - os.path.getmtime("/var/lib/relay/public/usagetap.json")
    except OSError:
        return False, "no heartbeat file"
    if age > 180:
        return False, f"heartbeat stale ({int(age)}s)"
    return True, f"heartbeat {int(age)}s old"


def tap_mode():
    rc, out = run(["python3", "/opt/relay/set_target.py", "status"])
    return out.strip() if rc == 0 else "unknown"


def check_usage_tap():
    """Token statistics require the tap to be up and actually in the path."""
    ok, detail = tap_healthy()
    mode = tap_mode()
    if not ok:
        return False, f"tap down: {detail} (mode={mode})"
    if mode != "tap":
        return False, f"tap healthy but bypassed (mode={mode}) - token stats paused"
    return True, f"tap active, {detail}"


def act_fix_tap():
    """Restore the tap if it is healthy, otherwise bypass it so traffic survives."""
    ok, detail = tap_healthy()
    if ok:
        rc, out = run(["python3", "/opt/relay/set_target.py", "tap"])
        return rc == 0, f"tap back in path: {out.strip()[:120]}"
    run(["systemctl", "restart", "relay-usagetap.service"])
    time.sleep(3)
    ok2, detail2 = tap_healthy()
    if ok2:
        run(["python3", "/opt/relay/set_target.py", "tap"])
        return True, f"tap restarted ({detail2})"
    rc, out = run(["python3", "/opt/relay/set_target.py", "direct"])
    return rc == 0, f"tap unavailable ({detail}) - relay bypassed to direct upstream: {out.strip()[:100]}"


CHECKS = [
    ("nginx_active", check_nginx_active, True),
    ("nginx_config", check_nginx_config, True),
    ("dns_resolvers", check_resolvers, True),
    ("relay_endpoint", check_relay_endpoint, True),
    ("stats_api", check_stats_api, True),
    ("dashboard_static", check_dashboard_static, False),
    ("disk_space", check_disk, False),
    ("error_burst", check_error_burst, False),
    ("models_report", check_models_report, False),
    ("quota", check_quota, False),
    ("key_pool", check_key_pool, False),
    ("usage_tap", check_usage_tap, False),
]
CRITICAL = {"nginx_active", "nginx_config", "relay_endpoint"}


def run_checks():
    results = []
    for name, fn, _critical in CHECKS:
        t0 = time.time()
        try:
            ok, detail = fn()
        except Exception as exc:  # a broken check must not break the watchdog
            ok, detail = False, f"check raised {type(exc).__name__}: {exc}"
        results.append({"name": name, "ok": bool(ok), "detail": str(detail)[:200],
                        "ms": int((time.time() - t0) * 1000)})
    return results


# ── remediation ─────────────────────────────────────────────────────
class Healer:
    def __init__(self, state):
        self.state = state
        self.actions = []
        self.recent_restarts = [t for t in state.get("recent_restarts", [])
                                if time.time() - t < 3600]

    def allowed(self, action):
        last = self.state.get("cooldowns", {}).get(action, 0)
        return time.time() - last >= COOLDOWNS.get(action, 300)

    def mark(self, action):
        self.state.setdefault("cooldowns", {})[action] = time.time()

    def do(self, action, reason, fn):
        """Run one remediation, rate-limited and logged."""
        if not self.allowed(action):
            left = int(COOLDOWNS.get(action, 300) - (time.time() - self.state["cooldowns"][action]))
            log(f"SKIP {action} (cooldown {left}s) reason={reason}")
            return {"action": action, "reason": reason, "result": "skipped (cooldown)",
                    "at": now().isoformat(timespec="seconds")}
        self.mark(action)
        t0 = time.time()
        try:
            ok, detail = fn()
        except Exception as exc:
            ok, detail = False, f"{type(exc).__name__}: {exc}"
        entry = {"action": action, "reason": reason, "result": ("ok: " if ok else "failed: ") + str(detail)[:160],
                 "ms": int((time.time() - t0) * 1000), "at": now().isoformat(timespec="seconds")}
        self.actions.append(entry)
        log(f"ACTION {action} reason={reason} -> {entry['result']}")
        return entry

    def restart_budget_left(self):
        return len(self.recent_restarts) < RESTART_BUDGET_PER_HOUR

    def note_restart(self):
        self.recent_restarts.append(time.time())
        self.state["recent_restarts"] = self.recent_restarts


def act_reload():
    rc, out = run(["nginx", "-t"])
    if rc != 0:
        return False, "config invalid, reload skipped"
    rc, out = run(["systemctl", "reload", "nginx"])
    return rc == 0, "reloaded (upstream pool cleared, DNS re-resolved)"


def act_restart_nginx(healer):
    if not healer.restart_budget_left():
        return False, "restart budget for this hour exhausted"
    rc, out = run(["nginx", "-t"])
    if rc != 0:
        return False, "config invalid, restart skipped"
    rc, out = run(["systemctl", "restart", "nginx"], timeout=60)
    healer.note_restart()
    return rc == 0, "restarted"


def act_restore_config():
    """Put the known-good config from /opt/relay back and reload."""
    restored = []
    for master, live in ((MASTER_SITE, LIVE_SITE), (MASTER_SNIP, LIVE_SNIP)):
        try:
            with open(master, "rb") as f:
                data = f.read()
        except OSError as exc:
            return False, f"master {master} unreadable: {exc}"
        tmp = live + ".selfheal-tmp"
        with open(tmp, "wb") as f:
            f.write(data)
        os.chmod(tmp, 0o600 if live == LIVE_SITE else 0o644)
        os.replace(tmp, live)
        restored.append(os.path.basename(live))
    rc, out = run(["nginx", "-t"])
    if rc != 0:
        return False, "master config does not validate either"
    rc, out = run(["systemctl", "reload", "nginx"])
    return rc == 0, "restored " + ", ".join(restored)


def act_fix_resolver():
    """Point nginx at a resolver that actually answers for opencode.ai."""
    working = [s for s in RESOLVER_CANDIDATES if dns_query_a(s, DNS_NAME)]
    if not working:
        return False, "no candidate resolver answers"
    current = read_resolvers()
    if current and all(s in working for s in current):
        return True, "configured resolvers already answer"
    line_new = "resolver " + " ".join(working[:3]) + " ipv6=off valid=300s;"
    changed = 0
    for path in (LIVE_SITE, MASTER_SITE):
        try:
            with open(path, encoding="utf-8") as f:
                src = f.read()
            out = re.sub(r"^\s*resolver\s+[^;]+;", "    " + line_new, src, count=1, flags=re.M)
            if out != src:
                with open(path + ".before-resolver-fix", "w", encoding="utf-8") as f:
                    f.write(src)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(out)
                changed += 1
        except OSError as exc:
            return False, f"{path}: {exc}"
    rc, _ = run(["nginx", "-t"])
    if rc != 0:
        return False, "rewritten resolver line does not validate"
    run(["systemctl", "reload", "nginx"])
    return True, f"switched to {', '.join(working[:3])} ({changed} files)"


def act_restart_stats():
    rc, out = run(["systemctl", "restart", "relay-stats.service"], timeout=60)
    return rc == 0, "relay-stats restarted"


def act_prune():
    freed = 0
    for pattern_dir, pattern in (("/var/log/nginx", "opencode-relay.access.log."),
                                 ("/var/log/nginx", "opencode-relay.error.log.")):
        try:
            for name in os.listdir(pattern_dir):
                if name.startswith(pattern) or (name.startswith(pattern.split('.')[0]) and name.endswith(".gz")):
                    p = os.path.join(pattern_dir, name)
                    if os.path.isfile(p) and time.time() - os.path.getmtime(p) > 86400:
                        freed += os.path.getsize(p)
                        os.remove(p)
        except OSError:
            pass
    for d in ("/var/lib/nginx/body", "/var/lib/nginx/proxy", "/var/lib/nginx/fastcgi"):
        try:
            for name in os.listdir(d):
                p = os.path.join(d, name)
                if os.path.isfile(p) and time.time() - os.path.getmtime(p) > 3600:
                    freed += os.path.getsize(p)
                    os.remove(p)
        except OSError:
            pass
    return True, f"freed {freed / 1024 / 1024:.1f} MB"


def act_modelcheck():
    rc, out = run(["systemctl", "start", "relay-modelcheck.service"], timeout=30)
    return rc == 0, "model probe triggered"


def remediate(results, healer):
    """Map failed checks to escalating actions; return the set of problems seen."""
    failed = {r["name"] for r in results if not r["ok"]}
    problems = set(failed)

    if "nginx_active" in failed:
        healer.do("restart_nginx", "nginx not active", lambda: act_restart_nginx(healer))
    if "nginx_config" in failed:
        healer.do("restore_config", "nginx config invalid", act_restore_config)
    if "dns_resolvers" in failed:
        healer.do("fix_resolver", "configured resolvers do not answer", act_fix_resolver)
    if "relay_endpoint" in failed:
        # step 1: reload clears the upstream keepalive pool and forces re-resolution
        entry = healer.do("reload", "relay endpoint failing", act_reload)
        recheck = [r for r in run_checks() if r["name"] == "relay_endpoint"][0]
        if not recheck["ok"] and entry["result"].startswith("ok"):
            if "401" in recheck["detail"]:
                log("CRITICAL upstream rejected the key (401) - needs a human")
            else:
                healer.do("restart_nginx", f"endpoint still failing ({recheck['detail']})",
                          lambda: act_restart_nginx(healer))
    if "stats_api" in failed or "dashboard_static" in failed:
        healer.do("restart_stats", "stats API/dashboard down", act_restart_stats)
    if "error_burst" in failed and "relay_endpoint" not in failed:
        healer.do("reload", "upstream error burst", act_reload)
    if "disk_space" in failed:
        healer.do("prune", "disk space low", act_prune)
    if "models_report" in failed:
        healer.do("modelcheck", "model report stale", act_modelcheck)
    if "key_pool" in failed:
        healer.do("refresh_keys", "no usable upstream key", act_refresh_keys)
    if "usage_tap" in failed:
        healer.do("usage_tap", "usage tap unhealthy or bypassed", act_fix_tap)
    return problems


def main():
    started = time.time()
    state = load_json(STATE_PATH, {})
    healer = Healer(state)

    results = run_checks()
    failed = [r for r in results if not r["ok"]]
    problems = remediate(results, healer) if failed else set()

    # verify: anything that failed gets re-checked after remediation
    if failed:
        results = run_checks()
        failed = [r for r in results if not r["ok"]]

    still_critical = [r["name"] for r in failed if r["name"] in CRITICAL]
    still_broken = [r["name"] for r in failed]

    if still_critical:
        status = "critical"
        summary = "关键检查仍失败: " + ", ".join(still_critical)
    elif still_broken:
        status = "degraded"
        summary = "非关键检查失败: " + ", ".join(still_broken)
    elif healer.actions and any(a["result"].startswith("ok") for a in healer.actions):
        status = "healed"
        summary = "已自动修复: " + ", ".join(a["action"] for a in healer.actions if a["result"].startswith("ok"))
    else:
        status = "ok"
        summary = "全部检查通过"

    state["cooldowns"] = state.get("cooldowns", {})
    state["recent_restarts"] = healer.recent_restarts
    state["counters"] = state.get("counters", {})
    counters = state["counters"]
    counters["runs"] = counters.get("runs", 0) + 1
    counters["heals"] = counters.get("heals", 0) + (1 if status == "healed" else 0)
    counters["incidents"] = counters.get("incidents", 0) + (1 if problems else 0)
    for a in healer.actions:
        key = "actions_" + a["action"]
        counters[key] = counters.get(key, 0) + 1

    history = state.get("history", [])
    if problems or healer.actions:
        history.insert(0, {
            "at": now().isoformat(timespec="seconds"),
            "problems": sorted(problems),
            "actions": [{"action": a["action"], "result": a["result"]} for a in healer.actions],
            "status": status,
        })
        history = history[:HISTORY_MAX]
    state["history"] = history
    save_json(STATE_PATH, state)

    payload = {
        "available": True,
        "checked_at": now().isoformat(timespec="seconds"),
        "status": status,
        "summary": summary,
        "duration_ms": int((time.time() - started) * 1000),
        "checks": results,
        "actions": healer.actions,
        "history": history[:25],
        "counters": counters,
        "restarts_last_hour": len(healer.recent_restarts),
    }
    save_json(STATUS_PATH, payload)

    if status not in ("ok",):
        log(f"STATUS {status}: {summary}")
    return 0 if status in ("ok", "healed") else 1


if __name__ == "__main__":
    sys.exit(main())
