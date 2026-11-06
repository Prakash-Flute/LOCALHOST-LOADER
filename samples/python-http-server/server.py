"""Minimal Python HTTP server package for Localhost Loader."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import sys

PORT = int(os.environ.get("PORT", "8000"))
HOST = os.environ.get("HOST", "127.0.0.1")
APP_NAME = os.environ.get("LOCALHOST_APP_NAME", "Python Local Server")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        payload = {
            "app": APP_NAME,
            "runtime": "Python",
            "python": sys.version.split()[0],
            "path": self.path,
            "message": "The embedded Python runtime is serving this app locally.",
        }
        if self.path == "/api/info":
            body = json.dumps(payload).encode("utf-8")
            content_type = "application/json; charset=utf-8"
        else:
            body = (
                "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                "<title>Python Local Server</title><style>body{font:16px system-ui;background:#0a1119;color:#eef7f5;"
                "padding:32px;max-width:760px;margin:auto}main{background:#12202d;padding:24px;border-radius:18px}"
                "pre{white-space:pre-wrap;overflow-wrap:anywhere;color:#70e0c0}</style></head><body><main>"
                "<h1>Python runtime is running</h1><p>This page is served by embedded CPython.</p>"
                "<pre id=\"info\">Loading runtime details…</pre></main>"
                "<script>fetch('/api/info').then(r=>r.json()).then(x=>info.textContent=JSON.stringify(x,null,2))</script>"
                "</body></html>"
            ).encode("utf-8")
            content_type = "text/html; charset=utf-8"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("[python-sample] " + (fmt % args))


if __name__ == "__main__":
    print(f"Starting {APP_NAME} on {HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
