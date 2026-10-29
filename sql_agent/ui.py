"""Loopback HTTP transport and static assets for the local database workspace."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import secrets
from urllib.parse import parse_qs, urlsplit
import webbrowser

from .client import DatabaseError, json_text
from .workspace import Workspace

ASSETS = Path(__file__).with_name("web")
MAX_BODY = 256 * 1024


def strict_json(text):
    def invalid(value):
        raise ValueError(f"Invalid JSON number: {value}")
    return json.loads(text, parse_constant=invalid)


class WorkspaceServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, workspace, port=8765):
        self.workspace = workspace
        self.token = secrets.token_urlsafe(32)
        super().__init__(("127.0.0.1", port), WorkspaceHandler)
        self.origins = {f"http://127.0.0.1:{self.server_port}", f"http://localhost:{self.server_port}"}
        self.hosts = {urlsplit(origin).netloc for origin in self.origins}


class WorkspaceHandler(BaseHTTPRequestHandler):
    server_version = "SQLWorkspace"

    def log_message(self, *_):
        pass

    def _send(self, status, body, content_type="application/json; charset=utf-8"):
        encoded = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; "
                         "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        try:
            self.wfile.write(encoded)
        except (BrokenPipeError, ConnectionResetError):
            pass  # A disconnected browser never causes execution to be retried.

    def _json(self, data, status=200):
        self._send(status, json_text(data))

    def _authorized(self, post=False):
        if self.headers.get("Host") not in self.server.hosts:
            self._json({"ok": False, "error": {"code": "FORBIDDEN", "message": "Invalid local host"}}, 403)
            return False
        origin = self.headers.get("Origin")
        if origin is not None and origin not in self.server.origins:
            self._json({"ok": False, "error": {"code": "FORBIDDEN", "message": "Cross-origin access is not allowed"}}, 403)
            return False
        if post and not secrets.compare_digest(self.headers.get("X-Workspace-Token", "").encode("utf-8"), self.server.token.encode("ascii")):
            self._json({"ok": False, "error": {"code": "FORBIDDEN", "message": "Refresh this workspace before submitting"}}, 403)
            return False
        return True

    def do_GET(self):
        if not self._authorized():
            return
        address = urlsplit(self.path)
        assets = {"/": ("index.html", "text/html; charset=utf-8"),
                  "/app.css": ("app.css", "text/css; charset=utf-8"),
                  "/app.js": ("app.js", "text/javascript; charset=utf-8")}
        assets["/values.mjs"] = ("values.mjs", "text/javascript; charset=utf-8")
        if address.path in assets:
            name, content_type = assets[address.path]
            self._send(200, (ASSETS / name).read_bytes(), content_type)
            return
        try:
            arguments = parse_qs(address.query, keep_blank_values=True)
            workspace = self.server.workspace
            if address.path == "/api/status" and not arguments:
                data = {**workspace.status(), "token": self.server.token}
            elif address.path == "/api/tables" and not arguments:
                data = workspace.tables()
            elif address.path == "/api/schema" and set(arguments) == {"table"} and len(arguments["table"]) == 1:
                data = workspace.schema(arguments["table"][0])
            elif address.path == "/api/history" and not arguments:
                data = workspace.history()
            else:
                self._json({"ok": False, "error": {"code": "NOT_FOUND", "message": "Unknown workspace route"}}, 404)
                return
            self._json({"ok": True, "data": data})
        except (DatabaseError, OSError, ValueError, TypeError) as error:
            self._error(error)

    def do_POST(self):
        if not self._authorized(post=True):
            return
        try:
            if self.headers.get_content_type() != "application/json" or self.headers.get("Transfer-Encoding"):
                raise ValueError("Use an application/json request body")
            lengths = self.headers.get_all("Content-Length", [])
            if len(lengths) != 1 or not lengths[0].isdigit() or not 0 < int(lengths[0]) <= MAX_BODY:
                raise ValueError("Request must have a Content-Length between 1 and 256 KiB")
            self.connection.settimeout(10)
            data = strict_json(self.rfile.read(int(lengths[0])).decode("utf-8"))
            if not isinstance(data, dict):
                raise ValueError("Request must be a JSON object")
            workspace = self.server.workspace
            if self.path in {"/api/sql", "/api/plan"}:
                self._fields(data, {"sql", "parameters"}, {"sql"})
                parameters = data.get("parameters", [])
                # Keep large integer parameters exact: the browser can send the original JSON text.
                if isinstance(parameters, str):
                    parameters = strict_json(parameters)
                if not isinstance(parameters, list):
                    raise ValueError("Parameters must be a JSON array")
                result = workspace.query(data["sql"], parameters, explain=self.path == "/api/plan")
            elif self.path == "/api/preview":
                self._fields(data, {"table"}, {"table"})
                if not isinstance(data["table"], str):
                    raise ValueError("Table name must be text")
                result = workspace.preview(data["table"])
            elif self.path == "/api/ask":
                self._fields(data, {"question", "conversation", "answer"}, {"question", "conversation"})
                if not isinstance(data["question"], str):
                    raise ValueError("Question must be text")
                result = workspace.ask(data["question"], data["conversation"], answer=data.get("answer", "auto"))
            elif self.path == "/api/conversation/clear":
                self._fields(data, {"conversation"}, {"conversation"})
                if not isinstance(data["conversation"], str):
                    raise ValueError("Conversation ID must be text")
                result = workspace.clear_conversation(data["conversation"])
            else:
                self._json({"ok": False, "error": {"code": "NOT_FOUND", "message": "Unknown workspace route"}}, 404)
                return
            self._json({"ok": True, "data": result})
        except (DatabaseError, OSError, ValueError, TypeError) as error:
            self._error(error)
        except Exception:
            self._json({"ok": False, "error": {"code": "INTERNAL_ERROR", "message": "Workspace request failed; inspect state before retrying"}}, 500)

    @staticmethod
    def _fields(data, allowed, required):
        if set(data) - allowed or required - set(data):
            raise ValueError("Unexpected or missing request fields")

    def _error(self, error):
        code = getattr(error, "code", "INVALID_REQUEST")
        status = 409 if code in {"BUSY", "TRANSACTION_ACTIVE"} else 400
        self._json({"ok": False, "error": {"code": code, "message": str(error)}}, status)


def serve(home, config, *, port=8765, open_browser=False):
    if not 0 <= port <= 65535:
        raise ValueError("Port must be between 0 and 65535")
    workspace = Workspace(home, config)
    try:
        with WorkspaceServer(workspace, port) as server:
            url = f"http://127.0.0.1:{server.server_port}"
            print(f"AI SQL Engine workspace: {url}", flush=True)
            print("One local database session. Press Ctrl+C to close.", flush=True)
            if open_browser:
                webbrowser.open(url)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass
    finally:
        workspace.close()
