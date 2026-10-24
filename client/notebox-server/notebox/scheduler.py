"""Background mail scheduler.

Two reminders per todo (when SMTP is configured):
  * pre1d  — 1 day before the deadline
  * pre10m — 10 minutes before the deadline
All-day todos (due_date only) are reminded at 09:00 the day before and at
08:50 on the due day. A reminder whose fire time passed more than 2h ago is
skipped instead of spamming stale alerts; each slot retries up to 3 times
with a 5-minute backoff.
"""
import html
import json
import threading
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from . import db, mailer
from .mailtokens import quick_token
from .routes_folders import folder_path_text

_stop = threading.Event()

REMIND_1D = 86400   # 提前 1 天（秒）
REMIND_10M = 600    # 提前 10 分钟（秒）
GRACE = 7200        # 过期超过 2 小时不再补发
ALLDAY_REMIND_HOUR = 9  # 全天事项的提醒时刻 09:00
SLOT_BACKOFF = 300   # 失败后每个槽位的重试间隔
SLOT_MAX_TRIES = 3


def start_scheduler(interval: int = 30) -> threading.Thread:
    t = threading.Thread(target=_loop, args=(interval,), daemon=True, name="notebox-mailer")
    t.start()
    return t


def stop_scheduler() -> None:
    _stop.set()


def _loop(interval: int) -> None:
    while not _stop.is_set():
        try:
            tick()
        except Exception:  # noqa: BLE001 - scheduler must never die
            pass
        _stop.wait(interval)


def _tz(cfg: dict) -> ZoneInfo:
    from .timeutil import get_tz

    return get_tz(cfg.get("digest_tz") or "Asia/Shanghai")


def _fmt(ts: int, tz: ZoneInfo) -> str:
    return datetime.fromtimestamp(ts, tz).strftime("%Y-%m-%d %H:%M")


def _weekday(ts: int, tz: ZoneInfo) -> str:
    names = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]
    return names[datetime.fromtimestamp(ts, tz).weekday()]


def countdown_text(seconds: int) -> str:
    if seconds < 0:
        m = -seconds // 60
        if m >= 60:
            return f"已过期 {m // 60} 小时 {m % 60} 分钟"
        return f"已过期 {m} 分钟"
    if seconds >= 86400:
        return f"还有 {seconds // 86400} 天 {seconds % 86400 // 3600} 小时"
    if seconds >= 3600:
        return f"还有 {seconds // 3600} 小时 {seconds % 3600 // 60} 分钟"
    return f"还有 {max(seconds // 60, 1)} 分钟"


def reminder_times(t: dict, tz: ZoneInfo) -> list[tuple[str, int]]:
    """Return [(kind, fire_at)] for one todo. kind: 'pre1d' | 'pre10m'."""
    if t.get("due_at"):
        due = int(t["due_at"])
        return [("pre1d", due - REMIND_1D), ("pre10m", due - REMIND_10M)]
    if t.get("due_date"):
        try:
            d = date.fromisoformat(str(t["due_date"]))
        except ValueError:
            return []
        nine = datetime(d.year, d.month, d.day, ALLDAY_REMIND_HOUR, 0, tzinfo=tz)
        day_before = nine - timedelta(days=1)
        return [("pre1d", int(day_before.timestamp())), ("pre10m", int(nine.timestamp()) - REMIND_10M)]
    return []


PRI_LABEL = {3: "高", 2: "中", 1: "低", 0: "无"}
PRI_COLOR = {3: ("#fee2e2", "#dc2626", "#fecaca"), 2: ("#fef3c7", "#b45309", "#fde68a"),
             1: ("#e0e7ff", "#4338ca", "#c7d2fe"), 0: ("#f3f4f6", "#6b7280", "#e5e7eb")}
REPEAT_LABEL = {"none": "", "daily": "每天重复", "weekdays": "每个工作日重复",
                "weekly": "每周重复", "monthly": "每月重复"}


def _subtasks(t: dict) -> list:
    try:
        val = json.loads(t.get("subtasks") or "[]")
    except (TypeError, ValueError):
        return []
    return val if isinstance(val, list) else []


def _list_path(t: dict) -> str:
    if not t.get("folder_id"):
        return ""
    return folder_path_text(t.get("owner_id") or 0, t["folder_id"])


def _ics_escape(text: str) -> str:
    return (text or "").replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")


def ics_attachment(t: dict, tz: ZoneInfo) -> list[tuple]:
    """Calendar invite attached to reminder mails (opens in Outlook/Apple/Google)."""
    lines = [
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//NoteBox//Todo//CN", "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH", "BEGIN:VEVENT", f"UID:notebox-todo-{t['id']}@notebox",
        f"DTSTAMP:{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}",
        f"SUMMARY:{_ics_escape(t.get('title') or '待办')}",
    ]
    desc = [t.get("detail") or ""]
    subs = _subtasks(t)
    if subs:
        desc.append("子任务：")
        desc += [("[x] " if s.get("done") else "[ ] ") + str(s.get("title") or "") for s in subs]
    path = _list_path(t)
    if path:
        desc.append("清单：" + path)
    if desc:
        lines.append("DESCRIPTION:" + _ics_escape("\n".join(x for x in desc if x is not None)))
    if t.get("due_at"):
        start = datetime.fromtimestamp(int(t["due_at"]), tz).astimezone(timezone.utc)
        end = start + timedelta(hours=1)
        lines += [f"DTSTART:{start.strftime('%Y%m%dT%H%M%SZ')}",
                  f"DTEND:{end.strftime('%Y%m%dT%H%M%SZ')}"]
    elif t.get("due_date"):
        d = date.fromisoformat(str(t["due_date"]))
        lines += [f"DTSTART;VALUE=DATE:{d.strftime('%Y%m%d')}",
                  f"DTEND;VALUE=DATE:{(d + timedelta(days=1)).strftime('%Y%m%d')}"]
    lines += [
        "BEGIN:VALARM", "TRIGGER:-PT10M", "ACTION:DISPLAY",
        "DESCRIPTION:" + _ics_escape("NoteBox 待办提醒"), "END:VALARM",
        "END:VEVENT", "END:VCALENDAR", "",
    ]
    ics = "\r\n".join(lines)
    return [("notebox-todo.ics", ics.encode("utf-8"), "text", "calendar")]


def _badge(text: str, colors: tuple) -> str:
    bg, fg, bd = colors
    return (f"<span style='display:inline-block;padding:2px 10px;border-radius:999px;font-size:12px;"
            f"font-weight:700;background:{bg};color:{fg};border:1px solid {bd}'>{html.escape(text)}</span>")


def _upcoming_lines(t: dict, tz: ZoneInfo, limit: int = 5) -> list[str]:
    now = db.now()
    horizon = now + 2 * 86400
    rows = db.rows(
        "SELECT * FROM todos WHERE status='pending' AND owner_id=? AND id != ? "
        "AND (due_at BETWEEN ? AND ? OR due_date BETWEEN ? AND ?) "
        "ORDER BY priority DESC, COALESCE(due_at, 0) ASC, due_date ASC LIMIT ?",
        (t.get("owner_id"), t["id"], now - 86400, horizon,
         (datetime.now(tz) - timedelta(days=1)).date().isoformat(),
         datetime.fromtimestamp(horizon, tz).date().isoformat(), limit),
    )
    out = []
    for r in rows:
        when = _fmt(r["due_at"], tz) if r["due_at"] else f"{r['due_date']}（全天）"
        pri = PRI_LABEL.get(r["priority"], "")
        out.append(f"<li>{html.escape(when)} &nbsp;<b>{html.escape(r['title'])}</b>"
                   f"&nbsp;<span style='color:#9ca3af'>· {pri}优先级</span></li>")
    return out


def build_reminder(t: dict, kind: str, fire_at: int, cfg: dict, tz: ZoneInfo) -> tuple[str, str]:
    """Build (subject, html_body) for one reminder."""
    now = db.now()
    all_day = bool(t.get("due_date")) and not t.get("due_at")
    due_ts = int(t["due_at"]) if t.get("due_at") else None

    if t.get("due_at"):
        due_label = f"{_fmt(due_ts, tz)}（{_weekday(due_ts, tz)}）"
        due_end_ts = due_ts
    elif t.get("due_date"):
        due_label = f"{t['due_date']}（全天）"
        due_end_ts = int(datetime.fromisoformat(t["due_date"]).replace(tzinfo=tz).timestamp())
    else:
        due_label = "未设置截止时间"
        due_end_ts = now

    if kind == "manual":
        head = "待办详情提醒"
        slot_text = "手动发送 · 完整详情"
        icon = "bell"
    elif kind == "pre1d":
        head = "明天到期" if all_day else "提前 1 天提醒"
        slot_text = "第 1 次提醒 · 截止前 1 天"
        icon = "bell"
    else:
        head = "今天到期" if all_day else "10 分钟内到期"
        slot_text = "第 2 次提醒 · 截止前 10 分钟"
        icon = "warn"

    remaining = countdown_text(due_end_ts - now)
    pri = int(t.get("priority") or 1)
    pri_badge = _badge(f"{PRI_LABEL.get(pri, '低')}优先级", PRI_COLOR.get(pri, PRI_COLOR[1]))
    repeat_label = REPEAT_LABEL.get(t.get("repeat") or "none", "")
    badges = pri_badge + " &nbsp;" + _badge(slot_text, ("#eef2ff", "#4338ca", "#c7d2fe"))
    if repeat_label:
        badges += " &nbsp;" + _badge("↻ " + repeat_label, ("#ecfdf5", "#047857", "#a7f3d0"))

    rows_html = [
        ("截止时间", f"<b>{html.escape(due_label)}</b>"),
        ("剩余", f"<b style='color:#4f46e5'>{html.escape(remaining)}</b>"),
        ("状态", "待完成（未完成）"),
    ]
    path = _list_path(t)
    if path:
        rows_html.append(("清单", html.escape(path)))
    tags = [x.strip() for x in str(t.get("tags") or "").split(",") if x.strip()]
    if tags:
        chips = " ".join(
            f"<span style='display:inline-block;padding:1px 8px;margin:0 4px 4px 0;border-radius:999px;"
            f"background:#eef2ff;color:#4338ca;font-size:12px'>#{html.escape(tag)}</span>" for tag in tags
        )
        rows_html.append(("标签", chips))
    subs = _subtasks(t)
    if subs:
        done = sum(1 for s in subs if s.get("done"))
        pct = int(done * 100 / len(subs))
        items = "".join(
            "<div style='padding:3px 0;font-size:13.5px;"
            + ("color:#9ca3af;text-decoration:line-through" if s.get("done") else "color:#334155")
            + "'>" + ("☑ " if s.get("done") else "☐ ") + html.escape(str(s.get("title") or "")) + "</div>"
            for s in subs
        )
        rows_html.append((
            "子任务",
            f"<div style='margin-bottom:5px;color:#475569'>{done}/{len(subs)} 已完成 · {pct}%</div>"
            f"<div style='height:6px;background:#e5e7eb;border-radius:3px;overflow:hidden'>"
            f"<div style='height:6px;width:{pct}%;background:#4f46e5'></div></div>"
            f"<div style='margin-top:8px'>{items}</div>",
        ))

    lines = [
        f"<div style='margin-bottom:10px'>{badges}</div>",
        f"<div style='font-size:17px;font-weight:800;color:#111827;margin-bottom:8px'>{html.escape(t['title'])}</div>",
        "<table style='width:100%;font-size:14px;border-collapse:collapse'>"
        + "".join(
            f"<tr><td style='color:#6b7280;padding:4px 0;width:70px;vertical-align:top'>{k}</td>"
            f"<td style='padding:4px 0'>{v}</td></tr>" for k, v in rows_html
        )
        + "</table>",
    ]
    if t.get("detail"):
        detail = html.escape(t["detail"]).replace("\n", "<br>")
        lines.append(
            "<div style='margin-top:8px;padding:10px 12px;background:#f8fafc;border:1px solid #e5e7eb;"
            "border-radius:8px;font-size:13.5px;color:#475569'><b>详细内容：</b><br>" + detail + "</div>"
        )
    others = _upcoming_lines(t, tz)
    if others:
        lines.append(
            "<div style='margin-top:14px;font-size:13px;color:#6b7280'>接下来的安排</div>"
            "<ul style='margin:6px 0 0;padding-left:18px;font-size:13.5px;color:#374151'>" + "".join(others) + "</ul>"
        )
    lines.append(
        f"<div style='margin-top:14px;font-size:12px;color:#9ca3af'>此邮件于 {_fmt(now, tz)} 由 NoteBox "
        + ("手动发送" if kind == "manual" else "自动发送（规则：截止前 1 天、截止前 10 分钟各一封）")
        + "，并附带日历邀请。</div>"
    )

    when_short = _fmt(due_ts, tz) if due_ts else str(t["due_date"])
    subject = f"[NoteBox] {head}：{t['title']}（{when_short} 截止）"
    base = (cfg.get("base_url") or "").rstrip("/")
    extra = ""
    if base:
        link = f"{base}/api/todos/{t['id']}/quick-complete/{quick_token(t)}"
        extra = (
            "<p style='margin-top:8px'><a href='" + html.escape(link) + "' style='background:#ecfdf5;color:#047857;"
            "border:1px solid #a7f3d0;padding:9px 16px;border-radius:8px;text-decoration:none;"
            "display:inline-block;font-size:13.5px'>✓ 一键标记完成</a></p>"
        )
    body = mailer.render_mail(f"{head}：{t['title']}", lines, button=True,
                              base_url=base, icon=icon, extra_html=extra)
    return subject, body


def _owner_email(uid, cfg: dict) -> str | None:
    """Recipient for one owner's mail; None means 'global configured recipient'."""
    if uid:
        u = db.row("SELECT email FROM users WHERE id=?", (uid,))
        if u and "@" in (u["email"] or ""):
            return u["email"]
    return None


def _reminders(cfg: dict) -> None:
    now = db.now()
    tz = _tz(cfg)
    for t in db.rows("SELECT * FROM todos WHERE status='pending' AND remind=1"):
        for kind, fire_at in reminder_times(t, tz):
            sent_col = f"sent_{kind}"
            tries_col = f"tries_{kind}"
            last_col = f"last_{kind}"
            if t.get(sent_col):
                continue
            if int(t.get(tries_col) or 0) >= SLOT_MAX_TRIES:
                continue
            if now < fire_at:
                continue
            if now > fire_at + GRACE:
                db.execute(f"UPDATE todos SET {sent_col}=1 WHERE id=?", (t["id"],))
                continue
            last = t.get(last_col)
            if last and now - int(last) < SLOT_BACKOFF:
                continue
            subject, body = build_reminder(t, kind, fire_at, cfg, tz)
            ok, _err = mailer.send(subject, body, kind="reminder",
                                   to_override=_owner_email(t.get("owner_id"), cfg),
                                   attachments=ics_attachment(t, tz))
            db.execute(
                f"UPDATE todos SET {tries_col}={tries_col}+1, {last_col}=?, "
                f"{sent_col}=CASE WHEN ? THEN 1 ELSE {sent_col} END, updated_at=? WHERE id=?",
                (now, 1 if ok else 0, now, t["id"]),
            )


def _digest(cfg: dict) -> None:
    if cfg.get("digest_enabled") != "1":
        return
    now = db.now()
    tz = _tz(cfg)
    local = datetime.fromtimestamp(now, tz)
    hh, mm = (cfg.get("digest_time") or "08:30").split(":")[:2]
    target = local.replace(hour=int(hh), minute=int(mm), second=0, microsecond=0)
    target_ts = int(target.timestamp())
    if now < target_ts:
        return
    if int(cfg.get("digest_last_epoch") or 0) >= target_ts:
        return
    if now - int(cfg.get("digest_last_attempt") or 0) < 300:
        return
    db.set_setting("digest_last_attempt", str(now))

    today = local.date()
    today_start = int(datetime(today.year, today.month, today.day, 0, 0, tzinfo=tz).timestamp())
    tomorrow_start = today_start + 86400

    # one digest per owner, delivered to that owner's mailbox
    owners = [o["owner_id"] for o in db.rows("SELECT DISTINCT owner_id FROM todos WHERE status='pending'")]
    any_ok = False
    sent_count = 0

    for oid in owners:
        overdue_lines, today_lines = [], []
        rows = (
            db.rows("SELECT * FROM todos WHERE status='pending' AND owner_id=? ORDER BY priority DESC, created_at ASC", (oid,))
            if oid is not None
            else db.rows("SELECT * FROM todos WHERE status='pending' AND owner_id IS NULL ORDER BY priority DESC, created_at ASC")
        )
        for t in rows:
            due_at, due_date = t.get("due_at"), t.get("due_date")
            bucket = None
            if due_at and due_at < now:
                bucket = "overdue"
            elif due_date and due_date < today.isoformat():
                bucket = "overdue"
            elif due_at and today_start <= due_at < tomorrow_start:
                bucket = "today"
            elif due_date == today.isoformat():
                bucket = "today"
            if not bucket:
                continue
            when = _fmt(due_at, tz) if due_at else due_date
            meta = []
            path = _list_path(t)
            if path:
                meta.append(html.escape(path))
            subs = _subtasks(t)
            if subs:
                meta.append(f"子任务 {sum(1 for s in subs if s.get('done'))}/{len(subs)}")
            tags = [x.strip() for x in str(t.get("tags") or "").split(",") if x.strip()]
            if tags:
                meta.append(" ".join("#" + html.escape(x) for x in tags))
            detail = str(t.get("detail") or "").strip()
            extra = ""
            if detail:
                extra = (
                    "<br><span style='color:#64748b;font-size:12.5px'>"
                    + html.escape(detail[:180]).replace("\n", " ")
                    + ("…" if len(detail) > 180 else "") + "</span>"
                )
            meta_html = (
                "<br><span style='color:#9ca3af;font-size:12px'>" + " · ".join(meta) + "</span>"
                if meta else ""
            )
            line = (
                f"{'<span style=color:#dc2626><b>[已过期]</b></span> ' if bucket == 'overdue' else '<b>[今天]</b> '}"
                f"{html.escape(t['title'])} <span style=color:#9ca3af>({when})</span>"
                f"{meta_html}{extra}"
            )
            (overdue_lines if bucket == "overdue" else today_lines).append(line)

        total = len(overdue_lines) + len(today_lines)
        if total == 0:
            continue
        sent_count += 1
        body = mailer.render_mail(
            f"今日待办：{total} 项（过期 {len(overdue_lines)} / 今天 {len(today_lines)}）",
            overdue_lines + today_lines,
            button=True,
            base_url=cfg.get("base_url") or "",
            icon="calendar",
        )
        ok, _err = mailer.send(
            f"[NoteBox] 每日摘要 {today.isoformat()}：{total} 项待办",
            body,
            kind="digest",
            to_override=_owner_email(oid, cfg),
        )
        if ok:
            any_ok = True

    # nothing due for anyone, or at least one digest delivered -> done for today
    if sent_count == 0 or any_ok:
        db.set_setting("digest_last_epoch", str(target_ts))


def tick() -> None:
    """One scheduler pass; safe to call manually (used by tests)."""
    cfg = db.all_settings()
    if cfg.get("notify_enabled") != "1" or not mailer.smtp_ready(cfg):
        return
    _reminders(cfg)
    _digest(cfg)
