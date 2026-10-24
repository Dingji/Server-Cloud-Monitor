"""Hierarchical folders (multi-level) shared by notes and todos.

Folders are per-user and per-kind: the same tree service powers note
categories and todo lists. Deleting a folder never deletes its content —
children and items are re-parented one level up.
"""
import re

from flask import Blueprint, g, jsonify, request

from . import db
from .auth import login_required

bp = Blueprint("folders", __name__)

KINDS = ("note", "todo")
COLOR_RE = re.compile(r"^#[0-9a-fA-F]{6}$")
MAX_DEPTH = 8
MAX_NAME = 60


def _me() -> dict:
    return g.user


def _sanitize_color(raw) -> str:
    color = str(raw or "").strip()
    return color if COLOR_RE.match(color) else ""


def _own(fid: int):
    return db.row("SELECT * FROM folders WHERE id=? AND owner_id=?", (fid, _me()["id"]))


def _depth(fid) -> int:
    """0 for a root folder."""
    depth, seen = 0, set()
    while fid:
        if fid in seen:
            break
        seen.add(fid)
        f = db.row("SELECT parent_id FROM folders WHERE id=?", (fid,))
        if not f:
            break
        fid = f["parent_id"]
        depth += 1
    return depth


def subtree_height(fid: int) -> int:
    """Number of levels below `fid` (0 = leaf)."""
    kids = [r["id"] for r in db.rows("SELECT id FROM folders WHERE parent_id=?", (fid,))]
    return 0 if not kids else 1 + max(subtree_height(k) for k in kids)


def descendant_ids(owner_id: int, kind: str, fid: int) -> list[int]:
    out, stack = [], [fid]
    while stack:
        cur = stack.pop()
        out.append(cur)
        stack.extend(
            r["id"]
            for r in db.rows(
                "SELECT id FROM folders WHERE owner_id=? AND kind=? AND parent_id=?", (owner_id, kind, cur)
            )
        )
    return out


def folder_path(owner_id: int, fid) -> list[dict]:
    """Root -> ... -> folder chain (empty when the folder is gone)."""
    chain, seen = [], set()
    while fid and fid not in seen:
        seen.add(fid)
        f = db.row(
            "SELECT id,name,parent_id,color FROM folders WHERE id=? AND owner_id=?",
            (fid, owner_id),
        )
        if not f:
            break
        chain.append(f)
        fid = f["parent_id"]
    return list(reversed(chain))


def folder_path_text(owner_id: int, fid) -> str:
    return " / ".join(f["name"] for f in folder_path(owner_id, fid))


@bp.get("/api/folders")
@login_required
def list_folders():
    kind = (request.args.get("kind") or "note").strip()
    if kind not in KINDS:
        kind = "note"
    me = _me()["id"]
    rows = db.rows(
        "SELECT * FROM folders WHERE owner_id=? AND kind=? ORDER BY position, name COLLATE NOCASE",
        (me, kind),
    )
    if kind == "note":
        counts = db.rows(
            "SELECT folder_id, COUNT(*) AS n FROM notes WHERE owner_id=? AND deleted_at IS NULL "
            "AND folder_id IS NOT NULL GROUP BY folder_id",
            (me,),
        )
    else:
        counts = db.rows(
            "SELECT folder_id, COUNT(*) AS n FROM todos WHERE owner_id=? "
            "AND folder_id IS NOT NULL GROUP BY folder_id",
            (me,),
        )
    direct = {int(r["folder_id"]): int(r["n"]) for r in counts}
    by_id = {int(r["id"]): r for r in rows}
    for r in rows:
        r["count"] = direct.get(int(r["id"]), 0)
        r["total_count"] = r["count"]
    # roll direct counts up the tree (children first)
    for r in sorted(rows, key=lambda x: _depth(x["parent_id"]), reverse=True):
        parent = r.get("parent_id")
        if parent and int(parent) in by_id:
            by_id[int(parent)]["total_count"] += r["total_count"]
    for r in rows:
        r["kind"] = kind
    return jsonify(rows)


@bp.post("/api/folders")
@login_required
def create_folder():
    data = request.get_json(silent=True) or {}
    kind = str(data.get("kind") or "note").strip()
    if kind not in KINDS:
        return jsonify({"error": "归类类型不正确"}), 400
    name = str(data.get("name") or "").strip()
    if not name:
        return jsonify({"error": "归类名称不能为空"}), 400
    if len(name) > MAX_NAME:
        return jsonify({"error": f"归类名称最多 {MAX_NAME} 个字符"}), 400
    parent_id = data.get("parent_id")
    parent_id = int(parent_id) if parent_id not in (None, "", 0, "0") else None
    if parent_id is not None:
        parent = _own(parent_id)
        if not parent or parent["kind"] != kind:
            return jsonify({"error": "上级归类不存在"}), 400
        if _depth(parent_id) + 1 >= MAX_DEPTH:
            return jsonify({"error": "归类层级过深"}), 400
    dup = db.row(
        "SELECT id FROM folders WHERE owner_id=? AND kind=? AND IFNULL(parent_id,0)=IFNULL(?,0) "
        "AND name=? COLLATE NOCASE",
        (_me()["id"], kind, parent_id, name),
    )
    if dup:
        return jsonify({"error": "同级下已有同名归类"}), 400
    now = db.now()
    pos = db.scalar(
        "SELECT IFNULL(MAX(position), -1) + 1 FROM folders WHERE owner_id=? AND kind=? "
        "AND IFNULL(parent_id,0)=IFNULL(?,0)",
        (_me()["id"], kind, parent_id),
    )
    fid = db.execute(
        "INSERT INTO folders(owner_id,kind,parent_id,name,color,position,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?)",
        (_me()["id"], kind, parent_id, name, _sanitize_color(data.get("color")), int(pos or 0), now, now),
    )
    row = db.row("SELECT * FROM folders WHERE id=?", (fid,))
    row["count"] = 0
    row["total_count"] = 0
    row["kind"] = kind
    return jsonify(row), 201


@bp.put("/api/folders/<int:fid>")
@login_required
def update_folder(fid):
    folder = _own(fid)
    if not folder:
        return jsonify({"error": "归类不存在"}), 404
    data = request.get_json(silent=True) or {}
    kind = folder["kind"]
    name = str(data.get("name", folder["name"]) or "").strip()
    if not name:
        return jsonify({"error": "归类名称不能为空"}), 400
    if len(name) > MAX_NAME:
        return jsonify({"error": f"归类名称最多 {MAX_NAME} 个字符"}), 400
    parent_id = data.get("parent_id", folder["parent_id"])
    parent_id = int(parent_id) if parent_id not in (None, "", 0, "0") else None
    if parent_id is not None:
        if parent_id == fid:
            return jsonify({"error": "不能把归类移到自己下面"}), 400
        parent = _own(parent_id)
        if not parent or parent["kind"] != kind:
            return jsonify({"error": "上级归类不存在"}), 400
        if parent_id in descendant_ids(_me()["id"], kind, fid):
            return jsonify({"error": "不能把归类移到自己的子归类下面"}), 400
        if _depth(parent_id) + 1 + subtree_height(fid) >= MAX_DEPTH:
            return jsonify({"error": "归类层级过深"}), 400
    dup = db.row(
        "SELECT id FROM folders WHERE owner_id=? AND kind=? AND IFNULL(parent_id,0)=IFNULL(?,0) "
        "AND name=? COLLATE NOCASE AND id != ?",
        (_me()["id"], kind, parent_id, name, fid),
    )
    if dup:
        return jsonify({"error": "同级下已有同名归类"}), 400
    color = _sanitize_color(data.get("color", folder["color"]))
    pos = data.get("position", folder["position"])
    try:
        pos = int(pos)
    except (TypeError, ValueError):
        pos = folder["position"]
    db.execute(
        "UPDATE folders SET name=?,parent_id=?,color=?,position=?,updated_at=? WHERE id=?",
        (name, parent_id, color, pos, db.now(), fid),
    )
    row = db.row("SELECT * FROM folders WHERE id=?", (fid,))
    row["kind"] = kind
    return jsonify(row)


@bp.delete("/api/folders/<int:fid>")
@login_required
def delete_folder(fid):
    folder = _own(fid)
    if not folder:
        return jsonify({"error": "归类不存在"}), 404
    parent_id = folder["parent_id"]
    child_ids = [r["id"] for r in db.rows("SELECT id FROM folders WHERE parent_id=?", (fid,))]
    db.execute("UPDATE folders SET parent_id=?, updated_at=? WHERE parent_id=?", (parent_id, db.now(), fid))
    moved = 0
    if folder["kind"] == "note":
        moved = db.scalar("SELECT COUNT(*) FROM notes WHERE folder_id=?", (fid,)) or 0
        db.execute("UPDATE notes SET folder_id=? WHERE folder_id=?", (parent_id, fid))
    else:
        moved = db.scalar("SELECT COUNT(*) FROM todos WHERE folder_id=?", (fid,)) or 0
        db.execute("UPDATE todos SET folder_id=? WHERE folder_id=?", (parent_id, fid))
    db.execute("DELETE FROM folders WHERE id=?", (fid,))
    return jsonify({"ok": True, "items": int(moved), "children": len(child_ids)})
