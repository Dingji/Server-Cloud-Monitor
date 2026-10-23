# NoteBox clients

NoteBox is exposed over HTTP (default port **13345**). Two clients ship in this repository:

| Client | Location | Use |
|--------|----------|-----|
| **Web** | `../app/notebox/static/` (served by Flask at `/` and `/static/`) | Browser: open `http://<host>:13345/` after `install.sh` |
| **Native (KMP)** | [`../../client/`](../../client/) | Android / desktop Cloud Monitor app → sidebar **Notes** group |

Both talk to the same REST API under `/api/`. Authentication is a **session cookie** (`POST /api/login` with email and password), not the panel bearer token.

Native UI code: `client/composeApp/src/commonMain/kotlin/com/servermaintance/panel/core/NoteBox.kt` and pages under `pages/Note*.kt`, `TodosPage.kt`.
