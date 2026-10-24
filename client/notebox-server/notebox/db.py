"""SQLite storage layer: thread-local connections, WAL mode, tiny helpers."""
import hashlib
import hmac
import os
import secrets
import sqlite3
import threading
import time
from pathlib import Path

DATA_DIR = Path(os.environ.get("NB_DATA_DIR") or (Path(__file__).resolve().parent.parent / "data"))
DB_PATH = DATA_DIR / "notebox.db"

_local = threading.local()

DEFAULT_SETTINGS = {
    "smtp_host": "smtp.163.com",
    "smtp_port": "465",
    "smtp_ssl": "1",
    "smtp_user": "",
    "smtp_pass": "",
    "mail_from": "",
    "mail_to": "",
    "notify_enabled": "1",
    "remind_before_min": "10",
    "digest_enabled": "1",
    "digest_time": "08:30",
    "digest_tz": "Asia/Shanghai",
    "base_url": "http://8.153.153.176:13345",
}

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email TEXT NOT NULL UNIQUE,
    password TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user',
    active INTEGER NOT NULL DEFAULT 0,
    verify_token TEXT,
    verify_expire INTEGER,
    created_at INTEGER NOT NULL,
    last_login INTEGER
);
CREATE TABLE IF NOT EXISTS shares (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    note_id INTEGER NOT NULL,
    owner_id INTEGER NOT NULL,
    grantee_id INTEGER NOT NULL,
    permission TEXT NOT NULL DEFAULT 'read',
    created_at INTEGER NOT NULL,
    UNIQUE(note_id, grantee_id)
);
CREATE TABLE IF NOT EXISTS folders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id INTEGER NOT NULL,
    kind TEXT NOT NULL DEFAULT 'note',
    parent_id INTEGER,
    name TEXT NOT NULL,
    color TEXT NOT NULL DEFAULT '',
    position INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS notes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    tags TEXT NOT NULL DEFAULT '',
    pinned INTEGER NOT NULL DEFAULT 0,
    share_code TEXT,
    owner_id INTEGER,
    folder_id INTEGER,
    color TEXT NOT NULL DEFAULT '',
    deleted_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS todos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'pending',
    priority INTEGER NOT NULL DEFAULT 1,
    due_at INTEGER,
    due_date TEXT,
    remind INTEGER NOT NULL DEFAULT 1,
    owner_id INTEGER,
    folder_id INTEGER,
    tags TEXT NOT NULL DEFAULT '',
    subtasks TEXT NOT NULL DEFAULT '[]',
    repeat TEXT NOT NULL DEFAULT 'none',
    reminded INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_try INTEGER,
    sent_pre1d INTEGER NOT NULL DEFAULT 0,
    sent_pre10m INTEGER NOT NULL DEFAULT 0,
    tries_pre1d INTEGER NOT NULL DEFAULT 0,
    tries_pre10m INTEGER NOT NULL DEFAULT 0,
    completed_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT
);
CREATE TABLE IF NOT EXISTS email_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sent_at INTEGER NOT NULL,
    kind TEXT NOT NULL DEFAULT 'test',
    subject TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'ok',
    error TEXT DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_todos_status ON todos(status);
CREATE INDEX IF NOT EXISTS idx_notes_updated ON notes(updated_at);
CREATE INDEX IF NOT EXISTS idx_folders_owner ON folders(owner_id, kind, parent_id);
"""


def now() -> int:
    return int(time.time())


def get_db() -> sqlite3.Connection:
    db = getattr(_local, "db", None)
    if db is None:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(DB_PATH, timeout=15, check_same_thread=False)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA busy_timeout=15000")
        _local.db = db
    return db


def rows(sql: str, params: tuple = ()):
    return [dict(r) for r in get_db().execute(sql, params).fetchall()]


def row(sql: str, params: tuple = ()):
    r = get_db().execute(sql, params).fetchone()
    return dict(r) if r else None


def scalar(sql: str, params: tuple = ()):
    r = get_db().execute(sql, params).fetchone()
    return r[0] if r else None


def execute(sql: str, params: tuple = ()):
    db = get_db()
    cur = db.execute(sql, params)
    db.commit()
    return cur.lastrowid


def hash_password(pw: str, salt: str | None = None) -> str:
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", pw.encode("utf-8"), bytes.fromhex(salt), 200_000).hex()
    return f"pbkdf2-sha256${salt}${digest}"


def verify_password(pw: str, stored: str) -> bool:
    try:
        _scheme, salt, digest = (stored or "").split("$")
        cand = hashlib.pbkdf2_hmac("sha256", pw.encode("utf-8"), bytes.fromhex(salt), 200_000).hex()
        return hmac.compare_digest(cand, digest)
    except Exception:
        return False


def get_setting(key: str, default: str = "") -> str:
    v = scalar("SELECT value FROM settings WHERE key=?", (key,))
    return default if v is None else v


def set_setting(key: str, value: str) -> None:
    execute("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, str(value)))


def all_settings() -> dict:
    return {r["key"]: r["value"] for r in rows("SELECT key,value FROM settings")}


def _migrate() -> None:
    """Add columns introduced after a table was first created."""
    db = get_db()

    def cols(table: str) -> set:
        return {r[1] for r in db.execute(f"PRAGMA table_info({table})").fetchall()}

    for table, extra in (
        ("notes", [("share_code", "TEXT"), ("owner_id", "INTEGER"),
                   ("folder_id", "INTEGER"), ("color", "TEXT NOT NULL DEFAULT ''"),
                   ("deleted_at", "INTEGER")]),
        ("todos", [("owner_id", "INTEGER"),
                   ("folder_id", "INTEGER"), ("tags", "TEXT NOT NULL DEFAULT ''"),
                   ("subtasks", "TEXT NOT NULL DEFAULT '[]'"), ("repeat", "TEXT NOT NULL DEFAULT 'none'"),
                   ("sent_pre1d", "INTEGER NOT NULL DEFAULT 0"),
                   ("sent_pre10m", "INTEGER NOT NULL DEFAULT 0"),
                   ("tries_pre1d", "INTEGER NOT NULL DEFAULT 0"),
                   ("tries_pre10m", "INTEGER NOT NULL DEFAULT 0"),
                   ("last_pre1d", "INTEGER"),
                   ("last_pre10m", "INTEGER")]),
    ):
        have = cols(table)
        for name, ddl in extra:
            if name not in have:
                db.execute(f"ALTER TABLE {table} ADD COLUMN {name} {ddl}")
    db.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_notes_share ON notes(share_code) WHERE share_code IS NOT NULL"
    )
    # indexes on migrated columns must be created after ALTER TABLE above
    db.execute("CREATE INDEX IF NOT EXISTS idx_notes_folder ON notes(folder_id)")
    db.execute("CREATE INDEX IF NOT EXISTS idx_todos_folder ON todos(folder_id)")
    db.commit()


def _seed_users_and_owners() -> None:
    """Create the first admin from legacy single-user settings and attach all
    existing notes/todos to that admin (idempotent)."""
    if scalar("SELECT COUNT(*) FROM users") == 0:
        email = get_setting("username") or "admin"
        pw_hash = get_setting("password") or hash_password(
            os.environ.get("NB_PASS", "Ladridibiciclette!")
        )
        execute(
            "INSERT INTO users(email,password,role,active,created_at) VALUES(?,?, 'admin', 1, ?)",
            (email, pw_hash, int(time.time())),
        )
    # legacy single-user credentials are superseded by the users table
    execute("DELETE FROM settings WHERE key IN ('username','password')")
    admin_id = scalar("SELECT id FROM users ORDER BY (role != 'admin'), id LIMIT 1")
    if admin_id:
        execute("UPDATE notes SET owner_id=? WHERE owner_id IS NULL", (admin_id,))
        execute("UPDATE todos SET owner_id=? WHERE owner_id IS NULL", (admin_id,))


def init_db() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    db = get_db()
    db.executescript(SCHEMA)
    db.commit()
    _migrate()
    _seed_users_and_owners()
    for k, v in DEFAULT_SETTINGS.items():
        if scalar("SELECT 1 FROM settings WHERE key=?", (k,)) is None:
            set_setting(k, v)
    if scalar("SELECT 1 FROM settings WHERE key='secret'") is None:
        set_setting("secret", secrets.token_hex(32))
    # NOTE: legacy username/password settings are intentionally NOT re-seeded —
    # credentials now live in the users table (see _seed_users_and_owners).
