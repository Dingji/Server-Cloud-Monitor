"""Share links (public), per-user share grants (read/edit/admin), verification
page, and uploaded asset serving."""
import html
import re
import secrets
import threading
import time
from pathlib import Path

from flask import Blueprint, g, jsonify, request, send_from_directory

from . import db, mailer
from .auth import PERMS, login_required, note_can, note_permission

bp = Blueprint("public", __name__)

STATIC_DIR = Path(__file__).resolve().parent / "static"
UPLOAD_DIR = db.DATA_DIR / "uploads"

IMAGE_EXT = {"png", "jpg", "jpeg", "gif", "webp", "svg", "avif", "bmp", "ico", "apng"}
VIDEO_EXT = {"mp4", "webm", "ogv", "mov", "m4v", "mkv", "avi"}
AUDIO_EXT = {"mp3", "wav", "ogg", "flac", "m4a", "aac", "opus"}
ASSET_EXT = IMAGE_EXT | VIDEO_EXT | AUDIO_EXT
MAX_ASSET = 200 * 1024 * 1024  # 200 MB

_SHARE_CODE_RE = re.compile(r"^[\w-]{6,40}$")

# simple fixed-window rate limiter for unauthenticated reads
_rate_lock = threading.Lock()
_rate_hits: dict[str, list] = {}


def _rate(bucket: str, limit: int = 120, window: int = 60) -> bool:
    ip = request.headers.get("X-Forwarded-For", request.remote_addr or "?").split(",")[0].strip()
    key = f"{bucket}:{ip}"
    now = time.time()
    with _rate_lock:
        hits = [h for h in _rate_hits.get(key, []) if now - h < window]
        if len(hits) >= limit:
            _rate_hits[key] = hits
            return False
        hits.append(now)
        _rate_hits[key] = hits
        return True


# --------------------------------------------------------------- share mgmt

def _base() -> str:
    return (db.get_setting("base_url") or "http://8.153.153.176:13345").rstrip("/")


def _share_rows(note_id: int) -> list[dict]:
    return [
        {
            "id": r["id"],
            "email": r["email"],
            "permission": r["permission"],
            "created_at": r["created_at"],
        }
        for r in db.rows(
            "SELECT s.id, s.permission, s.created_at, s.grantee_id, u.email "
            "FROM shares s JOIN users u ON u.id = s.grantee_id "
            "WHERE s.note_id=? ORDER BY s.id",
            (note_id,),
        )
    ]


PERM_TEXT = {"read": "只读", "edit": "可编辑", "admin": "管理"}


def _notify_share(grantee: dict, note: dict, permission: str, by_user: dict) -> None:
    if not mailer.smtp_ready() or "@" not in (grantee["email"] or ""):
        return
    if grantee["id"] == by_user["id"]:
        return
    title = html.escape(str(note.get("title") or "未命名笔记")[:80])
    perm_text = PERM_TEXT.get(permission, permission)
    body = mailer.render_mail(
        f"《{title}》已共享给你（{perm_text}）",
        [
            f"<b>{html.escape(by_user['email'])}</b> 把一篇笔记共享给了你。",
            f"权限：<b>{perm_text}</b>（只读 = 仅查看；可编辑 = 可修改内容；管理 = 可修改、删除并管理共享）。",
            "点击下方按钮打开 NoteBox 查看。",
        ],
        button=True,
        base_url=_base(),
        icon="check",
        button_text="打开 NoteBox",
    )
    mailer.send(
        f"[NoteBox] {by_user['email']} 共享了笔记《{title}》给你",
        body,
        kind="share",
        to_override=grantee["email"],
    )


def _load_for_share(nid: int):
    note = db.row("SELECT * FROM notes WHERE id=?", (nid,))
    if not note:
        return None, (jsonify({"error": "笔记不存在"}), 404)
    if not note_permission(note, g.user):
        return None, (jsonify({"error": "无权访问该笔记"}), 403)
    if not note_can(note, g.user, "admin"):
        return None, (jsonify({"error": "只有所有者或管理级共享者可以管理共享"}), 403)
    return note, None


@bp.get("/api/notes/<int:nid>/shares")
@login_required
def list_note_shares(nid):
    note = db.row("SELECT * FROM notes WHERE id=?", (nid,))
    if not note:
        return jsonify({"error": "笔记不存在"}), 404
    if not note_permission(note, g.user):
        return jsonify({"error": "无权访问该笔记"}), 403
    return jsonify(_share_rows(nid))


@bp.post("/api/notes/<int:nid>/shares")
@login_required
def add_note_share(nid):
    note, err = _load_for_share(nid)
    if err:
        return err
    data = request.get_json(silent=True) or {}
    email = str(data.get("email") or "").strip().lower()
    permission = str(data.get("permission") or "read")
    if permission not in PERMS:
        return jsonify({"error": "权限必须是 read / edit / admin"}), 400
    if not email:
        return jsonify({"error": "请填写对方邮箱"}), 400
    grantee = db.row("SELECT * FROM users WHERE email=?", (email,))
    if not grantee or not grantee["active"]:
        return jsonify({"error": "该用户不存在或尚未完成邮箱验证"}), 400
    if grantee["id"] == note.get("owner_id"):
        return jsonify({"error": "对方已经是该笔记的所有者"}), 400
    if grantee["id"] == g.user["id"]:
        return jsonify({"error": "不能共享给自己"}), 400
    existing = db.row("SELECT id FROM shares WHERE note_id=? AND grantee_id=?", (nid, grantee["id"]))
    if existing:
        db.execute("UPDATE shares SET permission=? WHERE id=?", (permission, existing["id"]))
    else:
        db.execute(
            "INSERT INTO shares(note_id,owner_id,grantee_id,permission,created_at) VALUES(?,?,?,?,?)",
            (nid, g.user["id"], grantee["id"], permission, db.now()),
        )
    _notify_share(grantee, note, permission, g.user)
    return jsonify({"shares": _share_rows(nid)}), 201


@bp.put("/api/notes/<int:nid>/shares/<int:sid>")
@login_required
def update_note_share(nid, sid):
    note, err = _load_for_share(nid)
    if err:
        return err
    row = db.row("SELECT * FROM shares WHERE id=? AND note_id=?", (sid, nid))
    if not row:
        return jsonify({"error": "共享记录不存在"}), 404
    data = request.get_json(silent=True) or {}
    permission = str(data.get("permission") or "")
    if permission not in PERMS:
        return jsonify({"error": "权限必须是 read / edit / admin"}), 400
    db.execute("UPDATE shares SET permission=? WHERE id=?", (permission, sid))
    return jsonify({"shares": _share_rows(nid)})


@bp.delete("/api/notes/<int:nid>/shares/<int:sid>")
@login_required
def delete_note_share(nid, sid):
    note, err = _load_for_share(nid)
    if err:
        return err
    row = db.row("SELECT * FROM shares WHERE id=? AND note_id=?", (sid, nid))
    if not row:
        return jsonify({"error": "共享记录不存在"}), 404
    db.execute("DELETE FROM shares WHERE id=?", (sid,))
    return jsonify({"shares": _share_rows(nid)})


@bp.post("/api/notes/<int:nid>/share")
@login_required
def share_note(nid):
    n = db.row("SELECT id, share_code FROM notes WHERE id=?", (nid,))
    if not n:
        return jsonify({"error": "笔记不存在"}), 404
    if not note_can(db.row("SELECT * FROM notes WHERE id=?", (nid,)), g.user, "admin"):
        return jsonify({"error": "只有所有者或管理级共享者可以开启公开链接"}), 403
    code = n.get("share_code")
    if not code:
        for _ in range(8):
            code = secrets.token_urlsafe(9)  # 12 URL-safe chars
            if not db.row("SELECT 1 FROM notes WHERE share_code=?", (code,)):
                break
        db.execute("UPDATE notes SET share_code=? WHERE id=?", (code, nid))
    return jsonify({"share_code": code, "share_url": "/s/" + code})


@bp.post("/api/notes/<int:nid>/unshare")
@login_required
def unshare_note(nid):
    note = db.row("SELECT * FROM notes WHERE id=?", (nid,))
    if not note:
        return jsonify({"error": "笔记不存在"}), 404
    if not note_can(note, g.user, "admin"):
        return jsonify({"error": "只有所有者或管理级共享者可以关闭公开链接"}), 403
    db.execute("UPDATE notes SET share_code=NULL WHERE id=?", (nid,))
    return jsonify({"ok": True})


# --------------------------------------------------------------- public read

@bp.get("/s/<code>")
def share_page(code):
    return send_from_directory(str(STATIC_DIR), "share.html")


@bp.get("/api/share/<code>")
def share_api(code):
    if not _rate("share"):
        return jsonify({"error": "请求过于频繁，请稍后再试"}), 429
    if not _SHARE_CODE_RE.match(code):
        return jsonify({"error": "分享链接无效或已失效"}), 404
    n = db.row(
        "SELECT title, content, tags, updated_at FROM notes WHERE share_code=?",
        (code,),
    )
    if not n:
        return jsonify({"error": "分享链接无效或已失效"}), 404
    n["shared_at"] = n["updated_at"]
    return jsonify(n)


# --------------------------------------------------------------- uploads

@bp.post("/api/assets")
@login_required
def upload_asset():
    f = request.files.get("file")
    if f is None or not f.filename:
        return jsonify({"error": "没有文件"}), 400
    ext = Path(f.filename).suffix.lower().lstrip(".")
    if ext not in ASSET_EXT:
        return jsonify({"error": f"不支持的文件类型 .{ext}（图片/视频/音频）"}), 400
    f.stream.seek(0, 2)
    size = f.stream.tell()
    f.stream.seek(0)
    if size > MAX_ASSET:
        return jsonify({"error": f"文件太大（上限 {MAX_ASSET // 1024 // 1024}MB）"}), 413
    if size == 0:
        return jsonify({"error": "空文件"}), 400
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    name = secrets.token_hex(12) + "." + ext
    f.save(str(UPLOAD_DIR / name))
    kind = "image" if ext in IMAGE_EXT else ("video" if ext in VIDEO_EXT else "audio")
    return jsonify({"url": "/uploads/" + name, "name": name, "size": size, "kind": kind})


@bp.get("/uploads/<name>")
def serve_upload(name):
    if not re.match(r"^[\w.-]+$", name) or ".." in name:
        return jsonify({"error": "not found"}), 404
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    if ext not in ASSET_EXT:
        return jsonify({"error": "not found"}), 404
    path = UPLOAD_DIR / name
    if not path.is_file():
        return jsonify({"error": "not found"}), 404
    resp = send_from_directory(str(UPLOAD_DIR), name, conditional=True, max_age=604800)
    resp.headers["X-Content-Type-Options"] = "nosniff"
    if ext == "svg":
        # SVGs are active documents: forbid scripts when viewed directly
        resp.headers["Content-Security-Policy"] = "default-src 'none'; style-src 'unsafe-inline'; img-src data: blob:"
        resp.headers["Content-Disposition"] = "inline"
    return resp
