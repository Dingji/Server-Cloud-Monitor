#!/usr/bin/env python3
"""Daily HTML report for the OpenCode relay.

Reads the relay SQLite database directly for one calendar day (default: yesterday,
Asia/Shanghai) and mails a self-contained HTML report with charts and tables for
every dimension the relay records, plus per-key quota state and self-heal results.

Charts are rendered with table cells and inline styles on purpose: no external
assets, no <svg>, no <img>, so Gmail / Outlook / Apple Mail all render them.

Usage:
    daily_report.py                     # yesterday -> email
    daily_report.py --date 2026-10-11   # a specific day -> email
    daily_report.py --dry-run           # write the HTML, send nothing
    daily_report.py --to a@b.c          # override the recipient
"""

import argparse
import csv
import io
import json
import os
import smtplib
import sqlite3
import sys
from datetime import date, datetime, timedelta, timezone
from email.message import EmailMessage
from email.utils import formataddr, formatdate

DB_PATH = "/var/lib/relay/relay.db"
KEYS_PATH = "/var/lib/relay/keys.json"
SELFHEAL_PATH = "/var/lib/relay/public/selfheal.json"
MODELSTATUS_PATH = "/var/lib/relay/public/models-status.json"
REPORT_DIR = "/var/lib/relay/public/reports"
CONFIG_PATH = "/etc/relay/report.json"
PROBE_UA = "%relay-modelcheck%"
TZ = timezone(timedelta(hours=8))
DEFAULT_TO = "dingji_007@live.com"

WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

# palette
INK = "#1f2933"
MUTED = "#6b7684"
LINE = "#e3e8ee"
ACCENT = "#0f766e"
ACCENT2 = "#0ea5a4"
OK = "#0f9d58"
WARN = "#d97706"
BAD = "#dc2626"
BG = "#f6f8fa"


# ── small helpers ───────────────────────────────────────────────────────────

def q(sql, args=()):
    conn = sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True, timeout=20)
    conn.row_factory = sqlite3.Row
    try:
        return [dict(r) for r in conn.execute(sql, args)]
    finally:
        conn.close()


def num(v, nd=0):
    try:
        v = float(v or 0)
    except (TypeError, ValueError):
        return "0"
    return f"{v:,.{nd}f}"


def human_bytes(n):
    try:
        n = float(n or 0)
    except (TypeError, ValueError):
        return "0 B"
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024 or unit == "TB":
            return f"{n:,.1f} {unit}" if unit != "B" else f"{n:,.0f} B"
        n /= 1024


def pct(part, whole):
    try:
        return (float(part) / float(whole) * 100.0) if whole else 0.0
    except (TypeError, ValueError, ZeroDivisionError):
        return 0.0


def percentile(values, p):
    vals = sorted(float(v) for v in values if v is not None)
    if not vals:
        return 0.0
    k = (len(vals) - 1) * (p / 100.0)
    lo, hi = int(k), min(int(k) + 1, len(vals) - 1)
    return vals[lo] + (vals[hi] - vals[lo]) * (k - lo)


def td(v, style=""):
    return f'<td style="padding:6px 8px;border-bottom:1px solid {LINE};{style}">{v}</td>'


def th(v, style=""):
    return (f'<th style="padding:7px 8px;text-align:left;font-size:11px;letter-spacing:.04em;'
            f'text-transform:uppercase;color:{MUTED};border-bottom:1px solid {LINE};{style}">{v}</th>')


def table(headers, rows, align_right_from=1):
    out = [f'<table cellpadding="0" cellspacing="0" border="0" style="width:100%;border-collapse:collapse;'
           f'font-size:12.5px;color:{INK}">']
    out.append("<tr>" + "".join(
        th(h, "text-align:right" if i >= align_right_from else "") for i, h in enumerate(headers)) + "</tr>")
    for r in rows:
        cells = "".join(
            td(c, "text-align:right;white-space:nowrap" if i >= align_right_from else "")
            for i, c in enumerate(r))
        out.append("<tr>" + cells + "</tr>")
    out.append("</table>")
    return "".join(out)


def hbar(value, maximum, color=ACCENT2, width=150):
    p = 0 if not maximum else max(0.0, min(100.0, float(value) / float(maximum) * 100.0))
    return (f'<span style="display:inline-block;width:{width}px;height:12px;background:{LINE};'
            f'border-radius:3px;vertical-align:middle;overflow:hidden">'
            f'<span style="display:block;width:{p:.1f}%;height:12px;background:{color};'
            f'border-radius:3px"></span></span>')


def vchart(pairs, max_height=110, color=ACCENT2, every=2):
    """Vertical bar chart from (label, value) pairs, table based."""
    top = max([v for _, v in pairs] or [0]) or 1
    bars = []
    labels = []
    for i, (lab, val) in enumerate(pairs):
        h = int(round(val / top * max_height))
        h = max(1, h) if val else 1
        c = color if val else "#eef2f6"
        bars.append(f'<td style="vertical-align:bottom;padding:0 1px">'
                    f'<div style="background:{c};height:{h}px;border-radius:2px 2px 0 0"></div></td>')
        labels.append(f'<td style="text-align:center;font-size:9px;color:{MUTED};padding-top:3px">'
                      f'{lab if i % every == 0 else "&nbsp;"}</td>')
    return ('<table cellpadding="0" cellspacing="0" border="0" style="width:100%;table-layout:fixed">'
            f'<tr>{"".join(bars)}</tr><tr>{"".join(labels)}</tr></table>')


def section(title, body, note=""):
    n = f'<div style="font-size:11.5px;color:{MUTED};margin:2px 0 10px">{note}</div>' if note else ""
    return (f'<div style="margin:26px 0 0">'
            f'<div style="font-size:14px;font-weight:700;color:{INK};padding-bottom:6px;'
            f'border-bottom:2px solid {ACCENT2};margin-bottom:10px">{title}</div>'
            f'{n}{body}</div>')


def card(label, value, sub="", color=INK):
    return (f'<td style="padding:10px 12px;background:#fff;border:1px solid {LINE};border-radius:8px" width="25%">'
            f'<div style="font-size:11px;color:{MUTED};letter-spacing:.03em">{label}</div>'
            f'<div style="font-size:19px;font-weight:700;color:{color};margin:3px 0 1px">{value}</div>'
            f'<div style="font-size:11px;color:{MUTED}">{sub}</div></td>')


def kpi_grid(cards, per_row=4):
    rows, chunk = [], cards
    for i in range(0, len(chunk), per_row):
        part = chunk[i:i + per_row]
        while len(part) < per_row:
            part.append('<td width="25%"></td>')
        rows.append("<tr>" + "".join(f'<td style="padding:4px">{c}</td>' if c.strip().startswith("<td")
                                     else f'<td style="padding:4px">{c}</td>' for c in part) + "</tr>")
    return f'<table cellpadding="0" cellspacing="0" border="0" style="width:100%">{"".join(rows)}</table>'


# ── data collection ─────────────────────────────────────────────────────────

def collect(day):
    d = {}
    like = (PROBE_UA,)
    base = "FROM requests WHERE day = ? AND ua NOT LIKE ?"

    d["overview"] = q(f"""SELECT COUNT(*) n,
        SUM(CASE WHEN status=200 THEN 1 ELSE 0 END) ok,
        SUM(CASE WHEN status>=400 THEN 1 ELSE 0 END) err,
        SUM(CASE WHEN status BETWEEN 400 AND 499 THEN 1 ELSE 0 END) e4,
        SUM(CASE WHEN status>=500 THEN 1 ELSE 0 END) e5,
        SUM(CASE WHEN status=499 THEN 1 ELSE 0 END) e499,
        SUM(CASE WHEN status=429 THEN 1 ELSE 0 END) e429,
        SUM(CASE WHEN status=401 THEN 1 ELSE 0 END) e401,
        SUM(bytes_in) bin, SUM(bytes_out) bout,
        AVG(rt) art, MAX(rt) mrt, AVG(ttfb) att, MAX(ttfb) mtt,
        COUNT(DISTINCT sess) sess, COUNT(DISTINCT ip) ips, COUNT(DISTINCT model) models,
        SUM(COALESCE(attempts,1)) attempts,
        SUM(CASE WHEN COALESCE(attempts,1)>1 THEN 1 ELSE 0 END) retried,
        SUM(CASE WHEN ctype='text/event-stream' THEN 1 ELSE 0 END) streaming,
        SUM(CASE WHEN COALESCE(creq,0)>1 THEN 1 ELSE 0 END) reused,
        COUNT(DISTINCT creq) conns
        {base}""", (day, *like))[0]

    vals = q(f"SELECT rt, ttfb {base}", (day, *like))
    rts = [v["rt"] for v in vals if v["rt"] is not None]
    tts = [v["ttfb"] for v in vals if v["ttfb"] is not None]
    d["lat"] = {
        "p50": percentile(rts, 50), "p90": percentile(rts, 90), "p95": percentile(rts, 95),
        "p99": percentile(rts, 99),
        "t50": percentile(tts, 50), "t90": percentile(tts, 90), "t95": percentile(tts, 95),
        "t99": percentile(tts, 99),
        "n_rt": len(rts), "n_ttfb": len(tts),
    }

    d["hourly"] = q(f"SELECT hour, COUNT(*) n, SUM(CASE WHEN status>=400 THEN 1 ELSE 0 END) err,"
                    f" AVG(CASE WHEN ttfb IS NOT NULL THEN ttfb END) ttfb, SUM(bytes_out) bout"
                    f" {base} GROUP BY hour ORDER BY hour", (day, *like))
    d["status"] = q(f"SELECT status, COUNT(*) n, SUM(COALESCE(attempts,1)) a {base}"
                    f" GROUP BY status ORDER BY n DESC", (day, *like))
    d["models"] = q(f"""SELECT model, route, COUNT(*) n,
        SUM(CASE WHEN status=200 THEN 1 ELSE 0 END) ok,
        SUM(CASE WHEN status>=400 THEN 1 ELSE 0 END) err,
        AVG(rt) art, AVG(ttfb) att, SUM(bytes_out) bout, COUNT(DISTINCT sess) sess
        {base} GROUP BY model ORDER BY n DESC""", (day, *like))
    d["routes"] = q(f"SELECT route, COUNT(*) n, SUM(bytes_out) bout, AVG(rt) art, AVG(ttfb) att,"
                    f" SUM(CASE WHEN status=200 THEN 1 ELSE 0 END) ok,"
                    f" SUM(CASE WHEN COALESCE(attempts,1)>1 THEN 1 ELSE 0 END) retried"
                    f" {base} GROUP BY route ORDER BY n DESC", (day, *like))
    d["endpoints"] = q(f"SELECT uri, COUNT(*) n, SUM(bytes_out) bout, AVG(rt) art, AVG(ttfb) att"
                       f" {base} GROUP BY uri ORDER BY n DESC LIMIT 15", (day, *like))
    d["clients"] = q(f"SELECT ip, COUNT(*) n, SUM(bytes_in) bin, SUM(bytes_out) bout,"
                     f" SUM(CASE WHEN status=200 THEN 1 ELSE 0 END) ok,"
                     f" SUM(CASE WHEN status>=400 THEN 1 ELSE 0 END) err,"
                     f" COUNT(DISTINCT sess) sess, COUNT(DISTINCT model) models, AVG(rt) art"
                     f" {base} GROUP BY ip ORDER BY n DESC LIMIT 10", (day, *like))
    d["uas"] = q(f"SELECT ua, COUNT(*) n, SUM(CASE WHEN status=200 THEN 1 ELSE 0 END) ok"
                 f" {base} GROUP BY ua ORDER BY n DESC LIMIT 10", (day, *like))
    d["methods"] = q(f"SELECT method, COUNT(*) n {base} GROUP BY method ORDER BY n DESC", (day, *like))
    d["ctypes"] = q(f"SELECT ctype, COUNT(*) n {base} GROUP BY ctype ORDER BY n DESC LIMIT 8", (day, *like))
    d["upstreams"] = q(f"SELECT upstream_addr, COUNT(*) n {base} AND upstream_addr IS NOT NULL"
                       f" AND upstream_addr <> '' GROUP BY upstream_addr ORDER BY n DESC LIMIT 10", (day, *like))
    d["up_status"] = q(f"SELECT upstream_status, COUNT(*) n {base} AND upstream_status IS NOT NULL"
                       f" AND upstream_status <> '' GROUP BY upstream_status ORDER BY n DESC LIMIT 10", (day, *like))
    d["errors"] = q(f"SELECT status, uri, model, COUNT(*) n, MAX(ts) last {base} AND status>=400"
                    f" GROUP BY status, uri, model ORDER BY n DESC LIMIT 20", (day, *like))
    d["sessions"] = q(f"SELECT sess, COUNT(*) n, MIN(ts) first, MAX(ts) last, COUNT(DISTINCT model) models,"
                      f" COUNT(DISTINCT ip) ips {base} AND sess IS NOT NULL AND sess <> ''"
                      f" GROUP BY sess ORDER BY n DESC LIMIT 10", (day, *like))
    d["retries"] = q(f"SELECT attempts, COUNT(*) n {base} GROUP BY attempts ORDER BY attempts", (day, *like))
    d["recent_err"] = q(f"SELECT ts, status, uri, model, upstream_status, rt {base} AND status>=400"
                        f" ORDER BY ts DESC LIMIT 25", (day, *like))

    # sizes / latency / ttfb histograms, computed in python so buckets stay tunable
    rows = q(f"SELECT bytes_in, bytes_out {base}", (day, *like))
    d["size_buckets"] = bucketize(
        [r["bytes_in"] for r in rows], [1024, 10 * 1024, 100 * 1024, 1024 ** 2, 3 * 1024 ** 2],
        ["<1KB", "1-10KB", "10-100KB", "100KB-1MB", "1-3MB", ">3MB"])
    d["resp_buckets"] = bucketize(
        [r["bytes_out"] for r in rows], [1024, 10 * 1024, 100 * 1024, 1024 ** 2, 3 * 1024 ** 2],
        ["<1KB", "1-10KB", "10-100KB", "100KB-1MB", "1-3MB", ">3MB"])
    d["lat_buckets"] = bucketize(
        rts, [0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60, 120],
        ["<50ms", "50-100ms", "100-250ms", "250-500ms", "0.5-1s", "1-2s", "2-5s", "5-10s",
         "10-30s", "30-60s", "1-2min", ">2min"])
    d["ttfb_buckets"] = bucketize(
        tts, [0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60],
        ["<0.1s", "0.1-0.25s", "0.25-0.5s", "0.5-1s", "1-2s", "2-5s", "5-10s", "10-30s", "30-60s", ">60s"])

    # same weekday over the previous 4 weeks, for context
    dd = datetime.strptime(day, "%Y-%m-%d").date()
    peers = [(dd - timedelta(days=7 * i)).strftime("%Y-%m-%d") for i in range(4, -1, -1)]
    marks = ",".join("?" * len(peers))
    d["peers"] = q(f"SELECT day, COUNT(*) n, SUM(CASE WHEN status=200 THEN 1 ELSE 0 END) ok,"
                   f" AVG(rt) art, SUM(bytes_out) bout FROM requests WHERE day IN ({marks})"
                   f" AND ua NOT LIKE ? GROUP BY day ORDER BY day", (*peers, *like))

    # token usage recorded by the inline usage tap
    try:
        d["tokens"] = {
            "totals": q("""SELECT COUNT(*) n,
                SUM(CASE WHEN total_tokens IS NOT NULL THEN 1 ELSE 0 END) measured,
                SUM(CASE WHEN total_tokens IS NULL THEN 1 ELSE 0 END) unmeasured,
                SUM(COALESCE(input_tokens,0)) input,
                SUM(COALESCE(output_tokens,0)) output,
                SUM(COALESCE(cache_read,0)) cr,
                SUM(COALESCE(cache_write,0)) cw,
                SUM(COALESCE(reasoning,0)) rsn,
                SUM(COALESCE(total_tokens,0)) total,
                AVG(CASE WHEN total_tokens IS NOT NULL THEN total_tokens END) avgr,
                MAX(total_tokens) maxr
                FROM usage_events WHERE day = ?""", (day,))[0],
            "hourly": q("SELECT hour, SUM(COALESCE(total_tokens,0)) total,"
                        " SUM(COALESCE(input_tokens,0)) input, SUM(COALESCE(output_tokens,0)) output,"
                        " COUNT(*) n FROM usage_events WHERE day = ? GROUP BY hour ORDER BY hour", (day,)),
            "models": q("""SELECT COALESCE(model,'unknown') model, COUNT(*) n,
                SUM(COALESCE(input_tokens,0)) input, SUM(COALESCE(output_tokens,0)) output,
                SUM(COALESCE(cache_read,0)) cr, SUM(COALESCE(reasoning,0)) rsn,
                SUM(COALESCE(total_tokens,0)) total,
                AVG(CASE WHEN total_tokens IS NOT NULL THEN total_tokens END) avgr
                FROM usage_events WHERE day = ? GROUP BY model ORDER BY total DESC LIMIT 15""", (day,)),
            "protocols": q("""SELECT COALESCE(protocol,'?') protocol, COUNT(*) n,
                SUM(CASE WHEN total_tokens IS NOT NULL THEN 1 ELSE 0 END) measured,
                SUM(COALESCE(input_tokens,0)) input, SUM(COALESCE(output_tokens,0)) output,
                SUM(COALESCE(cache_read,0)) cr, SUM(COALESCE(total_tokens,0)) total
                FROM usage_events WHERE day = ? GROUP BY protocol ORDER BY total DESC""", (day,)),
            "routes": q("SELECT COALESCE(route,'?') route, COUNT(*) n,"
                        " SUM(COALESCE(total_tokens,0)) total FROM usage_events WHERE day = ?"
                        " GROUP BY route ORDER BY total DESC", (day,)),
            "sessions": q("SELECT sess, COUNT(*) n, SUM(COALESCE(total_tokens,0)) total, MAX(ts) last"
                          " FROM usage_events WHERE day = ? AND sess IS NOT NULL AND sess <> ''"
                          " GROUP BY sess ORDER BY total DESC LIMIT 10", (day,)),
            "buckets": q("""SELECT CASE
                WHEN total_tokens IS NULL THEN '未采集'
                WHEN total_tokens < 1000 THEN '<1K'
                WHEN total_tokens < 5000 THEN '1-5K'
                WHEN total_tokens < 20000 THEN '5-20K'
                WHEN total_tokens < 50000 THEN '20-50K'
                WHEN total_tokens < 100000 THEN '50-100K'
                ELSE '>100K' END bucket, COUNT(*) c,
                MIN(CASE WHEN total_tokens IS NULL THEN 999999999 ELSE total_tokens END) ord
                FROM usage_events WHERE day = ? GROUP BY bucket ORDER BY ord""", (day,)),
        }
    except Exception:
        d["tokens"] = None

    d["keys"] = json_or(KEYS_PATH, {})
    d["selfheal"] = json_or(SELFHEAL_PATH, {})
    d["modelstatus"] = json_or(MODELSTATUS_PATH, {})
    return d


def bucketize(values, edges, labels):
    counts = [0] * (len(edges) + 1)
    for v in values:
        if v is None:
            continue
        for i, e in enumerate(edges):
            if float(v) < e:
                counts[i] += 1
                break
        else:
            counts[-1] += 1
    return [{"bucket": lab, "count": c} for lab, c in zip(labels, counts)]


def json_or(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


# ── rendering ───────────────────────────────────────────────────────────────

def render(day, d):
    o = d["overview"] or {}
    lat = d["lat"]
    total = o.get("n") or 0
    ok = o.get("ok") or 0
    err = o.get("err") or 0
    succ = pct(ok, total)
    weekday = WEEKDAYS[datetime.strptime(day, "%Y-%m-%d").weekday()]

    cards = [
        card("请求总数", num(total), f"{weekday} · 成功 {num(ok)}"),
        card("成功率", f"{succ:.2f}%", f"错误 {num(err)}", OK if succ >= 95 else (WARN if succ >= 85 else BAD)),
        card("出站流量", human_bytes(o.get("bout")), f"入站 {human_bytes(o.get('bin'))}"),
        card("平均延迟", f"{num(o.get('art'), 3)}s", f"P95 {num(lat['p95'], 3)}s"),
        card("首字节 TTFB", f"{num(o.get('att'), 3)}s", f"P95 {num(lat['t95'], 3)}s"),
        card("流式请求", num(o.get("streaming")), f"占比 {pct(o.get('streaming'), total):.1f}%"),
        card("故障切换", num(o.get("retried")), f"总尝试 {num(o.get('attempts'))}"),
        card("活跃会话", num(o.get("sess")), f"客户端 {num(o.get('ips'))} · 模型 {num(o.get('models'))}"),
        card("最大延迟", f"{num(o.get('mrt'), 3)}s", "单请求最慢"),
        card("最大 TTFB", f"{num(o.get('mtt'), 3)}s", "首字节最慢"),
        card("连接复用", num(o.get("reused")), f"平均 {num(pct(o.get('reused'), total), 1)}%"),
        card("错误构成", f"4xx {num(o.get('e4'))}", f"5xx {num(o.get('e5'))} · 401 {num(o.get('e401'))}"),
    ]

    html = []
    html.append(section("一、总览", kpi_grid(cards)))

    # hourly
    hours = {h["hour"]: h for h in d["hourly"]}
    pairs = []
    for i in range(24):
        key = f"{day} {i:02d}"
        row = hours.get(key)
        pairs.append((f"{i:02d}", (row or {}).get("n") or 0))
    hrows = []
    for i in range(24):
        key = f"{day} {i:02d}"
        r = hours.get(key) or {}
        hrows.append([f"{i:02d}:00", num(r.get("n")), num(r.get("err")),
                      (f"{num(r.get('ttfb'), 3)}s" if r.get("ttfb") is not None else "—"),
                      human_bytes(r.get("bout")),
                      hbar(r.get("n") or 0, max([p[1] for p in pairs] or [1]))])
    html.append(section("二、每小时分布",
                        vchart(pairs) + '<div style="height:10px"></div>'
                        + table(["时段", "请求", "错误", "平均 TTFB", "出站", "占比"], hrows, align_right_from=1),
                        "柱状图为各小时请求量；周六/周日流量下降属正常。"))

    # status
    srows = []
    for s in d["status"]:
        st = s["status"]
        col = OK if st == 200 else (WARN if 400 <= (st or 0) < 500 else BAD)
        srows.append([f'<b style="color:{col}">{st}</b>', num(s["n"]), f"{pct(s['n'], total):.2f}%", num(s["a"]),
                      hbar(s["n"], max([x["n"] for x in d["status"]] or [1]), col)])
    html.append(section("三、状态码分布", table(["状态码", "响应数", "占比", "上游尝试", "分布"], srows, 1)))

    # models
    mrows = []
    mmax = max([m["n"] for m in d["models"]] or [1])
    for m in d["models"]:
        ms = pct(m["ok"], m["n"])
        mrows.append([f'<b>{m["model"]}</b>', f'<span style="color:{MUTED}">{m["route"]}</span>', num(m["n"]),
                      f"{ms:.1f}%", num(m["err"]), f"{num(m['art'], 3)}s", f"{num(m['att'], 3)}s",
                      human_bytes(m["bout"]), num(m["sess"]), hbar(m["n"], mmax)])
    html.append(section("四、模型维度（按请求量）",
                        table(["模型", "线路", "请求", "成功率", "错误", "平均延迟", "平均 TTFB",
                               "出站", "会话", "分布"], mrows, 2)))

    # routes + endpoints
    rrows = [[r["route"], num(r["n"]), f"{pct(r['ok'], r['n']):.1f}%", num(r["retried"]),
              f"{num(r['art'], 3)}s", f"{num(r['att'], 3)}s", human_bytes(r["bout"])] for r in d["routes"]]
    erows = [[e["uri"], num(e["n"]), human_bytes(e["bout"]), f"{num(e['art'], 3)}s", f"{num(e['att'], 3)}s"]
             for e in d["endpoints"]]
    html.append(section("五、线路与端点",
                        table(["线路", "请求", "成功率", "重试", "平均延迟", "平均 TTFB", "出站"], rrows, 1)
                        + '<div style="height:14px"></div>'
                        + table(["端点", "请求", "出站", "平均延迟", "平均 TTFB"], erows, 1)))

    # clients + ua
    crows = []
    cmax = max([c["n"] for c in d["clients"]] or [1])
    for c in d["clients"]:
        crows.append([f'<code>{c["ip"]}</code>', num(c["n"]), f"{pct(c['ok'], c['n']):.1f}%", num(c["err"]),
                      human_bytes(c["bin"]), human_bytes(c["bout"]), num(c["sess"]), num(c["models"]),
                      f"{num(c['art'], 3)}s", hbar(c["n"], cmax)])
    urows = [[f'<span style="font-size:11px">{u["ua"] or "(空)"}</span>', num(u["n"]),
              f"{pct(u['ok'], u['n']):.1f}%"] for u in d["uas"]]
    mrows2 = [[m["method"] or "(空)", num(m["n"])] for m in d["methods"]]
    crows2 = [[c["ctype"] or "(空)", num(c["n"])] for c in d["ctypes"]]
    html.append(section("六、客户端 / UA / 方法 / 内容类型",
                        table(["来源 IP", "请求", "成功率", "错误", "入站", "出站", "会话", "模型",
                               "平均延迟", "分布"], crows, 1)
                        + '<div style="height:14px"></div>'
                        + table(["User-Agent", "请求", "成功率"], urows, 1)
                        + '<div style="height:14px"></div>'
                        + table(["HTTP 方法", "请求"], mrows2, 1)
                        + '<div style="height:14px"></div>'
                        + table(["响应内容类型", "请求"], crows2, 1)))

    # latency / ttfb / sizes
    def hist_rows(buckets, unit=""):
        mx = max([b["count"] for b in buckets] or [1])
        return [[b["bucket"], num(b["count"]), f"{pct(b['count'], sum(x['count'] for x in buckets)):.1f}%",
                 hbar(b["count"], mx)] for b in buckets]

    lrows = [[k, f"{num(v, 3)}s"] for k, v in
             [("P50", lat["p50"]), ("P90", lat["p90"]), ("P95", lat["p95"]), ("P99", lat["p99"]),
              ("最大", o.get("mrt") or 0)]]
    trows = [[k, f"{num(v, 3)}s"] for k, v in
             [("P50", lat["t50"]), ("P90", lat["t90"]), ("P95", lat["t95"]), ("P99", lat["t99"]),
              ("最大", o.get("mtt") or 0)]]
    html.append(section("七、延迟 / 首字节 / 体积分布",
                        table(["延迟分位", "值"], lrows, 1) + '<div style="height:14px"></div>'
                        + table(["TTFB 分位", "值"], trows, 1) + '<div style="height:14px"></div>'
                        + table(["端到端延迟区间", "请求", "占比", "分布"], hist_rows(d["lat_buckets"]), 1)
                        + '<div style="height:14px"></div>'
                        + table(["首字节 TTFB 区间", "请求", "占比", "分布"], hist_rows(d["ttfb_buckets"]), 1)
                        + '<div style="height:14px"></div>'
                        + table(["请求体大小", "请求", "占比", "分布"], hist_rows(d["size_buckets"]), 1)
                        + '<div style="height:14px"></div>'
                        + table(["响应体大小", "请求", "占比", "分布"], hist_rows(d["resp_buckets"]), 1)))

    # sessions
    srows = []
    for s in d["sessions"]:
        sess = s["sess"] or ""
        f = datetime.fromtimestamp(s["first"], TZ).strftime("%H:%M:%S") if s["first"] else "—"
        l = datetime.fromtimestamp(s["last"], TZ).strftime("%H:%M:%S") if s["last"] else "—"
        srows.append([f'<code>{sess[:22]}</code>', num(s["n"]), f, l, num(s["models"]), num(s["ips"])])
    html.append(section("八、会话维度（Top 10）",
                        table(["会话", "请求", "首次", "末次", "模型数", "IP 数"], srows, 1)))

    # failover
    frows = [[num(r["attempts"]), num(r["n"])] for r in d["retries"]]
    urows = [[f'<code>{u["upstream_addr"]}</code>', num(u["n"])] for u in d["upstreams"]]
    usrows = [[u["upstream_status"], num(u["n"])] for u in d["up_status"]]
    html.append(section("九、重试与上游",
                        table(["尝试次数", "请求数"], frows, 1) + '<div style="height:14px"></div>'
                        + table(["上游地址（多次尝试会列出多个）", "请求"], urows, 1) + '<div style="height:14px"></div>'
                        + table(["上游返回状态（逗号分隔为多次尝试）", "请求"], usrows, 1)))

    # errors
    erows = []
    for e in d["errors"]:
        erows.append([f'<b style="color:{BAD}">{e["status"]}</b>', f'<code>{e["uri"]}</code>', e["model"],
                      num(e["n"]),
                      datetime.fromtimestamp(e["last"], TZ).strftime("%H:%M:%S") if e["last"] else "—"])
    rrows = []
    for e in d["recent_err"]:
        rrows.append([datetime.fromtimestamp(e["ts"], TZ).strftime("%H:%M:%S"), f'<b style="color:{BAD}">{e["status"]}</b>',
                      f'<code>{e["uri"]}</code>', e["model"], e["upstream_status"] or "—",
                      f"{num(e['rt'], 3)}s"])
    html.append(section("十、错误分析",
                        table(["状态", "端点", "模型", "次数", "最近"], erows, 3)
                        + '<div style="height:14px"></div>'
                        + table(["时间", "状态", "端点", "模型", "上游状态", "延迟"], rrows, 5)))

    # peers (same weekday)
    prows = []
    pmax = max([p["n"] for p in d["peers"]] or [1])
    for p in d["peers"]:
        prows.append([p["day"], num(p["n"]), f"{pct(p['ok'], p['n']):.1f}%", f"{num(p['art'], 3)}s",
                      human_bytes(p["bout"]), hbar(p["n"], pmax)])
    html.append(section("十一、同类日对比（最近 5 个同星期）",
                        table(["日期", "请求", "成功率", "平均延迟", "出站", "分布"], prows, 1)))

    # keys / quota
    krows = []
    for k in (d["keys"].get("keys") or []):
        w = ((k.get("usage") or {}).get("windows") or {})
        state = k.get("state", "unknown")
        col = OK if state == "ok" else (BAD if state == "exhausted" else WARN)
        krows.append([
            f'<b>{k.get("label") or "(未命名)"}</b>'
            + (' <span style="color:%s;font-size:11px">· 使用中</span>' % ACCENT if k.get("id") == d["keys"].get("active_id") else ""),
            f'<code>{k.get("masked") or ""}</code>',
            f'<b style="color:{col}">{state}</b>',
            f'{(w.get("rolling") or {}).get("percent", "—")}%',
            f'{(w.get("weekly") or {}).get("percent", "—")}%',
            f'{(w.get("monthly") or {}).get("percent", "—")}%',
            ((w.get("rolling") or {}).get("resets_at") or "—")[:16].replace("T", " "),
        ])
    srows2 = []
    for s in (d["keys"].get("switch_log") or [])[:8]:
        srows2.append([s.get("at", "")[:19].replace("T", " "), s.get("to") or "—",
                       "成功" if s.get("ok") else "失败", (s.get("detail") or "")[:40]])
    quota_body = table(["Key", "标识", "状态", "5 小时", "每周", "每月", "5 小时窗口重置"], krows, 3) \
        if krows else '<div style="color:#6b7684">（key 池为空）</div>'
    html.append(section("十二、Key 池与配额", quota_body
                        + ('<div style="height:14px"></div>'
                           + table(["切换时间", "切换到", "结果", "详情"], srows2, 2) if srows2 else ""),
                        "中继按优先级选择可用 key；任一把某个窗口用满即自动切换，全部用满时返回 503 no_quota。"))

    # selfheal + model status
    sh = d["selfheal"] or {}
    shrows = [[c.get("name"), "✅ 通过" if c.get("ok") else "❌ 失败", (c.get("detail") or "")[:70],
               f'{c.get("ms", 0)}ms'] for c in (sh.get("checks") or [])]
    ms = d["modelstatus"] or {}
    msrows = []
    for prov, pdata in (ms.get("providers") or {}).items():
        for mid, mdata in ((pdata or {}).get("models") or {}).items():
            msrows.append([prov, mid, mdata.get("status", "—"), mdata.get("via", "—"),
                           str(mdata.get("code", "—")), mdata.get("checked_at", "")[:16].replace("T", " ")])
    html.append(section("十三、自愈与模型巡检",
                        table(["检查项", "结果", "详情", "耗时"], shrows, 3) if shrows
                        else '<div style="color:#6b7684">（无自愈记录）</div>'
                        + '<div style="height:14px"></div>'
                        + (table(["Provider", "模型", "状态", "协议", "HTTP", "检查时间"], msrows, 4)
                           if msrows else ""),
                        f'自愈状态 {sh.get("status", "—")} · 检查时间 {sh.get("checked_at", "—")}'))

    # tokens
    tk = d.get("tokens")
    if tk and (tk["totals"] or {}).get("n"):
        t = tk["totals"]
        measured = t.get("measured") or 0
        cov = pct(measured, t.get("n") or 1)
        cards = [
            card("总 Token", num(t.get("total")), f"{num(measured)} 个请求已采集"),
            card("输入 Token", num(t.get("input")),
                 f"占比 {pct(t.get('input'), t.get('total')):.1f}%"),
            card("输出 Token", num(t.get("output")),
                 f"占比 {pct(t.get('output'), t.get('total')):.1f}%"),
            card("缓存读取", num(t.get("cr")),
                 f"命中率 {pct(t.get('cr'), (t.get('input') or 0) + (t.get('cr') or 0)):.2f}%"),
            card("单请求均值", num(t.get("avgr")), f"峰值 {num(t.get('maxr'))}"),
            card("采集覆盖率", f"{cov:.1f}%", f"{num(t.get('unmeasured'))} 个请求无 token"),
        ]
        hours = {h["hour"]: h for h in (tk.get("hourly") or [])}
        pairs = [(f"{i:02d}", (hours.get(f"{day} {i:02d}") or {}).get("total") or 0) for i in range(24)]
        hrows = [[f"{i:02d}:00",
                  num((hours.get(f'{day} {i:02d}') or {}).get("n")),
                  num((hours.get(f'{day} {i:02d}') or {}).get("input")),
                  num((hours.get(f'{day} {i:02d}') or {}).get("output")),
                  num((hours.get(f'{day} {i:02d}') or {}).get("total")),
                  hbar((hours.get(f'{day} {i:02d}') or {}).get("total") or 0,
                       max([p[1] for p in pairs] or [1]))] for i in range(24)]
        mmax = max([m["total"] for m in (tk.get("models") or [])] or [1])
        mrows = [[m["model"], num(m["n"]), num(m["input"]), num(m["output"]), num(m["cr"]),
                  num(m["avgr"]), f'<b>{num(m["total"])}</b>', hbar(m["total"], mmax)]
                 for m in (tk.get("models") or [])]
        prows = [[p["protocol"], num(p["n"]), num(p["measured"]), num(p["input"]), num(p["output"]),
                  num(p["cr"]), f'<b>{num(p["total"])}</b>'] for p in (tk.get("protocols") or [])]
        rrows = [[r["route"], num(r["n"]), f'<b>{num(r["total"])}</b>'] for r in (tk.get("routes") or [])]
        srows3 = [[f'<code>{str(s0["sess"])[:22]}</code>', num(s0["n"]), f'<b>{num(s0["total"])}</b>',
                   datetime.fromtimestamp(s0["last"], TZ).strftime("%H:%M:%S") if s0["last"] else "—"]
                  for s0 in (tk.get("sessions") or [])]
        bmax = max([b["c"] for b in (tk.get("buckets") or [])] or [1])
        brows = [[b["bucket"], num(b["c"]), f'{pct(b["c"], sum(x["c"] for x in tk["buckets"])):.1f}%',
                  hbar(b["c"], bmax)] for b in (tk.get("buckets") or [])]
        html.append(section("十四、Token 统计",
                            kpi_grid(cards)
                            + '<div style="height:12px"></div>' + vchart(pairs, color="#a78bfa")
                            + '<div style="height:12px"></div>'
                            + table(["时段", "请求", "输入", "输出", "Token 合计", "分布"], hrows, 1)
                            + '<div style="height:14px"></div>'
                            + table(["模型", "请求", "输入", "输出", "缓存读", "平均/次", "合计", "分布"],
                                    mrows, 1)
                            + '<div style="height:14px"></div>'
                            + table(["协议", "请求", "已采集", "输入", "输出", "缓存读", "合计"], prows, 1)
                            + '<div style="height:14px"></div>'
                            + table(["线路", "请求", "Token"], rrows, 1)
                            + '<div style="height:14px"></div>'
                            + table(["会话", "请求", "Token", "最近"], srows3, 1)
                            + '<div style="height:14px"></div>'
                            + table(["单请求 Token 区间", "请求", "占比", "分布"], brows, 1),
                            "Token 由中继内联采集（解析上游返回的 usage 字段：Anthropic 取 "
                            "message_delta.usage，OpenAI 取 usage），非按字节估算。输入已统一为未缓存输入，"
                            "因此 输入 + 缓存读 + 缓存写 + 输出 = 合计。"))

    body = "".join(html)
    return f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>OpenCode 中继日报 {day}</title></head>
<body style="margin:0;padding:0;background:{BG}">
<div style="max-width:860px;margin:0 auto;padding:20px 16px 40px;font-family:-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif">
  <div style="background:#0b1220;border-radius:12px;padding:20px 22px;color:#e8eef7">
    <div style="font-size:20px;font-weight:700">OpenCode 中继 · 每日报表</div>
    <div style="font-size:13px;color:#9fb0c4;margin-top:4px">{day}（{weekday}）· 中继 http://8.153.153.176 · 统计口径已排除模型巡检探测流量</div>
    <div style="font-size:12px;color:#9fb0c4;margin-top:8px">
      请求 <b style="color:#fff">{num(total)}</b> · 成功率 <b style="color:#fff">{succ:.2f}%</b> ·
      出站 <b style="color:#fff">{human_bytes(o.get('bout'))}</b> · 平均延迟 <b style="color:#fff">{num(o.get('art'), 3)}s</b>
    </div>
  </div>
  {body}
  <div style="margin-top:28px;padding-top:14px;border-top:1px solid {LINE};font-size:11px;color:{MUTED}">
    由 relay-daily-report.timer 于每天 00:05（Asia/Shanghai）自动生成 · 数据源 /var/lib/relay/relay.db ·
    图表为纯 HTML/CSS 渲染，兼容各邮件客户端。
  </div>
</div></body></html>"""


def csv_attachment(day):
    rows = q("SELECT * FROM requests WHERE day = ? ORDER BY ts", (day,))
    if not rows:
        return None, 0
    buf = io.StringIO()
    w = csv.DictWriter(buf, fieldnames=list(rows[0].keys()))
    w.writeheader()
    for r in rows:
        w.writerow(r)
    data = ("\ufeff" + buf.getvalue()).encode("utf-8")
    return data, len(rows)


# ── delivery ────────────────────────────────────────────────────────────────

def load_config():
    cfg = json_or(CONFIG_PATH, None)
    if not cfg:
        raise SystemExit(f"missing {CONFIG_PATH}")
    e = cfg.get("EmailConfigruation") or cfg.get("EmailConfiguration") or cfg
    return {
        "host": e.get("SMTPHost", "smtp.163.com"),
        "port": int(e.get("SMTPPort", 25)),
        "user": e.get("EmailAccount", ""),
        "password": e.get("EmailAccountPassword", ""),
        "from_name": e.get("EmailAccountDisplayName", "OpenCode Relay"),
        "use_ssl": bool(e.get("SMTPUseSSL", False)),
        "starttls": bool(e.get("SMTPStartTLS", True)),
        "to": cfg.get("to") or [DEFAULT_TO],
    }


def _connect(cfg, port, mode):
    import ssl
    if mode == "ssl":
        return smtplib.SMTP_SSL(cfg["host"], port, timeout=45, context=ssl.create_default_context())
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


# Cloud hosts routinely block outbound 25 (Aliyun does), so a send that only
# honours the configured port silently fails. Try the configured transport first,
# then the usual ladder; first one that authenticates and accepts wins.
TRANSPORTS = [(465, "ssl"), (587, "starttls"), (25, "starttls"), (25, "plain")]


def send_mail(cfg, to, subject, html, attachments=()):
    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = formataddr((cfg["from_name"], cfg["user"]))
    msg["To"] = ", ".join(to)
    msg["Date"] = formatdate(localtime=True)
    msg.set_content("本邮件为 HTML 格式，请使用支持 HTML 的邮件客户端查看。")
    msg.add_alternative(html, subtype="html")
    for name, data, mtype, subtype in attachments:
        msg.add_attachment(data, maintype=mtype, subtype=subtype, filename=name)

    configured = (cfg["port"], "ssl" if cfg["use_ssl"] else ("starttls" if cfg["starttls"] else "plain"))
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
            return f"{port}/{mode}"
        except Exception as exc:
            errors.append(f"{port}/{mode}: {type(exc).__name__}: {str(exc)[:90]}")
    raise SystemExit("SMTP failed on every transport:\n  " + "\n  ".join(errors))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", help="YYYY-MM-DD (default: yesterday, Asia/Shanghai)")
    ap.add_argument("--dry-run", action="store_true", help="write the HTML, do not send")
    ap.add_argument("--to", action="append", help="recipient (repeatable)")
    ap.add_argument("--no-csv", action="store_true")
    args = ap.parse_args()

    day = args.date or (datetime.now(TZ).date() - timedelta(days=1)).strftime("%Y-%m-%d")
    if args.date:
        datetime.strptime(args.date, "%Y-%m-%d")

    d = collect(day)
    html = render(day, d)

    os.makedirs(REPORT_DIR, exist_ok=True)
    path = os.path.join(REPORT_DIR, f"report-{day}.html")
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)
    os.chmod(path, 0o644)
    print(f"report written: {path} ({len(html):,} bytes)")

    if args.dry_run:
        print("dry run: not sending")
        return 0

    cfg = load_config()
    to = args.to or cfg["to"]
    if isinstance(to, str):
        to = [to]
    attachments = []
    if not args.no_csv:
        data, n = csv_attachment(day)
        if data:
            attachments.append((f"opencode-relay-{day}.csv", data, "text", "csv"))
            print(f"csv attached: {n} rows, {len(data):,} bytes")

    o = d["overview"] or {}
    subject = (f"[OpenCode 中继日报] {day} · 请求 {num(o.get('n'))} · "
               f"成功率 {pct(o.get('ok'), o.get('n')):.2f}% · 出站 {human_bytes(o.get('bout'))}")
    used = send_mail(cfg, to, subject, html, attachments)
    print(f"sent to {', '.join(to)} via {cfg['host']}:{used}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
