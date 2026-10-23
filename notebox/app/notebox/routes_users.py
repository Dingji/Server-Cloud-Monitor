"""Registration, mailbox verification, and admin user management."""
import html
import re
import secrets
from datetime import timedelta
from flask import Blueprint, g, jsonify, redirect, request

from . import db, mailer
from .auth import admin_required, client_ip, login_required, rate_exceeded, rate_hit, validate_password

bp = Blueprint("users", __name__)

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
VERIFY_TTL = 86400  # 24h


def _base_url(cfg: dict | None = None) -> str:
    cfg = cfg or db.all_settings()
    return (cfg.get("base_url") or "http://8.153.153.176:13345").rstrip("/")


def send_verification(user: dict) -> bool:
    """Always (re)issue a verification token; email it when SMTP is available.
    Returns True only when the mail actually went out."""
    token = secrets.token_urlsafe(32)
    db.execute(
        "UPDATE users SET verify_token=?, verify_expire=? WHERE id=?",
        (token, db.now() + VERIFY_TTL, user["id"]),
    )
    if not mailer.smtp_ready():
        return False
    link = _base_url() + "/verify/" + token
    body = mailer.render_mail(
        "验证你的邮箱，激活 NoteBox 账号",
        [
            f"你正在注册 NoteBox，账号：<b>{html.escape(user['email'])}</b>",
            "请点击下方按钮完成邮箱验证，链接 <b>24 小时内</b>有效。",
            "验证通过后即可登录。若非本人操作，请忽略本邮件。",
        ],
        button=True,
        base_url=link,
        icon="check",
        button_text="验证并启用账号",
    )
    ok, _err = mailer.send(
        "[NoteBox] 邮箱验证：请激活你的账号", body, kind="verify", to_override=user["email"]
    )
    return ok


# ------------------------------------------------------------ registration

@bp.post("/api/register")
def register():
    ip = client_ip()
    if rate_hit(f"register:{ip}", 10, 3600):
        return jsonify({"error": "注册尝试过于频繁，请稍后再试"}), 429
    data = request.get_json(silent=True) or {}
    email = str(data.get("email") or "").strip().lower()
    password = str(data.get("password") or "")
    if not EMAIL_RE.match(email) or len(email) > 254:
        return jsonify({"error": "邮箱格式不正确"}), 400
    pw_err = validate_password(password)
    if pw_err:
        return jsonify({"error": pw_err}), 400
    if db.row("SELECT 1 FROM users WHERE email=?", (email,)):
        return jsonify({"error": "该邮箱已注册" + ("" if db.row("SELECT active FROM users WHERE email=?", (email,))["active"] else "（尚未完成邮箱验证）")}), 400
    uid = db.execute(
        "INSERT INTO users(email,password,role,active,verify_token,verify_expire,created_at) "
        "VALUES(?,?, 'user', 0, NULL, NULL, ?)",
        (email, db.hash_password(password), db.now()),
    )
    user = db.row("SELECT * FROM users WHERE id=?", (uid,))
    sent = send_verification(user)  # token always issued; mail only if SMTP is ready
    if sent:
        message = "验证邮件已发送，请查收（24 小时内有效）。"
    else:
        message = "账号已创建，但验证邮件未能发送（站点尚未配置邮件服务或发送失败），请联系管理员为你启用。"
    return jsonify({"ok": True, "mail_sent": sent, "message": message})


@bp.get("/verify/<token>")
def verify(token: str):
    user = db.row(
        "SELECT id FROM users WHERE verify_token=? AND verify_expire>? AND active=0",
        (token, db.now()),
    )
    if not user:
        return redirect("/?verify_error=1")
    db.execute(
        "UPDATE users SET active=1, verify_token=NULL, verify_expire=NULL WHERE id=?",
        (user["id"],),
    )
    return redirect("/?verified=1")


@bp.post("/api/resend-verification")
def resend():
    ip = client_ip()
    if rate_hit(f"resend:{ip}", 10, 3600):
        return jsonify({"error": "发送过于频繁，请稍后再试"}), 429
    data = request.get_json(silent=True) or {}
    email = str(data.get("email") or "").strip().lower()
    user = db.row("SELECT * FROM users WHERE email=? AND active=0", (email,))
    sent = False
    if user:
        sent = send_verification(user)
    # do not reveal whether the mailbox exists
    message = "若该邮箱存在且未验证，验证邮件已重新发送。" if sent else "若该邮箱存在且未验证，我们已尝试重新发送（若站点未配置邮件服务，请联系管理员为你启用）。"
    return jsonify({"ok": True, "message": message})


# ---------------------------------------------------------- admin: users

def _user_row(u: dict) -> dict:
    return {
        "id": u["id"],
        "email": u["email"],
        "role": u["role"],
        "active": u["active"],
        "status": "active" if u["active"] else ("pending" if u.get("verify_token") else "disabled"),
        "created_at": u["created_at"],
        "last_login": u.get("last_login"),
        "notes": u.get("notes", 0),
        "todos": u.get("todos", 0),
    }


@bp.get("/api/admin/users")
@admin_required
def list_users():
    rows = db.rows(
        "SELECT u.*, "
        "(SELECT COUNT(*) FROM notes WHERE owner_id=u.id) AS notes, "
        "(SELECT COUNT(*) FROM todos WHERE owner_id=u.id) AS todos "
        "FROM users u ORDER BY u.id"
    )
    return jsonify([_user_row(u) for u in rows])


@bp.post("/api/admin/users")
@admin_required
def create_user():
    data = request.get_json(silent=True) or {}
    email = str(data.get("email") or "").strip().lower()
    password = str(data.get("password") or "")
    role = "admin" if str(data.get("role") or "user") == "admin" else "user"
    if not EMAIL_RE.match(email):
        return jsonify({"error": "邮箱格式不正确"}), 400
    pw_err = validate_password(password)
    if pw_err:
        return jsonify({"error": pw_err}), 400
    if db.row("SELECT 1 FROM users WHERE email=?", (email,)):
        return jsonify({"error": "该邮箱已存在"}), 400
    uid = db.execute(
        "INSERT INTO users(email,password,role,active,created_at) VALUES(?,?,?,1,?)",
        (email, db.hash_password(password), role, db.now()),
    )
    return jsonify(_user_row(db.row("SELECT * FROM users WHERE id=?", (uid,)))), 201


@bp.put("/api/admin/users/<int:uid>")
@admin_required
def update_user(uid):
    target = db.row("SELECT * FROM users WHERE id=?", (uid,))
    if not target:
        return jsonify({"error": "用户不存在"}), 404
    me = g.user
    data = request.get_json(silent=True) or {}

    # guards against locking ourselves out
    if "role" in data:
        new_role = "admin" if str(data["role"]) == "admin" else "user"
        if uid == me["id"] and new_role != "admin":
            return jsonify({"error": "不能撤销自己的管理员身份"}), 400
        if target["role"] == "admin" and new_role != "admin":
            others = db.scalar("SELECT COUNT(*) FROM users WHERE role='admin' AND id!=?", (uid,)) or 0
            if others == 0:
                return jsonify({"error": "必须至少保留一名管理员"}), 400
    if "active" in data:
        want_active = bool(data["active"])
        if uid == me["id"] and not want_active:
            return jsonify({"error": "不能停用自己的账号"}), 400
        if target["role"] == "admin" and not want_active:
            others = db.scalar("SELECT COUNT(*) FROM users WHERE role='admin' AND id!=? AND active=1", (uid,)) or 0
            if others == 0:
                return jsonify({"error": "必须至少保留一名启用的管理员"}), 400

    sets, params = [], []
    if "email" in data:
        email = str(data["email"] or "").strip().lower()
        if not EMAIL_RE.match(email):
            return jsonify({"error": "邮箱格式不正确"}), 400
        if email != target["email"] and db.row("SELECT 1 FROM users WHERE email=? AND id!=?", (email, uid)):
            return jsonify({"error": "该邮箱已存在"}), 400
        sets.append("email=?")
        params.append(email)
    if data.get("password"):
        pw_err = validate_password(str(data["password"]))
        if pw_err:
            return jsonify({"error": pw_err}), 400
        sets.append("password=?")
        params.append(db.hash_password(str(data["password"])))
    if "role" in data:
        sets.append("role=?")
        params.append("admin" if str(data["role"]) == "admin" else "user")
    if "active" in data:
        sets.append("active=?")
        params.append(1 if data["active"] else 0)
        if not data["active"]:
            sets.append("verify_token=NULL")
    if not sets:
        return jsonify({"error": "没有要更新的字段"}), 400
    params.append(uid)
    db.execute(f"UPDATE users SET {', '.join(sets)} WHERE id=?", tuple(params))
    return jsonify(_user_row(db.row("SELECT * FROM users WHERE id=?", (uid,))))


@bp.delete("/api/admin/users/<int:uid>")
@admin_required
def delete_user(uid):
    me = g.user
    target = db.row("SELECT * FROM users WHERE id=?", (uid,))
    if not target:
        return jsonify({"error": "用户不存在"}), 404
    if uid == me["id"]:
        return jsonify({"error": "不能删除自己"}), 400
    if target["role"] == "admin":
        others = db.scalar("SELECT COUNT(*) FROM users WHERE role='admin' AND id!=? AND active=1", (uid,)) or 0
        if others == 0:
            return jsonify({"error": "必须至少保留一名启用的管理员"}), 400
    db.execute("DELETE FROM todos WHERE owner_id=?", (uid,))
    db.execute("DELETE FROM notes WHERE owner_id=?", (uid,))
    db.execute("DELETE FROM shares WHERE owner_id=? OR grantee_id=?", (uid, uid))
    db.execute("DELETE FROM users WHERE id=?", (uid,))
    return jsonify({"ok": True, "deleted": target["email"]})


@bp.post("/api/admin/users/<int:uid>/resend")
@admin_required
def admin_resend(uid):
    user = db.row("SELECT * FROM users WHERE id=?", (uid,))
    if not user:
        return jsonify({"error": "用户不存在"}), 404
    if user["active"]:
        return jsonify({"error": "该用户已激活"}), 400
    if not mailer.smtp_ready():
        return jsonify({"error": "站点尚未配置 SMTP 邮件服务"}), 400
    send_verification(user)
    return jsonify({"ok": True})
