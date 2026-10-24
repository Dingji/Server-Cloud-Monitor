"""Multi-user session auth: g.user loading, admin gating, password policy,
share-permission checks, and bucketed rate limits."""
import threading
import time
from functools import wraps

from flask import g, jsonify, request, session

from . import db

# ---------------------------------------------------------------- rate limits

_lock = threading.Lock()
_buckets: dict[str, list] = {}  # key -> [hits..., window_start]


def _prune(key: str, window: int) -> list:
    now = time.time()
    hits = [h for h in _buckets.get(key, []) if now - h < window]
    if hits:
        _buckets[key] = hits
    elif key in _buckets:
        _buckets.pop(key, None)
    return hits


def rate_exceeded(key: str, max_hits: int, window: int) -> bool:
    with _lock:
        return len(_prune(key, window)) >= max_hits


def rate_hit(key: str, max_hits: int, window: int) -> bool:
    """Record one attempt; returns True when the caller is now over the limit."""
    with _lock:
        hits = _prune(key, window)
        hits.append(time.time())
        _buckets[key] = hits
        return len(hits) > max_hits


def rate_clear(key: str) -> None:
    with _lock:
        _buckets.pop(key, None)


def client_ip() -> str:
    return request.headers.get("X-Forwarded-For", request.remote_addr or "?").split(",")[0].strip()


# login limits (8 fails / 15 min per IP)
MAX_FAILS = 8
WINDOW = 900


def rate_limited(ip: str) -> bool:
    return rate_exceeded(f"login:{ip}", MAX_FAILS, WINDOW)


def record_failure(ip: str) -> None:
    with _lock:
        hits = _prune(f"login:{ip}", WINDOW)
        hits.append(time.time())
        _buckets[f"login:{ip}"] = hits


def clear_failures(ip: str) -> None:
    rate_clear(f"login:{ip}")


# ---------------------------------------------------------------- passwords

def validate_password(pw: str) -> str | None:
    """Return an error message when the password is unacceptable."""
    if len(pw) < 8:
        return "密码至少需要 8 位"
    if len(pw) > 128:
        return "密码过长（最多 128 位）"
    if not pw.strip():
        return "密码不能为空"
    return None


# ---------------------------------------------------------------- sessions

def current_user():
    uid = session.get("uid")
    if not uid:
        return None
    if "_cu" not in g.__dict__:
        g._cu = db.row("SELECT * FROM users WHERE id=? AND active=1", (uid,))
    return g._cu


def login_required(f):
    @wraps(f)
    def wrapper(*args, **kwargs):
        user = current_user()
        if not user:
            return jsonify({"error": "未登录或会话已过期"}), 401
        g.user = user
        return f(*args, **kwargs)

    return wrapper


def admin_required(f):
    @wraps(f)
    def wrapper(*args, **kwargs):
        user = current_user()
        if not user:
            return jsonify({"error": "未登录或会话已过期"}), 401
        if user["role"] != "admin":
            return jsonify({"error": "需要管理员权限"}), 403
        g.user = user
        return f(*args, **kwargs)

    return wrapper


def login_user(email: str, password: str):
    """Returns (user, error, status_code)."""
    ip = client_ip()
    if rate_limited(ip):
        return None, "尝试过于频繁，请 15 分钟后再试", 429
    user = db.row("SELECT * FROM users WHERE email=?", (email,))
    if not user or not db.verify_password(password, user["password"]):
        record_failure(ip)
        return None, "邮箱或密码错误", 401
    if not user["active"]:
        return None, "邮箱尚未验证，请先查收验证邮件后再登录", 403
    clear_failures(ip)
    session.clear()
    session["uid"] = user["id"]
    session.permanent = True
    db.execute("UPDATE users SET last_login=? WHERE id=?", (db.now(), user["id"]))
    return user, None, 200


# ------------------------------------------------------- share permissions

PERMS = ("read", "edit", "admin")
_RANK = {None: 0, "read": 1, "edit": 2, "admin": 3, "owner": 4}


def note_permission(note: dict, user: dict) -> str | None:
    """'owner' | 'admin' | 'edit' | 'read' | None."""
    if not note or not user:
        return None
    if note.get("owner_id") == user["id"] or user.get("role") == "admin":
        return "owner"
    s = db.row(
        "SELECT permission FROM shares WHERE note_id=? AND grantee_id=?",
        (note["id"], user["id"]),
    )
    return s["permission"] if s else None


def note_can(note: dict, user: dict, need: str) -> bool:
    """need in ('read','edit','admin'); 'owner' rank satisfies everything."""
    return _RANK.get(note_permission(note, user), 0) >= _RANK[need]
