"""Todo CRUD — owner-scoped, with folders, tags, checklists and repeats."""
import calendar
import html
import json
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from flask import Blueprint, g, jsonify, request

from . import db, mailer, scheduler
from .auth import login_required, rate_hit
from .mailtokens import verify_quick_token
from .routes_folders import descendant_ids
from .timeutil import get_tz

bp = Blueprint("todos", __name__)

VALID_STATUS = {"pending", "done"}
VALID_REPEAT = {"none", "daily", "weekdays", "weekly", "monthly"}
REPEAT_LABEL = {"none": "不重复", "daily": "每天", "weekdays": "每个工作日",
                "weekly": "每周", "monthly": "每月"}


def _me() -> dict:
    return g.user


def _folder_for(raw):
    if raw in (None, "", 0, "0"):
        return None
    try:
        fid = int(raw)
    except (TypeError, ValueError):
        raise ValueError("清单不存在")
    if not db.row("SELECT 1 FROM folders WHERE id=? AND owner_id=? AND kind='todo'", (fid, _me()["id"])):
        raise ValueError("清单不存在")
    return fid


def _normalize_subtasks(raw) -> str:
    items = []
    if not isinstance(raw, list):
        return "[]"
    next_id = 1
    for it in raw[:100]:
        if not isinstance(it, dict):
            continue
        title = str(it.get("title") or "").strip()[:200]
        if not title:
            continue
        try:
            sid = int(it.get("id") or 0)
        except (TypeError, ValueError):
            sid = 0
        if sid <= 0 or any(x["id"] == sid for x in items):
            sid = next_id
        next_id = max(next_id, sid + 1)
        items.append({"id": sid, "title": title, "done": 1 if it.get("done") else 0})
    return json.dumps(items, ensure_ascii=False, separators=(",", ":"))


def _subtasks_list(raw: str) -> list:
    try:
        val = json.loads(raw or "[]")
    except (TypeError, ValueError):
        return []
    return val if isinstance(val, list) else []


def _clean(data: dict, base: dict | None = None) -> dict:
    base = base or {}
    title = str(data.get("title", base.get("title", ""))).strip()
    if not title:
        raise ValueError("标题不能为空")
    priority = data.get("priority", base.get("priority", 1))
    try:
        priority = max(0, min(3, int(priority)))
    except (TypeError, ValueError):
        priority = 1
    status = data.get("status", base.get("status", "pending"))
    if status not in VALID_STATUS:
        status = "pending"
    due_at = data.get("due_at", base.get("due_at"))
    due_at = int(due_at) if due_at else None
    due_date = str(data.get("due_date", base.get("due_date") or "") or "").strip() or None
    if due_date and len(due_date) != 10:
        raise ValueError("日期格式应为 YYYY-MM-DD")
    repeat = str(data.get("repeat", base.get("repeat") or "none")).strip()
    if repeat not in VALID_REPEAT:
        repeat = "none"
    if repeat != "none" and not (due_at or due_date):
        raise ValueError("重复事项需要设置截止时间")
    remind = data.get("remind", base.get("remind", 1))
    subtasks = _normalize_subtasks(data["subtasks"]) if "subtasks" in data else (base.get("subtasks") or "[]")
    return {
        "title": title[:500],
        "detail": str(data.get("detail", base.get("detail", "")))[:5000],
        "status": status,
        "priority": priority,
        "due_at": due_at,
        "due_date": due_date,
        "remind": 1 if remind in (1, True, "1", "true") else 0,
        "folder_id": _folder_for(data.get("folder_id", base.get("folder_id"))),
        "tags": str(data.get("tags", base.get("tags") or "")).strip()[:200],
        "subtasks": subtasks,
        "repeat": repeat,
    }


def _mine(tid: int):
    """Return the todo only when it belongs to the caller (404 otherwise)."""
    t = db.row("SELECT * FROM todos WHERE id=?", (tid,))
    if not t or t.get("owner_id") != _me()["id"]:
        return None
    return t


def _join_folder(t: dict | None) -> dict | None:
    if not t:
        return t
    t["folder_name"] = ""
    t["folder_color"] = ""
    if t.get("folder_id"):
        f = db.row("SELECT name,color FROM folders WHERE id=?", (t["folder_id"],))
        if f:
            t["folder_name"] = f["name"]
            t["folder_color"] = f["color"]
    subs = _subtasks_list(t.get("subtasks"))
    t["subtasks"] = subs
    t["subtasks_done"] = sum(1 for x in subs if x.get("done"))
    t["subtasks_total"] = len(subs)
    t["repeat_label"] = REPEAT_LABEL.get(t.get("repeat") or "none", "")
    return t


@bp.get("/api/todos")
@login_required
def list_todos():
    status = request.args.get("status") or ""
    q = (request.args.get("q") or "").strip()
    tag = (request.args.get("tag") or "").strip()
    folder = (request.args.get("folder") or "").strip()
    sql = "SELECT t.*, f.name AS folder_name, f.color AS folder_color FROM todos t " \
          "LEFT JOIN folders f ON f.id=t.folder_id WHERE t.owner_id = ?"
    params: list = [_me()["id"]]
    if status in VALID_STATUS:
        sql += " AND t.status=?"
        params.append(status)
    if q:
        sql += " AND (t.title LIKE ? OR t.detail LIKE ? OR t.tags LIKE ?)"
        like = f"%{q}%"
        params += [like, like, like]
    if tag:
        sql += " AND (',' || t.tags || ',') LIKE ?"
        params.append(f"%,{tag},%")
    if folder:
        if folder in ("none", "0"):
            sql += " AND t.folder_id IS NULL"
        else:
            try:
                fid = int(folder)
            except ValueError:
                return jsonify([])
            ids = descendant_ids(_me()["id"], "todo", fid)
            if not ids:
                return jsonify([])
            sql += " AND t.folder_id IN (" + ",".join("?" * len(ids)) + ")"
            params += ids
    sql += (
        " ORDER BY t.status ASC, (t.due_at IS NULL AND t.due_date IS NULL) ASC, "
        "COALESCE(t.due_at, 0) ASC, t.due_date ASC, t.priority DESC, t.created_at DESC LIMIT 1000"
    )
    rows = db.rows(sql, tuple(params))
    for r in rows:
        subs = _subtasks_list(r.get("subtasks"))
        r["subtasks"] = subs
        r["subtasks_done"] = sum(1 for x in subs if x.get("done"))
        r["subtasks_total"] = len(subs)
        r["repeat_label"] = REPEAT_LABEL.get(r.get("repeat") or "none", "")
    return jsonify(rows)


@bp.get("/api/todos/tags")
@login_required
def list_todo_tags():
    rows = db.rows("SELECT tags FROM todos WHERE owner_id=? AND tags != ''", (_me()["id"],))
    tags: list[str] = []
    for r in rows:
        for t in str(r["tags"]).split(","):
            t = t.strip()
            if t and t not in tags:
                tags.append(t)
    return jsonify(sorted(tags))


@bp.post("/api/todos")
@login_required
def create_todo():
    data = request.get_json(silent=True) or {}
    try:
        t = _clean(data)
    except ValueError as exc:
        return jsonify({"error": str(exc)}), 400
    now = db.now()
    tid = db.execute(
        "INSERT INTO todos(title,detail,status,priority,due_at,due_date,remind,owner_id,folder_id,tags,subtasks,repeat,"
        "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (t["title"], t["detail"], t["status"], t["priority"], t["due_at"], t["due_date"], t["remind"],
         _me()["id"], t["folder_id"], t["tags"], t["subtasks"], t["repeat"], now, now),
    )
    return jsonify(_join_folder(db.row("SELECT * FROM todos WHERE id=?", (tid,)))), 201


def _add_months(d: date, months: int) -> date:
    month = d.month - 1 + months
    year = d.year + month // 12
    month = month % 12 + 1
    day = min(d.day, calendar.monthrange(year, month)[1])
    return date(year, month, day)


def _next_due(t: dict, tz: ZoneInfo):
    """Return (due_at, due_date) for the next occurrence, or None."""
    repeat = t.get("repeat") or "none"
    if repeat == "none":
        return None

    def advance(d: datetime) -> datetime:
        if repeat == "daily":
            return d + timedelta(days=1)
        if repeat == "weekdays":
            d += timedelta(days=1)
            while d.weekday() >= 5:
                d += timedelta(days=1)
            return d
        if repeat == "weekly":
            return d + timedelta(days=7)
        # monthly
        nxt = _add_months(d.date(), 1)
        return datetime(nxt.year, nxt.month, nxt.day, d.hour, d.minute, tzinfo=d.tzinfo)

    if t.get("due_at"):
        d = datetime.fromtimestamp(int(t["due_at"]), tz)
        nxt = advance(d)
        return int(nxt.timestamp()), None
    if t.get("due_date"):
        d = date.fromisoformat(str(t["due_date"]))
        if repeat == "monthly":
            nd = _add_months(d, 1)
        elif repeat == "weekly":
            nd = d + timedelta(days=7)
        elif repeat == "weekdays":
            nd = d + timedelta(days=1)
            while nd.weekday() >= 5:
                nd += timedelta(days=1)
        else:
            nd = d + timedelta(days=1)
        return None, nd.isoformat()
    return None


def _spawn_next(t: dict):
    """Create the next occurrence of a recurring todo (reset checklist + reminders)."""
    nxt = _next_due(t, get_tz(db.get_setting("digest_tz", "Asia/Shanghai")))
    if not nxt:
        return None
    subs = _subtasks_list(t.get("subtasks"))
    for s in subs:
        s["done"] = 0
    now = db.now()
    tid = db.execute(
        "INSERT INTO todos(title,detail,status,priority,due_at,due_date,remind,owner_id,folder_id,tags,subtasks,repeat,"
        "created_at,updated_at) VALUES(?,?, 'pending',?,?,?,?,?,?,?,?,?,?,?)",
        (t["title"], t.get("detail") or "", t.get("priority") or 1, nxt[0], nxt[1], t.get("remind") or 0,
         t.get("owner_id"), t.get("folder_id"), t.get("tags") or "",
         json.dumps(subs, ensure_ascii=False, separators=(",", ":")), t.get("repeat") or "none", now, now),
    )
    return tid


def _complete(t: dict) -> int | None:
    """Mark done and spawn the next occurrence; returns the new todo id."""
    db.execute(
        "UPDATE todos SET status='done', completed_at=COALESCE(completed_at,?), updated_at=? WHERE id=?",
        (db.now(), db.now(), t["id"]),
    )
    return _spawn_next(t)


@bp.put("/api/todos/<int:tid>")
@login_required
def update_todo(tid):
    base = _mine(tid)
    if not base:
        return jsonify({"error": "事项不存在"}), 404
    data = request.get_json(silent=True) or {}
    try:
        t = _clean(data, base)
    except ValueError as exc:
        return jsonify({"error": str(exc)}), 400
    just_done = base["status"] == "pending" and t["status"] == "done"
    reset_remind = (t["due_at"] != base["due_at"]) or (t["due_date"] != base["due_date"]) or just_done
    db.execute(
        "UPDATE todos SET title=?,detail=?,status=?,priority=?,due_at=?,due_date=?,remind=?,folder_id=?,tags=?,"
        "subtasks=?,repeat=?,"
        "reminded=CASE WHEN ? THEN 0 ELSE reminded END,"
        "attempts=CASE WHEN ? THEN 0 ELSE attempts END,"
        "sent_pre1d=CASE WHEN ? THEN 0 ELSE sent_pre1d END,"
        "sent_pre10m=CASE WHEN ? THEN 0 ELSE sent_pre10m END,"
        "tries_pre1d=CASE WHEN ? THEN 0 ELSE tries_pre1d END,"
        "tries_pre10m=CASE WHEN ? THEN 0 ELSE tries_pre10m END,"
        "completed_at=CASE WHEN ?='done' THEN COALESCE(completed_at,?) ELSE NULL END, updated_at=? WHERE id=?",
        (
            t["title"], t["detail"], t["status"], t["priority"], t["due_at"], t["due_date"], t["remind"],
            t["folder_id"], t["tags"], t["subtasks"], t["repeat"],
            1 if reset_remind else 0,
            1 if reset_remind else 0,
            1 if reset_remind else 0,
            1 if reset_remind else 0,
            1 if reset_remind else 0,
            1 if reset_remind else 0,
            t["status"], db.now(), db.now(), tid,
        ),
    )
    next_id = _spawn_next({**base, **t, "id": tid}) if just_done and t["repeat"] != "none" else None
    row = _join_folder(db.row("SELECT * FROM todos WHERE id=?", (tid,)))
    if next_id:
        row["next_id"] = next_id
    return jsonify(row)


@bp.post("/api/todos/<int:tid>/toggle")
@login_required
def toggle_todo(tid):
    t = _mine(tid)
    if not t:
        return jsonify({"error": "事项不存在"}), 404
    next_id = None
    if t["status"] == "pending":
        next_id = _complete(t)
    else:
        db.execute(
            "UPDATE todos SET status='pending', completed_at=NULL, updated_at=? WHERE id=?",
            (db.now(), tid),
        )
    row = _join_folder(db.row("SELECT * FROM todos WHERE id=?", (tid,)))
    row["next_id"] = next_id
    return jsonify(row)


@bp.delete("/api/todos/<int:tid>")
@login_required
def delete_todo(tid):
    if not _mine(tid):
        return jsonify({"error": "事项不存在"}), 404
    db.execute("DELETE FROM todos WHERE id=?", (tid,))
    return jsonify({"ok": True})


@bp.post("/api/todos/clear-done")
@login_required
def clear_done():
    me = _me()["id"]
    n = db.scalar("SELECT COUNT(*) FROM todos WHERE status='done' AND owner_id=?", (me,)) or 0
    db.execute("DELETE FROM todos WHERE status='done' AND owner_id=?", (me,))
    return jsonify({"deleted": n})


@bp.post("/api/todos/<int:tid>/notify")
@login_required
def notify_todo(tid):
    """Send the full-detail reminder mail on demand."""
    t = _mine(tid)
    if not t:
        return jsonify({"error": "事项不存在"}), 404
    if t["status"] == "done":
        return jsonify({"error": "已完成的事项无需提醒"}), 400
    if rate_hit(f"todo-mail:{_me()['id']}", 6, 600):
        return jsonify({"error": "发送过于频繁，请稍后再试"}), 429
    cfg = db.all_settings()
    if not mailer.smtp_ready(cfg):
        return jsonify({"error": "站点尚未配置 SMTP 邮件服务"}), 400
    tz = get_tz(cfg.get("digest_tz") or "Asia/Shanghai")
    subject, body = scheduler.build_reminder(t, "manual", db.now(), cfg, tz)
    owner = db.row("SELECT email FROM users WHERE id=?", (t.get("owner_id"),))
    email = (owner or {}).get("email") or ""
    # accounts may use a non-mailbox login name (e.g. "admin"): fall back to
    # the globally configured recipient, same rule as scheduled reminders
    to = email if "@" in email else None
    ok, err = mailer.send(subject, body, kind="todo_manual", to_override=to,
                          attachments=scheduler.ics_attachment(t, tz))
    if not ok:
        return jsonify({"error": err or "邮件发送失败"}), 502
    return jsonify({"ok": True})


def _quick_page(title: str, message: str, ok: bool, base_url: str) -> str:
    color = "#16a34a" if ok else "#dc2626"
    return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>NoteBox</title></head>
<body style="margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0f172a;font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif">
<div style="background:#111c31;border:1px solid #24344f;border-radius:16px;padding:36px 42px;max-width:420px;text-align:center;color:#e5edf9">
<div style="width:56px;height:56px;border-radius:50%;background:{color}22;border:1px solid {color}66;
display:flex;align-items:center;justify-content:center;margin:0 auto 16px;font-size:28px;color:{color}">{'✓' if ok else '!'}</div>
<h1 style="font-size:18px;margin:0 0 10px">{html.escape(title)}</h1>
<p style="font-size:13.5px;color:#9fb0c9;line-height:1.6;margin:0 0 20px">{html.escape(message)}</p>
<a href="{html.escape(base_url)}" style="display:inline-block;background:#4f46e5;color:#fff;text-decoration:none;
padding:10px 20px;border-radius:9px;font-size:13.5px">打开 NoteBox</a></div></body></html>"""


@bp.get("/api/todos/<int:tid>/quick-complete/<token>")
def quick_complete(tid, token):
    """One-click completion link embedded in reminder mails (signed, no login)."""
    base = db.get_setting("base_url", "") or ""
    t = db.row("SELECT * FROM todos WHERE id=?", (tid,))
    if not t or not verify_quick_token(token, t):
        return _quick_page("链接无效", "这个完成链接无效或已过期，请打开 NoteBox 操作。", False, base), 403
    if t["status"] == "done":
        return _quick_page("已经完成", f"「{t['title']}」早已完成，无需重复操作。", True, base)
    _complete(t)
    return _quick_page("已完成", f"「{t['title']}」已标记完成，下次到期会继续提醒。", True, base)
