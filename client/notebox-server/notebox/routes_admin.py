"""Auth endpoints, settings, stats, import/export, email log."""
import csv
import io
import json
import re
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from flask import Blueprint, Response, g, jsonify, request, send_from_directory

from . import db, mailer
from .auth import admin_required, login_required, login_user, validate_password
from .htmlmd import html_to_markdown

bp = Blueprint("admin", __name__)

SETTING_KEYS = [
    "smtp_host", "smtp_port", "smtp_ssl", "smtp_user", "smtp_pass", "mail_from", "mail_to",
    "notify_enabled", "remind_before_min", "digest_enabled", "digest_time", "digest_tz",
    "base_url",
]


def _tz():
    from .timeutil import get_tz

    return get_tz(db.get_setting("digest_tz", "Asia/Shanghai") or "Asia/Shanghai")


# ---------------------------------------------------------------- auth

@bp.post("/api/login")
def login():
    data = request.get_json(silent=True) or {}
    ident = str(data.get("email") or data.get("username") or "").strip()
    password = str(data.get("password") or "")
    user, err, code = login_user(ident, password)
    if err:
        return jsonify({"error": err}), code
    return jsonify({"ok": True, "user": user["email"], "role": user["role"]})


@bp.post("/api/logout")
def logout():
    from flask import session

    session.clear()
    return jsonify({"ok": True})


@bp.get("/api/bootstrap")
@login_required
def bootstrap():
    return jsonify({
        "user": g.user["email"],
        "role": g.user["role"],
        "stats": _stats(g.user),
        "settings": _settings_for(g.user),
    })


def _stats(user: dict | None = None) -> dict:
    now = db.now()
    tz = _tz()
    today = datetime.fromtimestamp(now, tz).date()
    overdue = today_items = 0
    # admins manage everything -> stats cover the whole instance
    mine = user["id"] if (user and user.get("role") != "admin") else None
    todo_where = "WHERE owner_id = ?" if mine is not None else ""
    todo_params = (mine,) if mine is not None else ()
    for t in db.rows(f"SELECT status,due_at,due_date FROM todos {todo_where}", todo_params):
        if t["due_at"] and t["due_at"] < now:
            overdue += 1
        elif t["due_date"] and t["due_date"] < today.isoformat():
            overdue += 1
        elif t["due_at"] and datetime.fromtimestamp(t["due_at"], tz).date() == today:
            today_items += 1
        elif t["due_date"] == today.isoformat():
            today_items += 1
    if mine is not None:
        notes_count = db.scalar(
            "SELECT COUNT(*) FROM notes n WHERE (n.owner_id=? OR EXISTS("
            "SELECT 1 FROM shares s WHERE s.note_id=n.id AND s.grantee_id=?)) AND n.deleted_at IS NULL",
            (mine, mine),
        ) or 0
        pending = db.scalar("SELECT COUNT(*) FROM todos WHERE status='pending' AND owner_id=?", (mine,)) or 0
        done = db.scalar("SELECT COUNT(*) FROM todos WHERE status='done' AND owner_id=?", (mine,)) or 0
    else:
        notes_count = db.scalar("SELECT COUNT(*) FROM notes WHERE deleted_at IS NULL") or 0
        pending = db.scalar("SELECT COUNT(*) FROM todos WHERE status='pending'") or 0
        done = db.scalar("SELECT COUNT(*) FROM todos WHERE status='done'") or 0
    return {
        "notes": notes_count,
        "pending": pending,
        "done": done,
        "overdue": overdue,
        "today": today_items,
        "mail_ok_today": db.scalar(
            "SELECT COUNT(*) FROM email_log WHERE status='ok' AND sent_at>=?",
            (int(datetime(today.year, today.month, today.day, 0, 0, tzinfo=tz).timestamp()),),
        ) or 0,
        "db_bytes": db.DB_PATH.stat().st_size if db.DB_PATH.exists() else 0,
        "server_time": datetime.now(tz).strftime("%Y-%m-%d %H:%M:%S %Z"),
    }


def _public_settings() -> dict:
    s = db.all_settings()
    return {
        "username": s.get("username", ""),
        "smtp_host": s.get("smtp_host", ""),
        "smtp_port": s.get("smtp_port", "465"),
        "smtp_ssl": s.get("smtp_ssl", "1"),
        "smtp_user": s.get("smtp_user", ""),
        "smtp_pass": s.get("smtp_pass", ""),
        "mail_from": s.get("mail_from", ""),
        "mail_to": s.get("mail_to", ""),
        "notify_enabled": s.get("notify_enabled", "1"),
        "remind_before_min": s.get("remind_before_min", "30"),
        "digest_enabled": s.get("digest_enabled", "1"),
        "digest_time": s.get("digest_time", "08:30"),
        "digest_tz": s.get("digest_tz", "Asia/Shanghai"),
        "base_url": s.get("base_url", ""),
        "smtp_ready": mailer.smtp_ready(s),
        "notify_enabled_bool": s.get("notify_enabled") == "1",
        "digest_enabled_bool": s.get("digest_enabled") == "1",
    }


def _settings_for(user: dict) -> dict:
    """Admins get the full settings (incl. SMTP secret); users get a safe subset."""
    if user and user.get("role") == "admin":
        return _public_settings()
    return {
        "username": user.get("email", ""),
        "smtp_ready": mailer.smtp_ready(),
    }


# ---------------------------------------------------------------- settings

@bp.get("/api/settings")
@login_required
def get_settings():
    return jsonify(_settings_for(g.user))


@bp.put("/api/settings")
@admin_required
def put_settings():
    data = request.get_json(silent=True) or {}
    for key in SETTING_KEYS:
        if key in data:
            value = data[key]
            if isinstance(value, bool):
                value = "1" if value else "0"
            db.set_setting(key, str(value))
    # Port sanity
    try:
        db.set_setting("smtp_port", str(max(1, min(65535, int(db.get_setting("smtp_port", "465"))))))
    except ValueError:
        db.set_setting("smtp_port", "465")
    try:
        db.set_setting("remind_before_min", str(max(0, min(7 * 24 * 60, int(db.get_setting("remind_before_min", "30"))))))
    except ValueError:
        db.set_setting("remind_before_min", "30")
    if "username" in data and not str(data["username"]).strip():
        pass  # legacy field ignored (accounts live in the users table now)
    return jsonify(_public_settings())


@bp.post("/api/settings/password")
@login_required
def change_password():
    data = request.get_json(silent=True) or {}
    old = str(data.get("old") or "")
    new = str(data.get("new") or "")
    if not db.verify_password(old, g.user["password"]):
        return jsonify({"error": "原密码不正确"}), 400
    pw_err = validate_password(new)
    if pw_err:
        return jsonify({"error": pw_err}), 400
    db.execute("UPDATE users SET password=? WHERE id=?", (db.hash_password(new), g.user["id"]))
    return jsonify({"ok": True})


@bp.post("/api/settings/test-email")
@admin_required
def test_email():
    ok, err = mailer.send(
        "[NoteBox] 测试邮件：SMTP 配置成功",
        mailer.render_mail(
            "SMTP 测试成功",
            ["这是一封来自 NoteBox 的测试邮件。", f"发送时间：{datetime.now(_tz()).strftime('%Y-%m-%d %H:%M:%S')}"],
            button=True,
            icon="check",
        ),
        kind="test",
    )
    if ok:
        return jsonify({"ok": True})
    return jsonify({"ok": False, "error": err}), 502


@bp.get("/api/emails")
@admin_required
def email_log():
    return jsonify(db.rows("SELECT * FROM email_log ORDER BY id DESC LIMIT 50"))


@bp.get("/api/stats")
@login_required
def stats():
    return jsonify(_stats(g.user))


# ---------------------------------------------------------------- import helpers

_REL = {"今天": 0, "today": 0, "明天": 1, "tomorrow": 1, "后天": 2, "day after tomorrow": 2}


def _parse_due(raw: str):
    """Return (due_at: int|None, due_date: str|None)."""
    s = (raw or "").strip()
    if not s:
        return None, None
    tz = _tz()
    low = s.lower()
    if low in _REL:
        d = datetime.now(tz).date() + timedelta(days=_REL[low])
        return None, d.isoformat()
    for fmt in ("%Y-%m-%d %H:%M", "%Y/%m/%d %H:%M", "%Y-%m-%d %H:%M:%S", "%Y/%m/%d %H:%M:%S"):
        try:
            return int(datetime.strptime(s, fmt).replace(tzinfo=tz).timestamp()), None
        except ValueError:
            pass
    for fmt in ("%Y-%m-%d", "%Y/%m/%d"):
        try:
            return None, datetime.strptime(s, fmt).date().isoformat()
        except ValueError:
            pass
    m = re.match(r"^(\d{1,2})[-/](\d{1,2})$", s)  # 10-21 style (this year)
    if m:
        try:
            today = datetime.now(tz).date()
            d = datetime(today.year, int(m.group(1)), int(m.group(2))).date()
            if d < today - timedelta(days=180):
                d = datetime(today.year + 1, int(m.group(1)), int(m.group(2))).date()
            return None, d.isoformat()
        except ValueError:
            pass
    return None, None


def _parse_priority(raw) -> int:
    s = str(raw or "").strip().lower()
    if s in ("3", "高", "high", "urgent", "紧急", "p1"):
        return 3
    if s in ("2", "中", "medium", "normal", "中等", "p2"):
        return 2
    if s in ("1", "低", "low", "minor", "p3"):
        return 1
    if s in ("0", "无", "none", "p4"):
        return 0
    return 1


_STATUS_DONE = {"done", "1", "true", "是", "完成", "已完成", "x", "yes"}


@bp.post("/api/import/notes")
@login_required
def import_notes():
    data = request.get_json(silent=True) or {}
    files = data.get("files") or []
    split_headings = bool(data.get("split", True))
    if not files:
        return jsonify({"error": "没有文件"}), 400
    now = db.now()
    created = 0
    for f in files:
        name = str(f.get("name") or "note.md")
        content = str(f.get("content") or "").replace("\r\n", "\n")
        if Path(name).suffix.lower() in (".html", ".htm"):
            # HTML notes become Markdown so they stay editable & renderable
            content = html_to_markdown(content)
        if not content.strip():
            continue
        sections = []
        if split_headings and re.search(r"^# [^\n]+\n", content, re.M) and len(re.findall(r"^# ", content, re.M)) > 1:
            parts = re.split(r"(?=^# )", content, flags=re.M)
            for p in parts:
                if not p.strip():
                    continue
                first, _, rest = p.partition("\n")
                sections.append((first.lstrip("# ").strip()[:200] or "未命名笔记", rest.strip()))
        else:
            sections.append((Path(name).stem[:200] or "未命名笔记", content.strip()))
        for title, body in sections:
            db.execute(
                "INSERT INTO notes(title,content,tags,pinned,owner_id,created_at,updated_at) VALUES(?,?,?,0,?,?,?)",
                (title, body, "", g.user["id"], now, now),
            )
            created += 1
    return jsonify({"imported": created})


@bp.post("/api/import/todos")
@login_required
def import_todos():
    data = request.get_json(silent=True) or {}
    content = str(data.get("content") or "")
    name = str(data.get("name") or "import.txt")
    fmt = str(data.get("format") or "auto").lower()
    if fmt == "auto":
        ext = Path(name).suffix.lower()
        fmt = {".csv": "csv", ".json": "json"}.get(ext, "txt")
    if not content.strip():
        return jsonify({"error": "文件内容为空"}), 400
    try:
        if fmt == "json":
            items = _todos_from_json(content)
        elif fmt == "csv":
            items = _todos_from_csv(content)
        else:
            items = _todos_from_txt(content)
    except Exception as exc:  # noqa: BLE001
        return jsonify({"error": f"解析失败：{exc}"}), 400
    now = db.now()
    created = 0
    for it in items:
        title = str(it.get("title") or "").strip()[:500]
        if not title:
            continue
        due_at, due_date = it.get("due_at"), it.get("due_date")
        if not due_at and not due_date:
            due_at, due_date = _parse_due(it.get("due_raw") or "")
        status = "done" if it.get("done") else "pending"
        db.execute(
            "INSERT INTO todos(title,detail,status,priority,due_at,due_date,remind,owner_id,completed_at,created_at,updated_at) "
            "VALUES(?,?,?,?,?,?,1,?,?,?,?)",
            (
                title,
                str(it.get("detail") or "")[:5000],
                status,
                int(it.get("priority", 1)),
                due_at, due_date,
                g.user["id"],
                now if status == "done" else None,
                now, now,
            ),
        )
        created += 1
    return jsonify({"imported": created})


def _todos_from_json(content: str) -> list[dict]:
    data = json.loads(content)
    if isinstance(data, dict):
        data = data.get("todos") or data.get("items") or []
    if not isinstance(data, list):
        raise ValueError("JSON 应为数组")
    out = []
    for it in data:
        if isinstance(it, str):
            out.append({"title": it})
            continue
        if not isinstance(it, dict):
            continue
        title = it.get("title") or it.get("name") or it.get("任务") or it.get("标题") or ""
        out.append({
            "title": title,
            "detail": it.get("detail") or it.get("description") or it.get("备注") or "",
            "priority": _parse_priority(it.get("priority") or it.get("优先级")),
            "due_raw": it.get("due") or it.get("due_date") or it.get("deadline") or it.get("截止") or "",
            "done": str(it.get("status") or it.get("完成") or "") in _STATUS_DONE or bool(it.get("completed")),
        })
    return out


def _todos_from_csv(content: str) -> list[dict]:
    reader = csv.reader(io.StringIO(content.lstrip("\ufeff")))
    rows = [r for r in reader if any(c.strip() for c in r)]
    if not rows:
        return []
    header_cells = {c.strip().lower() for c in rows[0]}
    known = {"title", "标题", "任务", "task", "name", "事项", "detail", "备注", "描述", "description",
             "due", "截止", "日期", "deadline", "priority", "优先级", "status", "状态"}
    has_header = bool(header_cells & known)
    out = []
    if has_header:
        header = [c.strip().lower() for c in rows[0]]
        body = rows[1:]

        def col(names):
            for n in names:
                if n in header:
                    return header.index(n)
            return None

        i_title = col(["title", "标题", "任务", "task", "name", "事项"])
        i_detail = col(["detail", "备注", "描述", "description", "note"])
        i_due = col(["due", "截止", "日期", "deadline", "due_date"])
        i_pri = col(["priority", "优先级"])
        i_status = col(["status", "状态"])
        for r in body:
            def cell(idx):
                return r[idx].strip() if idx is not None and idx < len(r) else ""
            out.append({
                "title": cell(i_title) if i_title is not None else (r[0].strip() if r else ""),
                "detail": cell(i_detail),
                "due_raw": cell(i_due),
                "priority": _parse_priority(cell(i_pri)) if cell(i_pri) else 1,
                "done": cell(i_status).lower() in _STATUS_DONE,
            })
    else:
        for r in rows:
            if not r or not r[0].strip():
                continue
            item = {"title": r[0].strip(), "detail": "", "due_raw": "", "priority": 1, "done": False}
            if len(r) > 1:
                due_at, due_date = _parse_due(r[1])
                if due_at or due_date:
                    item["due_raw"] = r[1].strip()
                else:
                    item["detail"] = r[1].strip()
            if len(r) > 2:
                item["priority"] = _parse_priority(r[2])
            out.append(item)
    return out


_CHECKBOX = re.compile(r"^\s*(?:[-*+]|\d+[.)])\s*\[([ xX])\]\s+(.*)$")
_BULLET = re.compile(r"^\s*(?:[-*+]|\d+[.)])\s+(.*)$")


def _todos_from_txt(content: str) -> list[dict]:
    out = []
    for line in content.replace("\r\n", "\n").split("\n"):
        line = line.strip()
        if not line:
            continue
        m = _CHECKBOX.match(line)
        if m:
            out.append({"title": m.group(2).strip(), "done": m.group(1).lower() == "x", "detail": "", "priority": 1, "due_raw": ""})
            continue
        m = _BULLET.match(line)
        if m:
            out.append({"title": m.group(1).strip(), "done": False, "detail": "", "priority": 1, "due_raw": ""})
            continue
        out.append({"title": line, "done": False, "detail": "", "priority": 1, "due_raw": ""})
    return out


@bp.post("/api/import/backup")
@login_required
def import_backup():
    data = request.get_json(silent=True) or {}
    mode = "replace" if data.get("mode") == "replace" else "merge"
    try:
        payload = json.loads(str(data.get("content") or ""))
    except Exception:
        return jsonify({"error": "JSON 解析失败"}), 400
    if not isinstance(payload, dict):
        return jsonify({"error": "备份格式不正确"}), 400
    notes = payload.get("notes") or []
    todos = payload.get("todos") or []
    if mode == "replace":
        db.execute("DELETE FROM notes WHERE owner_id=?", (g.user["id"],))
        db.execute("DELETE FROM todos WHERE owner_id=?", (g.user["id"],))
    now = db.now()
    n_count = t_count = 0
    for n in notes:
        if not isinstance(n, dict):
            continue
        db.execute(
            "INSERT INTO notes(title,content,tags,pinned,owner_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
            (
                str(n.get("title") or "未命名笔记")[:200],
                str(n.get("content") or ""),
                str(n.get("tags") or "")[:200],
                1 if n.get("pinned") else 0,
                g.user["id"],
                int(n.get("created_at") or now),
                int(n.get("updated_at") or now),
            ),
        )
        n_count += 1
    for t in todos:
        if not isinstance(t, dict) or not str(t.get("title") or "").strip():
            continue
        status = "done" if t.get("status") == "done" else "pending"
        db.execute(
            "INSERT INTO todos(title,detail,status,priority,due_at,due_date,remind,owner_id,completed_at,created_at,updated_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            (
                str(t.get("title"))[:500],
                str(t.get("detail") or "")[:5000],
                status,
                int(t.get("priority") or 1),
                int(t["due_at"]) if t.get("due_at") else None,
                str(t.get("due_date") or "") or None,
                1 if t.get("remind", 1) else 0,
                g.user["id"],
                int(t["completed_at"]) if t.get("completed_at") else (now if status == "done" else None),
                int(t.get("created_at") or now),
                int(t.get("updated_at") or now),
            ),
        )
        t_count += 1
    return jsonify({"notes": n_count, "todos": t_count, "mode": mode})


# ---------------------------------------------------------------- export

def _attachment(filename: str, text: str, mime: str) -> Response:
    resp = Response(text, mimetype=mime)
    resp.headers["Content-Disposition"] = f"attachment; filename={filename}"
    return resp


@bp.get("/api/export")
@login_required
def export_backup():
    me = g.user["id"]
    if g.user["role"] == "admin":
        notes = db.rows("SELECT * FROM notes ORDER BY id")
        todos = db.rows("SELECT * FROM todos ORDER BY id")
    else:
        notes = db.rows(
            "SELECT n.* FROM notes n WHERE n.owner_id=? OR EXISTS("
            "SELECT 1 FROM shares s WHERE s.note_id=n.id AND s.grantee_id=?) ORDER BY n.id",
            (me, me),
        )
        todos = db.rows("SELECT * FROM todos WHERE owner_id=? ORDER BY id", (me,))
    payload = {
        "app": "notebox",
        "version": 1,
        "exported_at": db.now(),
        "exported_at_iso": datetime.now(_tz()).isoformat(),
        "notes": notes,
        "todos": todos,
    }
    return _attachment(
        f"notebox-backup-{datetime.now(_tz()).strftime('%Y%m%d-%H%M%S')}.json",
        json.dumps(payload, ensure_ascii=False, indent=2),
        "application/json; charset=utf-8",
    )


@bp.get("/api/export/markdown")
@login_required
def export_markdown():
    me = g.user["id"]
    if g.user["role"] == "admin":
        notes = db.rows("SELECT * FROM notes ORDER BY pinned DESC, updated_at DESC")
    else:
        notes = db.rows(
            "SELECT n.* FROM notes n WHERE n.owner_id=? OR EXISTS("
            "SELECT 1 FROM shares s WHERE s.note_id=n.id AND s.grantee_id=?) "
            "ORDER BY n.pinned DESC, n.updated_at DESC",
            (me, me),
        )
    parts = ["# NoteBox 笔记导出\n"]
    for n in notes:
        parts.append(f"# {n['title']}\n\n{n['content']}\n\n---\n")
    return _attachment(
        f"notebox-notes-{datetime.now(_tz()).strftime('%Y%m%d')}.md",
        "\n".join(parts),
        "text/markdown; charset=utf-8",
    )


@bp.get("/api/export/csv")
@login_required
def export_csv():
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["title", "status", "priority", "due_date", "due_at_iso", "detail", "created_at_iso"])
    tz = _tz()
    if g.user["role"] == "admin":
        todo_rows = db.rows("SELECT * FROM todos ORDER BY status, id")
    else:
        todo_rows = db.rows("SELECT * FROM todos WHERE owner_id=? ORDER BY status, id", (g.user["id"],))
    for t in todo_rows:
        due_iso = datetime.fromtimestamp(t["due_at"], tz).strftime("%Y-%m-%d %H:%M") if t["due_at"] else ""
        w.writerow([t["title"], t["status"], t["priority"], t["due_date"] or "", due_iso, t["detail"],
                    datetime.fromtimestamp(t["created_at"], tz).strftime("%Y-%m-%d %H:%M")])
    return _attachment(
        f"notebox-todos-{datetime.now(tz).strftime('%Y%m%d')}.csv",
        "\ufeff" + buf.getvalue(),
        "text/csv; charset=utf-8",
    )


@bp.get("/")
def index():
    return send_from_directory(str(Path(__file__).resolve().parent / "static"), "index.html")
