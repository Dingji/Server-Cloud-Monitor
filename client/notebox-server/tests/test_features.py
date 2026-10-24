"""NoteBox v2 backend integration test (folders, trash, todo features, mail).

Run: python notebox-server/tests/test_features.py
Uses a throwaway SQLite data dir; never touches production data.
"""
import os
import sys
import tempfile
import json
from datetime import date, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
APP_ROOT = os.path.dirname(HERE)
sys.path.insert(0, APP_ROOT)

TMP = tempfile.TemporaryDirectory(prefix="notebox-test-", ignore_cleanup_errors=True)
os.environ["NB_DATA_DIR"] = TMP.name
os.environ["NB_PASS"] = "TestPass1234"
os.environ["TZ"] = "Asia/Shanghai"

from notebox import create_app  # noqa: E402
from notebox import db, scheduler  # noqa: E402
from notebox.mailtokens import quick_token  # noqa: E402
from notebox.timeutil import get_tz  # noqa: E402

app = create_app()
app.config.update(TESTING=True)
c = app.test_client()


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)
    print("  ok:", msg)


def login():
    r = c.post("/api/login", json={"email": "admin", "password": "TestPass1234"})
    check(r.status_code == 200, "login")


login()

# ---------------------------------------------------------------- folders
r = c.post("/api/folders", json={"kind": "note", "name": "工作"})
check(r.status_code == 201, "create root folder")
work = r.get_json()["id"]
r = c.post("/api/folders", json={"kind": "note", "name": "项目", "parent_id": work})
proj = r.get_json()["id"]
r = c.post("/api/folders", json={"kind": "note", "name": "2026", "parent_id": proj})
year = r.get_json()["id"]
check(r.status_code == 201, "create multi-level folders")
check(c.post("/api/folders", json={"kind": "note", "name": "工作"}).status_code == 400, "duplicate sibling rejected")
check(c.put(f"/api/folders/{work}", json={"parent_id": year}).status_code == 400, "cycle rejected")

# ---------------------------------------------------------------- notes
r = c.post("/api/notes", json={"title": "季度计划", "content": "目标", "tags": "计划,工作"})
check(r.status_code == 201, "create note (legacy payload)")
nid = r.get_json()["id"]
r = c.put(f"/api/notes/{nid}", json={"folder_id": year})
check(r.status_code == 200 and r.get_json()["folder_id"] == year, "move note into nested folder")
check(r.get_json()["folder_path"] == "工作 / 项目 / 2026", "folder path annotation")
check(len(c.get("/api/notes?folder=%d" % work).get_json()) == 1, "parent folder includes descendants")
check(len(c.get("/api/notes?folder=none").get_json()) == 0, "unfiled filter")
check(len(c.get("/api/notes?tag=计划").get_json()) == 1, "tag filter")
folders = {f["id"]: f for f in c.get("/api/folders?kind=note").get_json()}
check(folders[work]["total_count"] == 1 and folders[proj]["total_count"] == 1, "folder counts roll up")

r = c.post(f"/api/notes/{nid}/trash")
check(r.status_code == 200, "trash note")
check(len(c.get("/api/notes").get_json()) == 0, "trashed note hidden")
check(len(c.get("/api/notes?trash=1").get_json()) == 1, "trash listing")
check(c.put(f"/api/notes/{nid}", json={"title": "x"}).status_code == 400, "no edit while trashed")
check(c.post(f"/api/notes/{nid}/restore").status_code == 200, "restore note")
check(len(c.get("/api/notes").get_json()) == 1, "restored note visible")

r = c.post(f"/api/notes/{nid}/duplicate")
check(r.status_code == 201 and r.get_json()["title"].endswith("副本"), "duplicate note")
dup = r.get_json()["id"]
r = c.post("/api/notes/bulk-move", json={"ids": [nid, dup], "folder_id": proj})
check(r.get_json()["moved"] == 2, "bulk move notes")

# folder delete re-parents content & children
r = c.delete(f"/api/folders/{year}")
check(r.status_code == 200, "delete folder")
note = c.get("/api/notes?folder=%d" % proj).get_json()
check(len(note) == 2 and all(n["folder_id"] == proj for n in note), "items re-parented to parent folder")

# ---------------------------------------------------------------- todos
r = c.post("/api/folders", json={"kind": "todo", "name": "产品发布"})
check(r.status_code == 201, "create todo list")
tlist = r.get_json()["id"]
tomorrow = (date.today() + timedelta(days=1)).isoformat()
payload = {
    "title": "发布 v2.0", "detail": "合并分支\n跑回归\n发公告",
    "priority": 3, "due_date": tomorrow, "folder_id": tlist,
    "tags": "发布,重要", "repeat": "daily",
    "subtasks": [{"title": "合并分支"}, {"title": "跑回归"}, {"title": "发公告", "done": 1}],
}
r = c.post("/api/todos", json=payload)
check(r.status_code == 201, "create rich todo")
t = r.get_json()
tid = t["id"]
check(t["folder_name"] == "产品发布" and t["subtasks"][0]["id"] == 1, "todo fields persisted")
row = c.get("/api/todos").get_json()[0]
check(row["subtasks_done"] == 1 and row["subtasks_total"] == 3, "subtask progress in list")
check(row["repeat_label"] == "每天", "repeat label")

r = c.post(f"/api/todos/{tid}/toggle")
check(r.status_code == 200 and r.get_json()["next_id"], "completing recurring todo spawns next")
check("重要" in c.get("/api/todos/tags").get_json(), "todo tags endpoint")
rows = c.get("/api/todos").get_json()
check(len(rows) == 2, "next occurrence exists")
nxt = [x for x in rows if x["id"] != tid][0]
check(nxt["due_date"] == (date.today() + timedelta(days=2)).isoformat(), "next due advanced")
check(nxt["subtasks"][0]["done"] == 0, "checklist reset")

# editing a repeating todo to done also spawns the next occurrence
r = c.post("/api/todos", json={"title": "每周复盘", "due_date": tomorrow, "repeat": "weekly"})
tid3 = r.get_json()["id"]
r = c.put(f"/api/todos/{tid3}", json={"title": "每周复盘", "status": "done", "due_date": tomorrow, "repeat": "weekly"})
check(r.status_code == 200 and r.get_json().get("next_id"), "PUT done spawns next occurrence")

# quick complete via signed link
r = c.post("/api/todos", json={"title": "交房租", "due_at": int(__import__("time").time()) + 3600})
tid2 = r.get_json()["id"]
row2 = db.row("SELECT * FROM todos WHERE id=?", (tid2,))
r = c.get(f"/api/todos/{tid2}/quick-complete/{quick_token(row2)}")
check(r.status_code == 200 and "已完成" in r.get_data(as_text=True), "signed one-click complete page")
check(db.row("SELECT status FROM todos WHERE id=?", (tid2,))["status"] == "done", "one-click marked done")

# manual notify without SMTP fails cleanly
r = c.post(f"/api/todos/{tid2}/notify")
check(r.status_code == 400, "manual mail explains missing SMTP")

# ---------------------------------------------------------------- mail rendering
row = db.row("SELECT * FROM todos WHERE id=?", (tid,))
row["tags"] = "发布,重要"
row["repeat"] = "daily"
row["folder_id"] = tlist
cfg = db.all_settings()
cfg["base_url"] = "http://example.test"
tz = get_tz("Asia/Shanghai")
subject, body = scheduler.build_reminder(row, "pre10m", 0, cfg, tz)
for needle in ("发布 v2.0", "合并分支", "发布", "产品发布", "一键标记完成", "每天重复", "跑回归"):
    check(needle in body or needle in subject, f"mail contains {needle}")
check("详细内容" in body and "子任务" in body, "mail has detail block and checklist")
ics = scheduler.ics_attachment(row, tz)[0]
check(ics[0].endswith(".ics") and b"BEGIN:VEVENT" in ics[1] and b"VALARM" in ics[1], "ics calendar attachment")

# ---------------------------------------------------------------- legacy compat
r = c.put(f"/api/notes/{nid}", json={"title": "改名", "content": "c", "tags": "计划", "pinned": 1})
check(r.status_code == 200 and r.get_json()["folder_id"] == proj, "legacy update keeps folder")
check(c.delete(f"/api/notes/{dup}").status_code == 200, "legacy permanent delete")
check(c.post("/api/todos/clear-done").status_code == 200, "legacy clear-done")

print("\nALL BACKEND TESTS PASSED")
TMP.cleanup()
