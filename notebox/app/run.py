"""NoteBox entry point: waitress WSGI server + mail scheduler."""
import os

from notebox import create_app
from notebox.scheduler import start_scheduler

app = create_app()

if __name__ == "__main__":
    start_scheduler(interval=30)
    from waitress import serve

    host = os.environ.get("NB_HOST", "0.0.0.0")
    port = int(os.environ.get("NB_PORT", "13345"))
    print(f"NoteBox listening on http://{host}:{port}")
    serve(app, host=host, port=port, threads=8, ident="NoteBox")
