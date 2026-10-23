"""Signed one-click links embedded in notification mails (no login required)."""
import hmac
from hashlib import sha256

from . import db


def _due_key(t: dict) -> str:
    return str(t.get("due_at") or t.get("due_date") or "")


def quick_token(t: dict) -> str:
    secret = db.get_setting("secret") or ""
    msg = f"quick-complete:{t['id']}:{_due_key(t)}".encode()
    return hmac.new(secret.encode(), msg, sha256).hexdigest()[:32]


def verify_quick_token(token: str, t: dict) -> bool:
    return hmac.compare_digest(quick_token(t), token or "")
