"""NoteBox application factory."""
from datetime import timedelta
from pathlib import Path

from flask import Flask, jsonify, send_from_directory

from . import db
from .routes_admin import bp as admin_bp
from .routes_folders import bp as folders_bp
from .routes_notes import bp as notes_bp
from .routes_public import bp as public_bp
from .routes_todos import bp as todos_bp
from .routes_users import bp as users_bp

STATIC_DIR = Path(__file__).resolve().parent / "static"


def create_app() -> Flask:
    db.init_db()
    app = Flask(__name__, static_folder=str(STATIC_DIR), static_url_path="/static")
    app.secret_key = db.get_setting("secret")
    app.config.update(
        SESSION_COOKIE_HTTPONLY=True,
        SESSION_COOKIE_SAMESITE="Lax",
        PERMANENT_SESSION_LIFETIME=timedelta(days=30),
        MAX_CONTENT_LENGTH=200 * 1024 * 1024,  # asset uploads (videos)
        JSON_AS_ASCII=False,
    )
    app.json.ensure_ascii = False  # Flask 3: native Chinese in JSON responses
    app.register_blueprint(admin_bp)
    app.register_blueprint(folders_bp)
    app.register_blueprint(notes_bp)
    app.register_blueprint(todos_bp)
    app.register_blueprint(public_bp)
    app.register_blueprint(users_bp)

    @app.get("/")
    def index():
        return send_from_directory(str(STATIC_DIR), "index.html")

    @app.errorhandler(404)
    def not_found(e):
        from flask import request

        if request.path.startswith("/api/") or request.path.startswith("/uploads/"):
            return jsonify({"error": "接口不存在"}), 404
        return send_from_directory(str(STATIC_DIR), "index.html")

    @app.errorhandler(413)
    def too_large(_e):
        return jsonify({"error": "文件太大（上限 16MB）"}), 413

    @app.errorhandler(500)
    def server_error(_e):
        return jsonify({"error": "服务器内部错误"}), 500

    return app
