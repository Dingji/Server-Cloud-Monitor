"""Notes CRUD with multi-user ownership, folders, trash and share permissions."""
import re

from flask import Blueprint, g, jsonify, request

from . import db
from .auth import login_required, note_permission
from .routes_folders import descendant_ids, folder_path

bp = Blueprint("notes", __name__)

COLOR_RE = re.compile(r"^#[0-9a-fA-F]{6}$")


def _me() -> dict:
    return g.user


def _color(raw) -> str:
    color = str(raw or "").strip()
    return color if COLOR_RE.match(color) else ""


def _folder_for(owner_id, raw):
    """Validate a note folder id for its owner (None = unfiled)."""
    if raw in (None, "", 0, "0"):
        return None
    try:
        fid = int(raw)
    except (TypeError, ValueError):
        raise ValueError("归类不存在")
    if not db.row("SELECT 1 FROM folders WHERE id=? AND owner_id=? AND kind='note'", (fid, owner_id)):
        raise ValueError("归类不存在")
    return fid


def _visible_rows(sql_tail: str, params: list, scope: str = "") -> list:
    """Notes owned by me OR shared with me; admins see everything."""
    me = _me()["id"]
    is_admin = _me()["role"] == "admin"
    where = "1=1" if is_admin else "(n.owner_id = ? OR sh.id IS NOT NULL)"
    bind = [] if is_admin else [me]  # owner_id placeholder inside WHERE
    if scope == "mine":
        where += " AND n.owner_id = ?"
        bind.append(me)
    elif scope == "shared":
        where += " AND sh.id IS NOT NULL AND n.owner_id != ?"
        bind.append(me)
    sql = (
        "SELECT n.*, u.email AS owner_email, sh.permission AS my_permission, "
        "CASE WHEN n.owner_id = ? THEN 1 ELSE 0 END AS is_owner, "
        "f.name AS folder_name, f.color AS folder_color "
        "FROM notes n "
        "LEFT JOIN users u ON u.id = n.owner_id "
        "LEFT JOIN shares sh ON sh.note_id = n.id AND sh.grantee_id = ? "
        "LEFT JOIN folders f ON f.id = n.folder_id AND f.owner_id = n.owner_id "
        f"WHERE {where} {sql_tail}"
    )
    return db.rows(sql, tuple([me, me] + bind + params))


def _annotate(note: dict) -> dict:
    perm = note_permission(note, _me())
    note["my_permission"] = None if perm == "owner" else perm
    note["is_owner"] = 1 if perm == "owner" else 0
    if note.get("owner_id") and not note.get("owner_email"):
        owner = db.row("SELECT email FROM users WHERE id=?", (note["owner_id"],))
        note["owner_email"] = owner["email"] if owner else "?"
    note["folder_path"] = ""
    note["folder_name"] = note.get("folder_name") or ""
    note["folder_color"] = note.get("folder_color") or ""
    if note.get("folder_id"):
        chain = folder_path(note.get("owner_id") or _me()["id"], note["folder_id"])
        note["folder_path"] = " / ".join(f["name"] for f in chain)
        if chain:
            note["folder_name"] = chain[-1]["name"]
            note["folder_color"] = chain[-1].get("color") or ""
    return note


@bp.get("/api/notes")
@login_required
def list_notes():
    q = (request.args.get("q") or "").strip()
    tag = (request.args.get("tag") or "").strip()
    folder = (request.args.get("folder") or "").strip()
    trash = (request.args.get("trash") or "") in ("1", "true", "yes")
    scope = (request.args.get("scope") or "").strip()
    sort = (request.args.get("sort") or "").strip()
    tail = " AND n.deleted_at IS NOT NULL" if trash else " AND n.deleted_at IS NULL"
    params: list = []
    if q:
        tail += " AND (n.title LIKE ? OR n.content LIKE ? OR n.tags LIKE ?)"
        like = f"%{q}%"
        params += [like, like, like]
    if tag:
        tail += " AND (',' || n.tags || ',') LIKE ?"
        params.append(f"%,{tag},%")
    if folder:
        if folder in ("none", "0"):
            tail += " AND n.folder_id IS NULL"
        else:
            try:
                fid = int(folder)
            except ValueError:
                return jsonify([])
            ids = descendant_ids(_me()["id"], "note", fid)
            if not ids:
                return jsonify([])
            tail += " AND n.folder_id IN (" + ",".join("?" * len(ids)) + ")"
            params += ids
    if sort == "title":
        tail += " ORDER BY n.pinned DESC, n.title COLLATE NOCASE ASC, n.updated_at DESC LIMIT 500"
    elif sort == "created":
        tail += " ORDER BY n.pinned DESC, n.created_at DESC LIMIT 500"
    else:
        tail += " ORDER BY n.pinned DESC, n.updated_at DESC LIMIT 500"
    return jsonify([_annotate(r) for r in _visible_rows(tail, params, scope)])


@bp.get("/api/tags")
@login_required
def list_tags():
    me = _me()["id"]
    if _me()["role"] == "admin":
        rows = db.rows("SELECT tags FROM notes WHERE tags != '' AND deleted_at IS NULL")
    else:
        rows = db.rows(
            "SELECT n.tags FROM notes n "
            "LEFT JOIN shares sh ON sh.note_id = n.id AND sh.grantee_id = ? "
            "WHERE (n.owner_id = ? OR sh.id IS NOT NULL) AND n.tags != '' AND n.deleted_at IS NULL",
            (me, me),
        )
    tags: list[str] = []
    for r in rows:
        for t in r["tags"].split(","):
            t = t.strip()
            if t and t not in tags:
                tags.append(t)
    return jsonify(sorted(tags))


@bp.post("/api/notes")
@login_required
def create_note():
    data = request.get_json(silent=True) or {}
    owner = _me()["id"]
    try:
        folder_id = _folder_for(owner, data.get("folder_id"))
    except ValueError as exc:
        return jsonify({"error": str(exc)}), 400
    now = db.now()
    nid = db.execute(
        "INSERT INTO notes(title,content,tags,pinned,owner_id,folder_id,color,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?)",
        (
            str(data.get("title") or "未命名笔记").strip()[:200] or "未命名笔记",
            str(data.get("content") or ""),
            str(data.get("tags") or "").strip()[:200],
            1 if data.get("pinned") else 0,
            owner,
            folder_id,
            _color(data.get("color")),
            now,
            now,
        ),
    )
    return jsonify(_annotate(db.row("SELECT * FROM notes WHERE id=?", (nid,)))), 201


def _load(nid: int):
    note = db.row("SELECT * FROM notes WHERE id=?", (nid,))
    if not note:
        return None, (jsonify({"error": "笔记不存在"}), 404)
    perm = note_permission(note, _me())
    if not perm:
        return None, (jsonify({"error": "无权访问该笔记"}), 403)
    return (note, perm), None


@bp.get("/api/notes/<int:nid>")
@login_required
def get_note(nid):
    loaded, err = _load(nid)
    if err:
        return err
    note, _perm = loaded
    return jsonify(_annotate(dict(note)))


@bp.put("/api/notes/<int:nid>")
@login_required
def update_note(nid):
    loaded, err = _load(nid)
    if err:
        return err
    note, perm = loaded
    if perm not in ("owner", "admin", "edit"):
        return jsonify({"error": "只读共享，无权编辑"}), 403
    if note.get("deleted_at"):
        return jsonify({"error": "笔记在回收站中，请先恢复"}), 400
    data = request.get_json(silent=True) or {}
    is_owner = perm == "owner"
    owner_id = note.get("owner_id") or _me()["id"]

    folder_id = note.get("folder_id")
    if "folder_id" in data:
        if not is_owner:
            return jsonify({"error": "只有所有者可以移动归类"}), 403
        try:
            folder_id = _folder_for(owner_id, data.get("folder_id"))
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400

    color = note.get("color") or ""
    if "color" in data:
        if not is_owner:
            return jsonify({"error": "只有所有者可以修改颜色"}), 403
        color = _color(data.get("color"))

    # pinning only affects the owner's own list view
    if is_owner and "pinned" in data:
        pinned = 1 if data.get("pinned") else 0
    else:
        pinned = note["pinned"]
    merged = {
        "title": str(data.get("title", note["title"])).strip()[:200] or "未命名笔记",
        "content": str(data.get("content", note["content"])),
        "tags": str(data.get("tags", note["tags"])).strip()[:200],
        "pinned": pinned,
    }
    db.execute(
        "UPDATE notes SET title=?,content=?,tags=?,pinned=?,folder_id=?,color=?,updated_at=? WHERE id=?",
        (merged["title"], merged["content"], merged["tags"], merged["pinned"], folder_id, color, db.now(), nid),
    )
    return jsonify(_annotate(db.row("SELECT * FROM notes WHERE id=?", (nid,))))


@bp.delete("/api/notes/<int:nid>")
@login_required
def delete_note(nid):
    loaded, err = _load(nid)
    if err:
        return err
    note, perm = loaded
    if perm not in ("owner", "admin"):
        return jsonify({"error": "只有所有者或管理级共享者可以删除"}), 403
    db.execute("DELETE FROM notes WHERE id=?", (nid,))
    db.execute("DELETE FROM shares WHERE note_id=?", (nid,))
    return jsonify({"ok": True})


@bp.post("/api/notes/<int:nid>/trash")
@login_required
def trash_note(nid):
    loaded, err = _load(nid)
    if err:
        return err
    _note, perm = loaded
    if perm not in ("owner", "admin"):
        return jsonify({"error": "只有所有者或管理级共享者可以移入回收站"}), 403
    db.execute("UPDATE notes SET deleted_at=?, updated_at=? WHERE id=?", (db.now(), db.now(), nid))
    return jsonify({"ok": True, "deleted_at": db.now()})


@bp.post("/api/notes/<int:nid>/restore")
@login_required
def restore_note(nid):
    loaded, err = _load(nid)
    if err:
        return err
    _note, perm = loaded
    if perm not in ("owner", "admin"):
        return jsonify({"error": "只有所有者或管理级共享者可以恢复"}), 403
    db.execute("UPDATE notes SET deleted_at=NULL, updated_at=? WHERE id=?", (db.now(), nid))
    return jsonify(_annotate(db.row("SELECT * FROM notes WHERE id=?", (nid,))))


@bp.post("/api/notes/<int:nid>/duplicate")
@login_required
def duplicate_note(nid):
    loaded, err = _load(nid)
    if err:
        return err
    src, _perm = loaded
    now = db.now()
    title = (str(src["title"]).strip() or "未命名笔记")
    title = (title + " 副本")[:200]
    nid2 = db.execute(
        "INSERT INTO notes(title,content,tags,pinned,owner_id,folder_id,color,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?)",
        (title, src["content"], src.get("tags") or "", 0, _me()["id"], None, src.get("color") or "", now, now),
    )
    return jsonify(_annotate(db.row("SELECT * FROM notes WHERE id=?", (nid2,)))), 201


@bp.post("/api/notes/bulk-move")
@login_required
def bulk_move():
    data = request.get_json(silent=True) or {}
    ids = data.get("ids") or []
    if not isinstance(ids, list) or not ids:
        return jsonify({"error": "未选择笔记"}), 400
    try:
        folder_id = _folder_for(_me()["id"], data.get("folder_id"))
    except ValueError as exc:
        return jsonify({"error": str(exc)}), 400
    moved = 0
    for raw in ids[:500]:
        try:
            note = db.row("SELECT * FROM notes WHERE id=?", (int(raw),))
        except (TypeError, ValueError):
            continue
        if not note or note.get("deleted_at"):
            continue
        if note_permission(note, _me()) != "owner":
            continue
        owner_id = note.get("owner_id") or _me()["id"]
        try:
            _folder_for(owner_id, folder_id)
        except ValueError:
            continue
        db.execute("UPDATE notes SET folder_id=?, updated_at=? WHERE id=?", (folder_id, db.now(), note["id"]))
        moved += 1
    return jsonify({"ok": True, "moved": moved})


@bp.post("/api/convert/html")
@login_required
def convert_html():
    """Turn pasted rich text (HTML) into Markdown for the editor."""
    data = request.get_json(silent=True) or {}
    src = str(data.get("html") or "")
    if len(src) > 2_000_000:
        return jsonify({"error": "内容过大"}), 413
    if not src.strip():
        return jsonify({"markdown": ""})
    from .htmlmd import html_to_markdown

    return jsonify({"markdown": html_to_markdown(src)})
