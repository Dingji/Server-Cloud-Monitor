#!/usr/bin/env python3
"""relay_alert.py - service health checks + email alerting.

Reuses the SMTP settings already present in /etc/relay/report.json.
State transitions (ok->fail / fail->ok) send immediately; a persistent
failure re-reminds at most once per COOLDOWN hours.
"""
import json, os, re, smtplib, ssl, socket, subprocess, sys, time, urllib.request, urllib.error
from datetime import datetime, timezone, timedelta
from email.message import EmailMessage
from email.utils import formataddr

STATE   = "/var/lib/relay/alert.state"
REPORT  = "/etc/relay/report.json"
LOG     = "/var/log/relay-alert.log"
COOLDOWN = 6 * 3600          # seconds between repeat alerts for the same issue
CST      = timezone(timedelta(hours=8))
ERRLOG   = "/var/log/nginx/opencode-relay.error.log"
ACCLOG   = "/var/log/nginx/opencode-relay.access.log"

def now():  return datetime.now(CST)
def log(m): 
    try:    open(LOG, "a").write("[%s] %s\n" % (now().strftime("%Y-%m-%d %H:%M:%S"), m))
    except Exception: pass

# ---------------- checks ----------------
def c_unit(name, label=None):
    label = label or name
    try:
        r = subprocess.run(["systemctl", "is-active", name], capture_output=True, text=True, timeout=15)
        st = (r.stdout or "").strip()
        return label, st == "active", st
    except Exception as e:
        return label, False, str(e)

def http_status(path):
    try:
        req = urllib.request.Request("http://127.0.0.1" + path, method="GET")
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return None

def tcp_ok(port):
    try:
        s = socket.create_connection(("127.0.0.1", port), 3); s.close(); return True
    except Exception:
        return False

def c_http(path, expect, label):
    st = http_status(path)
    return label, st in expect, "status=%s (expect %s)" % (st, "/".join(map(str, expect)))

def c_disk(pct_max=90):
    try:
        import shutil
        u = shutil.disk_usage("/")
        p = round(u.used / u.total * 100, 1)
        return "disk /", p <= pct_max, "used %s%% (limit %s%%)" % (p, pct_max)
    except Exception as e:
        return "disk /", False, str(e)

def c_swap(pct_max=85):
    try:
        d = dict(re.findall(r"^(SwapTotal|SwapFree):\s+(\d+)", open("/proc/meminfo").read(), re.M))
        t = int(d.get("SwapTotal", 0)); f = int(d.get("SwapFree", 0))
        if t == 0: return "swap", True, "no swap"
        p = round((t - f) / t * 100, 1)
        return "swap", p <= pct_max, "used %s%% (limit %s%%)" % (p, pct_max)
    except Exception as e:
        return "swap", False, str(e)

def c_mem_avail(min_mb=80):
    try:
        d = dict(re.findall(r"^(MemAvailable):\s+(\d+)", open("/proc/meminfo").read(), re.M))
        mb = int(d.get("MemAvailable", 0)) // 1024
        return "mem available", mb >= min_mb, "%sMB (min %sMB)" % (mb, min_mb)
    except Exception as e:
        return "mem available", False, str(e)

def c_backup(max_age_h=26):
    p = "/var/lib/relay/backup.state"
    if not os.path.exists(p): return "backup", False, "no backup.state"
    try:
        d = json.load(open(p)); age = time.time() - os.path.getmtime(p)
        ok = bool(d.get("ok")) and age <= max_age_h * 3600
        return "backup", ok, "age %.1fh ok=%s" % (age / 3600, d.get("ok"))
    except Exception as e:
        return "backup", False, str(e)

def _since(path, mins, pattern):
    """count lines in `path` newer than `mins` minutes matching pattern"""
    if not os.path.exists(path): return 0, 0
    cutoff = time.time() - mins * 60
    n = 0; tot = 0
    try:
        with open(path, "r", errors="ignore") as f:
            for line in f:
                m = re.match(r"(\d{4})/(\d{2})/(\d{2}) (\d{2}):(\d{2}):(\d{2})", line)
                if not m: continue
                ts = time.mktime(tuple(int(x) for x in m.groups()) + (0, 0, -1))
                if ts < cutoff: continue
                tot += 1
                if re.search(pattern, line): n += 1
    except Exception:
        pass
    return n, tot

def c_err_burst(max_n=50, mins=15):
    n, _ = _since(ERRLOG, mins, r"\[error\]")
    return "nginx errors(%dm)" % mins, n <= max_n, "%d errors (limit %d)" % (n, max_n)

def c_5xx(min_ratio=0.10, mins=15, min_samples=20):
    if not os.path.exists(ACCLOG): return "5xx ratio", True, "no access log"
    cutoff = time.time() - mins * 60
    bad = 0; tot = 0
    try:
        with open(ACCLOG, "r", errors="ignore") as f:
            for line in f:
                p = line.split("|")
                if len(p) < 3: continue
                try: ts = time.mktime(datetime.strptime(p[0], "%Y-%m-%dT%H:%M:%S%z").timetuple())
                except Exception: continue
                if ts < cutoff: continue
                tot += 1
                if p[2].isdigit() and int(p[2]) >= 500: bad += 1
    except Exception:
        pass
    if tot < min_samples:
        return "5xx ratio(%dm)" % mins, True, "%d samples (<%d, skipped)" % (tot, min_samples)
    r = bad / tot
    return "5xx ratio(%dm)" % mins, r <= min_ratio, "%.1f%% (%d/%d, limit %.0f%%)" % (r*100, bad, tot, min_ratio*100)

def c_sshd_guard():
    """config drift guard: password auth must stay disabled"""
    try:
        r = subprocess.run(["sshd", "-T"], capture_output=True, text=True, timeout=15)
        v = dict(re.findall(r"^(passwordauthentication|permitrootlogin)\s+(\S+)", r.stdout, re.M))
        ok = v.get("passwordauthentication") == "no" and v.get("permitrootlogin") == "prohibit-password"
        return "ssh hardening", ok, "pwd=%s root=%s" % (v.get("passwordauthentication"), v.get("permitrootlogin"))
    except Exception as e:
        return "ssh hardening", False, str(e)

def _state_check(label, path, max_age_h, extra=""):
    """读取守护脚本写出的 state 文件；缺失即视为未验证过"""
    if not os.path.exists(path):
        return label, False, "尚未运行过"
    try:
        d = json.load(open(path))
        age = time.time() - os.path.getmtime(path)
        ok = bool(d.get("ok")) and age <= max_age_h * 3600
        detail = "age %.1fh ok=%s" % (age / 3600, d.get("ok"))
        if extra and d.get(extra):
            detail += " %s=%s" % (extra, d.get(extra))
        return label, ok, detail
    except Exception as ex:
        return label, False, str(ex)


def c_backup_restorable():
    return _state_check("backup restorable", "/var/lib/relay/verify.state", 48, "backup")


def c_db_guard():
    return _state_check("db integrity guard", "/var/lib/relay/dbguard.state", 1, "action")


def c_disk_guard():
    return _state_check("disk watermark guard", "/var/lib/relay/diskguard.state", 1, "action")


def c_relay_guard():
    """The self-healing guard's own verdict: integrity + end-to-end canaries."""
    path = "/var/lib/relay/guard.state"
    if not os.path.exists(path):
        return "relay guard", False, "guard 未运行过"
    try:
        d = json.load(open(path))
        age = time.time() - os.path.getmtime(path)
        if age > 15 * 60:
            return "relay guard", False, "guard state 过期 %.1fmin" % (age / 60)
        if not d.get("ok"):
            return "relay guard: canary", False, ", ".join(d.get("problems") or [])[:300]
        canaries = (d.get("checks") or {}).get("canaries") or {}
        bad = [k for k, v in canaries.items() if not v.get("ok")]
        if bad:
            return "relay guard: canary", False, ", ".join(bad)
        return "relay guard: canary", True, "%d canaries ok" % len(canaries)
    except Exception as ex:
        return "relay guard", False, str(ex)


def run_checks():
    out = []
    out.append(c_unit("nginx"))
    out.append(c_unit("ssh"))
    out.append(c_unit("fail2ban"))
    out.append(c_unit("ufw"))
    out.append(c_unit("relay-stats"))
    out.append(c_unit("relay-usagetap"))
    out.append(c_unit("relay-selfheal.timer"))
    out.append(c_unit("relay-backup.timer"))
    out.append(c_http("/", (404, 401, 200), "relay API reachable"))
    out.append(c_http("/stats/", (401, 200), "stats dashboard"))
    out.append(c_http("/panel/", (302, 200), "panel"))
    out.append(("panel upstream :9000", tcp_ok(9000), "tcp 127.0.0.1:9000"))
    out.append(("stats upstream :8787", tcp_ok(8787), "tcp 127.0.0.1:8787"))
    out.append(c_disk())
    out.append(c_swap())
    out.append(c_mem_avail())
    out.append(c_backup())
    out.append(c_err_burst())
    out.append(c_5xx())
    out.append(c_sshd_guard())
    out.append(c_backup_restorable())
    out.append(c_db_guard())
    out.append(c_disk_guard())
    out.append(c_relay_guard())
    return out

# ---------------- email ----------------
def load_smtp():
    cfg = json.load(open(REPORT))
    e = cfg.get("EmailConfigruation") or cfg.get("EmailConfiguration") or {}
    return cfg.get("to", []), {
        "host": e.get("SMTPHost", "smtp.163.com"),
        "port": int(e.get("SMTPPort", 25)),
        "user": e.get("EmailAccount", ""),
        "password": e.get("EmailAccountPassword", ""),
        "from_name": e.get("EmailAccountDisplayName", "OpenCode Relay"),
        "use_ssl": bool(e.get("SMTPUseSSL", False)),
        "starttls": bool(e.get("SMTPStartTLS", True)),
    }

# Cloud hosts block outbound 25 (Aliyun does). Try the configured transport
# first, then the usual ladder; first one that authenticates wins.
TRANSPORTS = [(465, "ssl"), (587, "starttls"), (25, "starttls"), (25, "plain")]


def _connect(cfg, port, mode):
    if mode == "ssl":
        return smtplib.SMTP_SSL(cfg["host"], port, timeout=45,
                                context=ssl.create_default_context())
    srv = smtplib.SMTP(cfg["host"], port, timeout=45)
    srv.ehlo()
    if mode == "starttls":
        if not srv.has_extn("starttls"):
            try:
                srv.quit()
            except Exception:
                pass
            raise RuntimeError("server does not advertise STARTTLS")
        srv.starttls(context=ssl.create_default_context())
        srv.ehlo()
    return srv


def send_mail(subject, html):
    to, cfg = load_smtp()
    if not to:
        raise RuntimeError("no recipients in report.json")
    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = formataddr((cfg["from_name"], cfg["user"]))
    msg["To"] = ", ".join(to)
    msg.set_content("OpenCode Relay alert (HTML version preferred).")
    msg.add_alternative(html, subtype="html")

    configured = (cfg["port"],
                  "ssl" if cfg["use_ssl"] else ("starttls" if cfg["starttls"] else "plain"))
    ladder = [configured] + [t for t in TRANSPORTS if t != configured]
    errors = []
    for port, mode in ladder:
        try:
            srv = _connect(cfg, port, mode)
            try:
                if cfg["user"]:
                    srv.login(cfg["user"], cfg["password"])
                srv.send_message(msg)
            finally:
                try:
                    srv.quit()
                except Exception:
                    pass
            log("mail sent via %s:%s/%s" % (cfg["host"], port, mode))
            return "%s/%s" % (port, mode)
        except Exception as exc:
            errors.append("%s/%s: %s: %s" % (port, mode, type(exc).__name__, str(exc)[:80]))
    raise RuntimeError("SMTP failed on every transport: " + " | ".join(errors))


def render(subject, rows, ok_count, bad_count):
    tr = []
    for name, ok, detail in rows:
        color = "#0a7d33" if ok else "#c62828"
        badge = "OK" if ok else "FAIL"
        tr.append(
            "<tr><td style='padding:6px 10px;border-bottom:1px solid #eee'>%s</td>"
            "<td style='padding:6px 10px;border-bottom:1px solid #eee;color:%s;font-weight:700'>%s</td>"
            "<td style='padding:6px 10px;border-bottom:1px solid #eee;color:#555;font-family:monospace'>%s</td></tr>"
            % (name, color, badge, detail))
    return """<html><body style="font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;color:#222">
<h2 style="margin:0 0 4px">%s</h2>
<p style="margin:0 0 14px;color:#666">%s &middot; host %s &middot; %d OK / %d FAIL</p>
<table style="border-collapse:collapse;width:100%%;max-width:760px;font-size:13px">
<tr style="background:#f5f5f5"><th style="padding:6px 10px;text-align:left">Check</th>
<th style="padding:6px 10px;text-align:left">State</th><th style="padding:6px 10px;text-align:left">Detail</th></tr>
%s</table>
<p style="margin-top:16px;color:#999;font-size:12px">Auto-generated by /opt/relay/relay_alert.py</p>
</body></html>""" % (subject, now().strftime("%Y-%m-%d %H:%M:%S %Z"),
                     socket.gethostname(), ok_count, bad_count, "".join(tr))

def main():
    test = "--test" in sys.argv
    rows = run_checks()

    try:
        st = json.load(open(STATE))
    except Exception:
        st = {}

    changed = []
    for name, ok, detail in rows:
        prev = st.get(name)
        if prev is None:
            st[name] = {"ok": ok, "since": now().isoformat(), "last_sent": 0}
            continue
        if prev.get("ok") != ok:
            st[name] = {"ok": ok, "since": now().isoformat(), "last_sent": int(time.time())}
            changed.append((name, ok, detail))
        elif not ok and (time.time() - prev.get("last_sent", 0)) > COOLDOWN:
            st[name]["last_sent"] = int(time.time())
            changed.append((name, ok, detail))

    fails = [r for r in rows if not r[1]]
    oks   = [r for r in rows if r[1]]

    try:
        os.makedirs(os.path.dirname(STATE), exist_ok=True)
        json.dump(st, open(STATE, "w"), indent=1)
    except Exception as e:
        log("state write failed: %s" % e)

    if test:
        subj = "[TEST] OpenCode Relay 健康检查邮件通道正常"
        html = render("邮件通道测试 —— 收到即表示告警可用", rows, len(oks), len(fails))
        try:
            send_mail(subj, html); print("TEST MAIL SENT to", load_smtp()[0]); log("test mail sent")
            return 0
        except Exception as e:
            print("TEST MAIL FAILED:", e); log("test mail failed: %s" % e); return 1

    if not changed:
        log("no change (%d checks, %d failing)" % (len(rows), len(fails)))
        print("no change; %d checks, %d failing" % (len(rows), len(fails)))
        return 0

    bad_changed = [c for c in changed if not c[1]]
    state_word = "故障" if bad_changed else "恢复"
    subj = "[%s] OpenCode Relay %s" % ("FAIL" if bad_changed else "OK", state_word)
    html = render(subj, rows, len(oks), len(fails))
    try:
        send_mail(subj, html)
        log("alert sent: %s (%s)" % (subj, ", ".join(c[0] for c in changed)))
        print("alert sent:", subj)
    except Exception as e:
        log("alert send failed: %s" % e)
        print("alert send failed:", e)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
