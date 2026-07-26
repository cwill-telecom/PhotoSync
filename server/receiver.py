#!/usr/bin/env python3
"""
PhotoSync Receiver — runs on the user's PC, receives photos from the Android app.

Usage:
    pip install flask
    python receiver.py --token my-secret-token --port 8765

The Android app sends photos as multipart/form-data POST to /upload
with Bearer token auth. Photos are saved to ./photos/YYYY/MM/ by default.
"""

import argparse
import os
import sys
import hmac
from datetime import datetime, timezone
from functools import wraps
from pathlib import Path

from flask import Flask, request, jsonify, current_app

app = Flask(__name__)

# Defaults (override via CLI args or env vars)
app.config.setdefault("PHOTOSYNC_DIR", Path(os.environ.get("PHOTOSYNC_DIR", "./photos")))
app.config.setdefault("PHOTOSYNC_TOKEN", os.environ.get("PHOTOSYNC_TOKEN", ""))

ALLOWED_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".bmp", ".mp4", ".mov"}


def check_auth(f):
    """Decorator that validates Bearer token against the configured token."""
    @wraps(f)
    def wrapper(*args, **kwargs):
        token = current_app.config["PHOTOSYNC_TOKEN"]
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return jsonify({"error": "Missing Authorization header"}), 401
        provided = auth.removeprefix("Bearer ")
        if not hmac.compare_digest(provided, token):
            return jsonify({"error": "Invalid token"}), 403
        return f(*args, **kwargs)
    return wrapper


def safe_filename(name: str) -> str:
    """Sanitize a filename, preserving extension."""
    stem, ext = os.path.splitext(name)
    stem = stem.replace("/", "_").replace("\\", "_").replace("\0", "")
    if ext.lower() not in ALLOWED_EXTENSIONS:
        ext = ".jpg"
    return f"{stem}{ext}"


def get_photo_path(date_added: str | None = None) -> Path:
    """Return the save path: photos/YYYY/MM/."""
    photos_dir = current_app.config["PHOTOSYNC_DIR"]
    if date_added:
        try:
            ts = int(date_added)
            dt = datetime.fromtimestamp(ts, tz=timezone.utc)
        except (ValueError, TypeError):
            dt = datetime.now(tz=timezone.utc)
    else:
        dt = datetime.now(tz=timezone.utc)
    return photos_dir / f"{dt.year:04d}" / f"{dt.month:02d}"


@app.route("/health", methods=["GET"])
@check_auth
def health():
    """Health check endpoint. Returns 200 if the server is running."""
    photos_dir = current_app.config["PHOTOSYNC_DIR"]
    return jsonify({
        "status": "ok",
        "photos_dir": str(photos_dir.resolve()),
        "server_time": datetime.now(timezone.utc).isoformat(),
    })


@app.route("/upload", methods=["POST"])
@check_auth
def upload():
    """Receive a photo upload. Expects multipart/form-data with a 'file' field."""
    if "file" not in request.files:
        return jsonify({"error": "No file part in request"}), 400

    file = request.files["file"]
    if file.filename is None or file.filename == "":
        return jsonify({"error": "No file selected"}), 400

    date_added = request.form.get("date_added", "")
    display_name = request.form.get("display_name", file.filename)

    filename = safe_filename(display_name)
    save_dir = get_photo_path(date_added)
    save_dir.mkdir(parents=True, exist_ok=True)

    save_path = save_dir / filename

    # Avoid overwriting: append a counter if file exists
    if save_path.exists():
        stem, ext = os.path.splitext(filename)
        counter = 1
        while save_path.exists():
            save_path = save_dir / f"{stem}_{counter}{ext}"
            counter += 1

    file.save(str(save_path))
    size = save_path.stat().st_size

    print(f"[OK] {datetime.now().strftime('%H:%M:%S')} | Received: {display_name} "
          f"-> {save_path} ({size:,} bytes)")

    return jsonify({
        "status": "ok",
        "filename": save_path.name,
        "path": str(save_path),
        "size_bytes": size,
    })


@app.route("/photos", methods=["GET"])
@check_auth
def list_photos():
    """List all received photos (optional — for debugging)."""
    photos_dir = current_app.config["PHOTOSYNC_DIR"]
    photos = []
    if photos_dir.exists():
        for f in sorted(photos_dir.rglob("*"), reverse=True):
            if f.is_file() and f.suffix.lower() in ALLOWED_EXTENSIONS:
                photos.append({
                    "name": f.name,
                    "path": str(f.relative_to(photos_dir)),
                    "size": f.stat().st_size,
                })
    return jsonify({"photos": photos[:500]})


def main():
    parser = argparse.ArgumentParser(description="PhotoSync Receiver")
    parser.add_argument("--token", default=app.config["PHOTOSYNC_TOKEN"],
                        help="Shared API token (also settable via PHOTOSYNC_TOKEN env var)")
    parser.add_argument("--port", type=int, default=8765,
                        help="Port to listen on (default: 8765)")
    parser.add_argument("--dir", default=str(app.config["PHOTOSYNC_DIR"]),
                        help="Directory to save photos (also settable via PHOTOSYNC_DIR env var)")
    parser.add_argument("--host", default="0.0.0.0",
                        help="Bind address (default: 0.0.0.0)")
    parser.add_argument("--debug", action="store_true",
                        help="Enable Flask debug mode")
    args = parser.parse_args()

    app.config["PHOTOSYNC_TOKEN"] = args.token
    app.config["PHOTOSYNC_DIR"] = Path(args.dir)

    if not app.config["PHOTOSYNC_TOKEN"]:
        print("ERROR: API token is required. Set --token or PHOTOSYNC_TOKEN env var.")
        print("  Example: python receiver.py --token my-secret-token")
        sys.exit(1)

    app.config["PHOTOSYNC_DIR"].mkdir(parents=True, exist_ok=True)
    photos_dir = app.config["PHOTOSYNC_DIR"]
    token = app.config["PHOTOSYNC_TOKEN"]

    print(f"""
╔══════════════════════════════════════════╗
║         PhotoSync Receiver              ║
╠══════════════════════════════════════════╣
║  Listening:  http://0.0.0.0:{args.port:<5}            ║
║  Photos dir: {str(photos_dir):<30} ║
║  Token:      {"*" * len(token):<30} ║
╚══════════════════════════════════════════╝
""")

    app.run(host=args.host, port=args.port, debug=args.debug)


if __name__ == "__main__":
    main()
