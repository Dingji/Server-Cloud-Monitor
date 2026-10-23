# NoteBox

Flask + SQLite service for notes, todos, sharing, and mail reminders. Default listen: `0.0.0.0:13345`.

| Piece | Path |
|-------|------|
| Server code | `app/` (`run.py`, `notebox/` package) |
| Browser client | `app/notebox/static/` (served at `/`) |
| Native client | Repository [`client/`](../client/) — Notes section in the KMP app |
| Install | `./install.sh` (root of this directory) |

Full documentation: [repository README](../README.md#notebox-notebox).

Quick install on Ubuntu (as root):

```bash
./install.sh
```

Quick dev run:

```bash
cd app
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
mkdir -p data
NB_DATA_DIR=./data NB_PORT=13345 python run.py
```

Environment variables (production set by systemd):

| Variable | Default | Meaning |
|----------|---------|---------|
| `NB_HOST` | `0.0.0.0` | Bind address |
| `NB_PORT` | `13345` | TCP port |
| `NB_DATA_DIR` | `./data` next to package | SQLite and uploads |
