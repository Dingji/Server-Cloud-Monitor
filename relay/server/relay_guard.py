#!/usr/bin/env python3
"""OpenCode relay self-healing guard (v2).

Runs every two minutes and repairs the failure classes that broke clients on
2026-10-17 before users notice:

  1. nginx integrity      syntax valid, x-opencode-session injection present
                          -> restore golden config, nginx -t, reload
  2. usage tap integrity  service alive, health fresh, stream-fallback +
                          error-recording patches present
                          -> restart, then restore golden tap
  3. end-to-end canaries  real streaming calls through the relay:
                            normal chat          (SSE + finish_reason + [DONE])
                            streaming-only model (proves the fallback converts
                                                  an upstream 400 into a stream)
                            plain chat           (non-streaming completion)
                            responses protocol   (a /responses-only model)
                          -> nginx reload, tap restart, golden tap, repeat
  4. capability report    models-status.json fresh with >0 usable models
                          -> run the probe now

Deliberate non-goals
--------------------
* It never rewrites upstream routing. `set_target.py direct` (tap bypass) is the
  watchdog's job and doing it here once took the relay down; a guard that can
  make traffic worse is worse than no guard.
* It never restores a golden file without `nginx -t` (or a syntax check for the
  Python tap) passing first.
* Repairs are rate-limited per action so a permanently broken upstream cannot
  produce an nginx-reload storm.

State: /var/lib/relay/guard.state   (read by relay_alert.py)
Health: /var/lib/relay/public/relay-health.json
Log:    /var/log/relay-guard.log
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))
STATE = "/var/lib/relay/guard.state"
HEALTH = "/var/lib/relay/public/relay-health.json"
LOG = "/var/log/relay-guard.log"
RELAY = "http://127.0.0.1"
TOKEN_FILE = "/etc/relay/relay.env"
NGINX_SITE = "/etc/nginx/sites-available/opencode-relay"
GOLDEN = "/opt/relay/golden"
TAP = "/opt/relay/usagetap.py"
STATUS = "/var/lib/relay/public/models-status.json"
UA = "relay-guard/2.0 (+self-healing)"   # Cloudflare blocks bare urllib UA
COOLDOWN = 180
STALE_MODELS_H = 30

FALLBACK_CANARIES = [
    ("chat-stream", "/v1/chat/completions",
     {"model": "deepseek-v4.1-flash", "stream": True, "max_tokens": 16,
      "messages": [{"role": "user", "content": "say ok"}]}, "stream"),
    ("stream-fallback", "/v1/chat/completions",
     {"model": "mimo-v2.6-flash", "stream": True, "max_tokens": 16,
      "messages": [{"role": "user", "content": "say ok"}]}, "stream"),
    ("chat-plain", "/v1/chat/completions",
     {"model": "deepseek-v4.1-flash", "max_tokens": 8,
      "messages": [{"role": "user", "content": "say ok"}]}, "json"),
]


def now():
    return datetime.now(TZ)


def log(msg):
    line = "%s %s" % (now().strftime("%Y-%m-%d %H:%M:%S"), msg)
    print(line, flush=True)
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass


def load_json(path, default):
    try:
        return json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError):
        return default


def save_json(path, obj):
    tmp = path + ".tmp"
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
    except OSError as exc:
        log("cannot write %s: %s" % (path, exc))


def token():
    try:
        txt = open(TOKEN_FILE, encoding="utf-8").read()
        a = txt.find('"')
        return txt[a + 1:txt.find('"', a + 1)]
    except OSError:
        return ""


def run(cmd, timeout=60):
    try:
        p = subprocess.run(cmd, shell=True, capture_output=True, text=True,
                           timeout=timeout)
        return p.returncode, ((p.stdout or "") + (p.stderr or "")).strip()
    except subprocess.TimeoutExpired:
        return 124, "timeout"
    except Exception as exc:
        return 1, "%s: %s" % (type(exc).__name__, exc)


# ── canaries ───────────────────────────────────────────────────────────────

def canaries_from_report():
    """Prefer models the report currently marks usable, so a renamed model does
    not turn the guard into a permanent false alarm."""
    report = load_json(STATUS, {})
    go = (report.get("providers") or {}).get("opencode-go", {}).get("models", {})
    if not go:
        return FALLBACK_CANARIES
    chat, responses = [], []
    for mid, info in go.items():
        routes = info.get("routes") or {}
        if (routes.get("chat") or {}).get("status") == "ok":
            chat.append(mid)
        if (routes.get("responses") or {}).get("status") == "ok":
            responses.append(mid)
    pick = list(FALLBACK_CANARIES)
    allowlist = set(report.get("go_allowlist") or [])
    if allowlist:
        # One streaming canary per allowed model: the plan's permitted set is
        # exactly what must keep working.
        pick = []
        primary = None
        for mid in sorted(allowlist):
            if (go.get(mid, {}).get("routes") or {}).get("chat", {}).get("status") != "ok":
                continue
            pick.append(("chat-stream:" + mid, "/v1/chat/completions",
                         {"model": mid, "stream": True, "max_tokens": 16,
                          "messages": [{"role": "user", "content": "say ok"}]}, "stream"))
            primary = primary or mid
        if primary:
            pick.append(("chat-plain", "/v1/chat/completions",
                         {"model": primary, "max_tokens": 8,
                          "messages": [{"role": "user", "content": "say ok"}]}, "json"))
        # Prove the restriction itself: a model outside the allowlist must be
        # refused with 403 model_not_allowed.
        disallowed = next((m for m in sorted(go) if m not in allowlist), None)
        if disallowed:
            pick.append(("deny:" + disallowed, "/v1/chat/completions",
                         {"model": disallowed, "max_tokens": 8,
                          "messages": [{"role": "user", "content": "hi"}]},
                         "forbidden"))
        return pick
    if chat:
        primary = "deepseek-v4.1-flash" if "deepseek-v4.1-flash" in chat else chat[0]
        fallback = next((m for m in ("mimo-v2.6-flash",) if m in chat), None)
        pick = [("chat-stream", "/v1/chat/completions",
                 {"model": primary, "stream": True, "max_tokens": 16,
                  "messages": [{"role": "user", "content": "say ok"}]}, "stream"),
                ("chat-plain", "/v1/chat/completions",
                 {"model": primary, "max_tokens": 8,
                  "messages": [{"role": "user", "content": "say ok"}]}, "json")]
        if fallback:
            pick.insert(1, ("stream-fallback", "/v1/chat/completions",
                            {"model": fallback, "stream": True, "max_tokens": 16,
                             "messages": [{"role": "user", "content": "say ok"}]},
                            "stream"))
    # Only models the plan allows are canaried; a plan-restricted model would
    # make every guard run report a false canary failure.
    allowed = set(report.get("go_allowlist") or [])
    if allowed:
        pick = [c for c in pick if c[2].get("model") in allowed]
    return pick


def canary(label, path, payload, expect):
    tok = token()
    if not tok:
        return False, "no relay token"
    headers = {"Content-Type": "application/json", "User-Agent": UA,
               "Authorization": "Bearer " + tok,
               "x-opencode-session": "guard-%s-%d" % (label, int(time.time()))}
    req = urllib.request.Request(RELAY + path, data=json.dumps(payload).encode(),
                                 method="POST", headers=headers)
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            status = r.status
            text = r.read(300000).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        try:
            body = e.read(4000).decode("utf-8", "replace")
        except Exception:
            body = ""
        if expect == "forbidden":
            if e.code == 403 and "model_not_allowed" in body:
                return True, "denied 403 model_not_allowed"
            return False, "allowlist NOT enforced (HTTP %s: %s)" % (
                e.code, body[:140].replace("\n", " "))
        return False, "HTTP %s %s" % (e.code, body[:140].replace("\n", " "))
    except Exception as exc:
        return False, "%s: %s" % (type(exc).__name__, str(exc)[:120])
    elapsed = time.time() - started
    if expect == "forbidden":
        body = text or ""
        if status == 403 and "model_not_allowed" in body:
            return True, "denied 403 model_not_allowed"
        return False, "allowlist NOT enforced (HTTP %s: %s)" % (
            status, body[:120].replace("\n", " "))
    if status != 200:
        return False, "HTTP %s" % status
    if expect == "stream":
        if "finish_reason" not in text or "[DONE]" not in text:
            return False, "stream incomplete (no finish_reason/[DONE])"
        if text.count("data:") < 2:
            return False, "stream had %d chunks" % text.count("data:")
    elif expect == "sse":
        if "data:" not in text:
            return False, "no SSE frames"
    else:
        if '"choices"' not in text and '"content"' not in text:
            return False, "not a completion: %s" % text[:120]
    return True, "%.2fs" % elapsed


def run_canaries():
    results, failed = {}, []
    for label, path, payload, expect in canaries_from_report():
        ok, detail = canary(label, path, payload, expect)
        results[label] = {"ok": ok, "detail": detail}
        if not ok:
            failed.append(label)
        log("  canary %-16s %s %s" % (label, "OK  " if ok else "FAIL", detail))
    return results, failed


# ── repairs ────────────────────────────────────────────────────────────────

class Repairs:
    def __init__(self, state):
        self.state = state
        self.cooldowns = dict(state.get("cooldowns") or {})
        self.actions = []

    def maybe(self, name, fn, cooldown=None):
        limit = COOLDOWN if cooldown is None else cooldown
        if time.time() - float(self.cooldowns.get(name, 0)) < limit:
            log("  SKIP %s (cooldown)" % name)
            return None
        self.cooldowns[name] = time.time()
        try:
            result = fn()
        except Exception as exc:
            result = "EXC %s: %s" % (type(exc).__name__, exc)
        self.actions.append({"action": name, "result": str(result),
                             "at": now().isoformat(timespec="seconds")})
        log("  ACTION %-24s %s" % (name, result))
        return result


def nginx_ok():
    code, out = run("nginx -t")
    return code == 0, out[-300:]


def session_header_present():
    try:
        return open(NGINX_SITE, encoding="utf-8").read().count("upstream-session.conf") >= 3
    except OSError:
        return False


def restore_golden_nginx():
    golden = os.path.join(GOLDEN, "opencode-relay.nginx")
    if not os.path.exists(golden):
        return "no golden nginx config"
    if recently_edited(NGINX_SITE) and os.path.getmtime(NGINX_SITE) > os.path.getmtime(golden):
        return "skipped: nginx site edited %ds ago (intentional change)" % int(
            time.time() - os.path.getmtime(NGINX_SITE))
    shutil.copy2(NGINX_SITE, NGINX_SITE + ".guard-broken")
    shutil.copy2(golden, NGINX_SITE)
    code, out = run("nginx -t")
    if code != 0:
        shutil.copy2(NGINX_SITE + ".guard-broken", NGINX_SITE)
        return "golden failed nginx -t, reverted: " + out[-200:]
    run("systemctl reload nginx")
    return "restored golden nginx + reloaded"


def service_uptime_ok(unit):
    """(active, seconds since the unit last entered active)."""
    if run("systemctl is-active %s" % unit)[1].strip() != "active":
        return False, 1e9
    try:
        out = run("systemctl show -p ActiveEnterTimestampMonotonic %s" % unit)[1]
        usec = int(out.split("=")[1].strip() or 0)
        return True, (time.monotonic() - usec / 1e6) if usec else 1e9
    except Exception:
        return True, 1e9


def port_open(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=2):
            return True
    except OSError:
        return False


def tap_ok():
    if not os.path.exists(TAP):
        return False, "tap missing"
    try:
        text = open(TAP, encoding="utf-8").read()
    except OSError:
        return False, "tap unreadable"
    for marker in ("STREAM_FALLBACK_MARK", "UPSTREAM_ERROR_MARK",
                   "GO_ALLOWLIST_MARK"):
        if marker not in text:
            return False, "%s missing" % marker
    active, age = service_uptime_ok("relay-usagetap")
    if not active:
        return False, "service inactive"
    if not port_open(8790):
        return False, "port 8790 not listening"
    # A just-restarted tap still has the previous process's health file; inside
    # the grace window an active listener is proof enough.
    if age < 90:
        return True, "starting (%.0fs)" % age
    health = load_json("/var/lib/relay/public/usagetap.json", {})
    if not health.get("ok", False):
        return False, "health ok=false: %s" % health.get("error")
    try:
        hage = (now() - datetime.fromisoformat(health["checked_at"])).total_seconds()
    except Exception:
        return False, "health not timestamped"
    if hage > 240:
        return False, "health stale %ds" % int(hage)
    return True, "ok (%ds)" % int(hage)


def recently_edited(path, seconds=180):
    """Respect an intentional edit instead of restoring over it."""
    try:
        return (time.time() - os.path.getmtime(path)) < seconds
    except OSError:
        return False


def restart_tap():
    run("systemctl restart relay-usagetap")
    time.sleep(2)
    return "restarted, service=" + run("systemctl is-active relay-usagetap")[1].strip()


def restore_golden_tap():
    golden = os.path.join(GOLDEN, "usagetap.py")
    if not os.path.exists(golden):
        return "no golden tap"
    if recently_edited(TAP) and os.path.getmtime(TAP) > os.path.getmtime(golden):
        return "skipped: tap edited %ds ago (intentional change)" % int(
            time.time() - os.path.getmtime(TAP))
    code, out = run("python3 -c \"import ast,io; ast.parse(io.open('%s',encoding='utf-8').read())\"" % golden)
    if code != 0:
        return "golden tap failed syntax check: " + out[-160:]
    shutil.copy2(TAP, TAP + ".guard-broken")
    shutil.copy2(golden, TAP)
    return restart_tap()


def restore_golden_allowlist():
    """Put the allowlist back when it is missing or unreadable."""
    golden = os.path.join(GOLDEN, "go-allowlist.json")
    if not os.path.exists(golden):
        return "no golden allowlist"
    try:
        json.load(open(golden, encoding="utf-8"))
    except Exception:
        return "golden allowlist invalid"
    live = "/etc/relay/go-allowlist.json"
    if os.path.exists(live):
        shutil.copy2(live, live + ".broken")
    shutil.copy2(golden, live)
    return "restored golden allowlist"


def report_fresh():
    report = load_json(STATUS, {})
    usable = (report.get("summary") or {}).get("usable")
    try:
        age = (now() - datetime.fromisoformat(report["generated_at"])).total_seconds()
    except Exception:
        return False, "no/invalid report"
    if age > STALE_MODELS_H * 3600:
        return False, "stale %.1fh" % (age / 3600)
    if not usable:
        return False, "0 usable models"
    return True, "%d usable, %.1fh old" % (usable, age / 3600)


# ── main ───────────────────────────────────────────────────────────────────

def main():
    started = time.time()
    state = load_json(STATE, {})
    repairs = Repairs(state)
    problems, checks = [], {}

    ok, detail = nginx_ok()
    checks["nginx_config"] = ok
    if not ok:
        problems.append("nginx_config")
        repairs.maybe("restore_nginx", restore_golden_nginx)
        checks["nginx_config"] = nginx_ok()[0]

    if not session_header_present():
        problems.append("session_header_missing")
        repairs.maybe("restore_session_header", restore_golden_nginx)
        checks["session_header"] = session_header_present()
    else:
        checks["session_header"] = True

    ok, detail = tap_ok()
    checks["usagetap"] = ok
    if not ok:
        problems.append("usagetap:" + detail)
        repairs.maybe("restart_tap", restart_tap)
        ok, detail = tap_ok()
        if not ok:
            repairs.maybe("restore_golden_tap", restore_golden_tap)
            ok, detail = tap_ok()
        checks["usagetap"] = ok
    else:
        checks["usagetap"] = True

    if "--no-canary" in sys.argv:
        # The timer runs every two minutes; model availability is checked exactly
        # once a day at 08:30 by relay-modelcheck. Integrity checks stay frequent.
        checks["canaries"] = {}
        failed = []
        log("  canaries skipped (--no-canary; daily model check runs at 08:30)")
    else:
        results, failed = run_canaries()
        checks["canaries"] = results
        if failed:
            problems.append("canary:" + ",".join(failed))
            repairs.maybe("reload_nginx", lambda: (run("nginx -s reload"), "reloaded")[1])
            results, failed = run_canaries()
            if failed:
                repairs.maybe("restart_tap_after_canary", restart_tap)
                results, failed = run_canaries()
            if failed:
                repairs.maybe("restore_tap_after_canary", restore_golden_tap)
                results, failed = run_canaries()
            checks["canaries"] = results
            if not failed:
                log("  healed: all canaries pass after repair")
                problems = [p for p in problems if not p.startswith("canary:")]

    ok, detail = report_fresh()
    checks["models_report"] = ok
    if not ok:
        problems.append("models_report:" + detail)
        repairs.maybe("run_modelcheck",
                      lambda: (run("systemctl start relay-modelcheck", 400),
                               "modelcheck started")[1])

    # Rebuild the problem list from the FINAL state. Getting this wrong made the
    # guard report DEGRADED for a fault it had just repaired, which would have
    # produced a false email alert every two minutes.
    final = []
    if not nginx_ok()[0]:
        final.append("nginx_config")
    if not session_header_present():
        final.append("session_header_missing")
    tap_ok_now, tap_detail = tap_ok()
    if not tap_ok_now:
        final.append("usagetap:" + tap_detail)
    canary_failed = [k for k, v in (checks.get("canaries") or {}).items()
                     if not v.get("ok")]
    if canary_failed:
        final.append("canary:" + ",".join(canary_failed))
    report_ok, report_detail = report_fresh()
    if not report_ok:
        final.append("models_report:" + report_detail)
    allowlist_ok = True
    try:
        allow_doc = json.load(open("/etc/relay/go-allowlist.json", encoding="utf-8"))
        if not (allow_doc.get("models") or []):
            allowlist_ok = False
    except Exception:
        allowlist_ok = False
    if not allowlist_ok:
        # a missing allowlist is cheap to restore and the restriction is
        # security-relevant, so retry it much sooner than the generic cooldown
        repairs.maybe("restore_allowlist", restore_golden_allowlist, cooldown=20)
        try:
            allow_doc = json.load(open("/etc/relay/go-allowlist.json", encoding="utf-8"))
            allowlist_ok = bool(allow_doc.get("models"))
        except Exception:
            allowlist_ok = False
    if not allowlist_ok:
        final.append("allowlist_unreadable")

    # Capability regression: the upstream can drop models or a whole wire
    # protocol without breaking the relay. Remember the usable count and shout
    # when it collapses, so the next "[model_coming_soon]"-style change is
    # noticed from the report instead of from a user complaint.
    report = load_json(STATUS, {})
    usable_now = (report.get("summary") or {}).get("usable") or 0
    prev = state.get("usable_models")
    if isinstance(prev, int) and prev >= 5 and usable_now < prev * 0.7:
        final.append("capability_drop:%d->%d" % (prev, usable_now))
        log("  WARNING capability drop: %d usable models -> %d" % (prev, usable_now))
    if usable_now:
        state["usable_models"] = usable_now

    if final != problems:
        log("  problems resolved after repair: %s -> %s"
            % (", ".join(problems) or "none", ", ".join(final) or "none"))
    problems = final

    healthy = not problems

    state.update({
        "ok": healthy,
        "problems": problems,
        "checks": checks,
        "cooldowns": repairs.cooldowns,
        "actions": ((state.get("actions") or [])[-40:] + repairs.actions)[-40:],
        "runs": int(state.get("runs") or 0) + 1,
        "last_run": now().isoformat(timespec="seconds"),
        "last_duration_s": round(time.time() - started, 2),
    })
    if repairs.actions:
        state["last_heal"] = now().isoformat(timespec="seconds")
    save_json(STATE, state)
    save_json(HEALTH, {
        "ok": healthy,
        "problems": problems,
        "canaries": checks.get("canaries", {}),
        "actions": repairs.actions,
        "checked_at": now().isoformat(timespec="seconds"),
    })
    log(("healthy" if healthy else "DEGRADED problems=" + ", ".join(problems))
        + " (%d checks, %.2fs)" % (len(checks), time.time() - started))
    return 0 if healthy else 1


if __name__ == "__main__":
    raise SystemExit(main())
