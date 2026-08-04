"""The local web app: a small JSON API plus the static search UI.

It binds to 127.0.0.1 only.  Because a page on the internet can also reach
localhost, every API call must carry the ``X-Mailsearch`` header — a custom
header forces a CORS preflight that this server never approves — and requests
carrying a foreign ``Origin`` or ``Host`` are refused outright.
"""

from __future__ import annotations

import json
import mimetypes
import threading
import time
import urllib.parse
from collections.abc import Callable
from dataclasses import dataclass
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from . import query as query_module
from .auth import Authenticator, AuthError, DeviceCode, NotAuthenticated
from .config import Config
from .graph import GraphClient, GraphError
from .store import Store
from .sync import Syncer

WEB_ROOT = Path(__file__).parent / "web"
API_HEADER = "X-Mailsearch"
ALLOWED_HOSTS = {"localhost", "127.0.0.1", "[::1]", "::1"}


@dataclass
class LoginState:
    flow: DeviceCode | None = None
    state: str = "idle"  # idle | pending | complete | error
    error: str = ""
    account: str = ""


class AppState:
    """The long-lived objects shared by every request."""

    def __init__(self, config: Config, *, store: Store | None = None) -> None:
        self.config = config
        self.store = store or Store(config.index_path)
        self.auth = Authenticator(config)
        self.graph = GraphClient(config, self.auth)
        self.syncer = Syncer(self.store, self.graph)
        self._login = LoginState()
        self._login_lock = threading.Lock()

    # -- login ------------------------------------------------------------
    def begin_login(self) -> dict[str, Any]:
        with self._login_lock:
            pending = self._login.state == "pending" and self._login.flow
            if pending and self._login.flow.expires_in > 0:
                return self._login.flow.public()
            flow = self.auth.begin_device_login()
            self._login = LoginState(flow=flow, state="pending")
        threading.Thread(target=self._await_login, args=(flow,), daemon=True).start()
        return flow.public()

    def _await_login(self, flow: DeviceCode) -> None:
        try:
            self.auth.complete_device_login(flow)
        except AuthError as exc:
            with self._login_lock:
                self._login = LoginState(state="error", error=str(exc))
            return
        except Exception as exc:  # pragma: no cover - network oddities
            with self._login_lock:
                self._login = LoginState(state="error", error=str(exc))
            return

        account = ""
        try:
            account = self.graph.account_address()
        except (GraphError, NotAuthenticated):
            pass
        if account:
            self.auth.remember_account(account)
            self.store.set_meta("account", account)
        with self._login_lock:
            self._login = LoginState(state="complete", account=account)
        # First login: start pulling mail immediately so the index is useful
        # by the time the user has typed their first search.
        self.syncer.start(full=False)

    def login_status(self) -> dict[str, Any]:
        with self._login_lock:
            login = self._login
        payload: dict[str, Any] = {
            "state": login.state,
            "error": login.error,
            "account": login.account,
        }
        if login.flow and login.state == "pending":
            payload.update(login.flow.public())
        if self.auth.is_signed_in and login.state != "pending":
            payload["state"] = "complete"
        return payload

    def sign_out(self) -> None:
        self.syncer.cancel()
        self.auth.sign_out()
        with self._login_lock:
            self._login = LoginState()

    # -- status -----------------------------------------------------------
    def status(self) -> dict[str, Any]:
        stats = self.store.stats()
        return {
            "configured": self.config.is_configured,
            "signed_in": self.auth.is_signed_in,
            "account": self.auth.account or stats.get("account", ""),
            "index": stats,
            "sync": self.syncer.status,
            "client_id_hint": self.config.client_id[:8] + "…" if self.config.client_id else "",
        }

    def close(self) -> None:
        self.syncer.cancel()
        self.store.close()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "mailsearch"

    def __init__(self, app: AppState, *args: Any, **kwargs: Any) -> None:
        self.app = app
        super().__init__(*args, **kwargs)

    # -- plumbing ---------------------------------------------------------
    def log_message(self, fmt: str, *args: Any) -> None:  # noqa: A003 - stdlib hook
        return  # the UI is the log; keep the terminal clean

    def _json(self, payload: Any, status: int = 200) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _error(self, message: str, status: int = 400, **extra: Any) -> None:
        self._json({"error": message, **extra}, status=status)

    def _guard(self) -> bool:
        """Refuse anything that did not come from our own page."""
        host = (self.headers.get("Host") or "").split(":")[0]
        if host and host not in ALLOWED_HOSTS:
            self._error("Refused: unexpected Host header.", 403)
            return False
        origin = self.headers.get("Origin")
        if origin:
            hostname = urllib.parse.urlparse(origin).hostname or ""
            if hostname not in ALLOWED_HOSTS:
                self._error("Refused: cross-origin request.", 403)
                return False
        if not self.headers.get(API_HEADER):
            self._error(f"Refused: missing {API_HEADER} header.", 403)
            return False
        return True

    def _body(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return {}
        if length <= 0:
            return {}
        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return {}
        return payload if isinstance(payload, dict) else {}

    # -- routing ----------------------------------------------------------
    def do_GET(self) -> None:  # noqa: N802 - stdlib hook
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        params = urllib.parse.parse_qs(parsed.query)

        if path in ("/", "/index.html"):
            return self._static("index.html")
        if path.startswith("/static/"):
            return self._static(path[len("/static/"):])
        if path == "/favicon.ico":
            return self._static("favicon.svg")

        if not path.startswith("/api/"):
            return self._error("Not found", 404)
        if not self._guard():
            return

        try:
            if path == "/api/status":
                return self._json(self.app.status())
            if path == "/api/login":
                return self._json(self.app.login_status())
            if path == "/api/folders":
                return self._json({"folders": self.app.store.folders()})
            if path == "/api/search":
                return self._search(params)
            if path == "/api/message":
                return self._message(params)
        except NotAuthenticated as exc:
            return self._error(str(exc), 401, needs_login=True)
        except (GraphError, AuthError) as exc:
            return self._error(str(exc), 502)
        return self._error("Not found", 404)

    def do_POST(self) -> None:  # noqa: N802 - stdlib hook
        path = urllib.parse.urlparse(self.path).path
        if not path.startswith("/api/"):
            return self._error("Not found", 404)
        if not self._guard():
            return
        body = self._body()

        try:
            if path == "/api/login":
                return self._json(self.app.begin_login())
            if path == "/api/logout":
                self.app.sign_out()
                return self._json({"ok": True})
            if path == "/api/sync":
                if not self.app.auth.is_signed_in:
                    return self._error("Sign in to Outlook first.", 401, needs_login=True)
                started = self.app.syncer.start(full=bool(body.get("full")))
                return self._json({"started": started, "sync": self.app.syncer.status})
            if path == "/api/sync/cancel":
                self.app.syncer.cancel()
                return self._json({"ok": True})
            if path == "/api/reset":
                if self.app.syncer.is_running:
                    return self._error("A sync is running; stop it first.", 409)
                self.app.store.reset_index()
                return self._json({"ok": True})
        except AuthError as exc:
            return self._error(str(exc), 400)
        except GraphError as exc:
            return self._error(str(exc), 502)
        return self._error("Not found", 404)

    # -- endpoints --------------------------------------------------------
    def _search(self, params: dict[str, list[str]]) -> None:
        raw = _first(params, "q")
        parsed = query_module.parse(raw)
        sort = _first(params, "sort")
        if sort:
            parsed.sort = {"newest": "newest", "oldest": "oldest", "relevance": "relevance"}.get(
                sort, parsed.sort
            )
        include_all = _first(params, "all") in ("1", "true", "yes")

        if not raw.strip():
            # An empty box is not a search for everything; the UI shows its
            # own hint instead of a wall of mail.
            return self._json({
                "total": 0,
                "limit": _int(params, "limit", 25),
                "offset": 0,
                "results": [],
                "query": {"raw": raw, "terms": [], "empty": True},
            })

        started = time.perf_counter()
        payload = self.app.store.search(
            parsed,
            limit=_int(params, "limit", 25),
            offset=_int(params, "offset", 0),
            include_all=include_all,
        )
        payload["took_ms"] = round((time.perf_counter() - started) * 1000, 1)
        payload["query"] = {
            "raw": raw,
            "terms": parsed.terms,
            "fts": parsed.fts,
            "sort": parsed.effective_sort,
            "warnings": parsed.warnings,
            "empty": False,
        }
        if _first(params, "facets") in ("1", "true", "yes"):
            payload["facets"] = self.app.store.facets(parsed, include_all=include_all)
        return self._json(payload)

    def _message(self, params: dict[str, list[str]]) -> None:
        message_id = _first(params, "id")
        if not message_id:
            return self._error("Missing message id.", 400)
        message = self.app.store.message(message_id)
        if not message:
            return self._error("That message is not in the local index.", 404)
        return self._json(message)

    def _static(self, name: str) -> None:
        candidate = (WEB_ROOT / name).resolve()
        try:
            candidate.relative_to(WEB_ROOT.resolve())
        except ValueError:
            return self._error("Not found", 404)
        if not candidate.is_file():
            return self._error("Not found", 404)
        body = candidate.read_bytes()
        content_type = mimetypes.guess_type(candidate.name)[0] or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", f"{content_type}; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        # The UI is entirely self-contained; forbid anything external outright.
        self.send_header(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            "connect-src 'self'; base-uri 'none'; form-action 'none'",
        )
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()
        self.wfile.write(body)


def _first(params: dict[str, list[str]], key: str, default: str = "") -> str:
    values = params.get(key)
    return values[0] if values else default


def _int(params: dict[str, list[str]], key: str, default: int) -> int:
    try:
        return int(_first(params, key, str(default)))
    except ValueError:
        return default


def serve(
    config: Config,
    *,
    port: int | None = None,
    host: str = "127.0.0.1",
    open_browser: bool = True,
    ready: Callable[[str], None] | None = None,
) -> None:
    """Run the app until interrupted."""
    app = AppState(config)
    chosen = port or config.port
    server = ThreadingHTTPServer((host, chosen), partial(Handler, app))
    server.daemon_threads = True
    url = f"http://{host}:{server.server_address[1]}/"

    if ready:
        ready(url)
    else:
        print(f"mailsearch is running at {url}")
        print("Press Ctrl+C to stop.")

    if open_browser:
        threading.Thread(target=_open_browser_later, args=(url,), daemon=True).start()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping…")
    finally:
        server.shutdown()
        server.server_close()
        app.close()


def _open_browser_later(url: str) -> None:  # pragma: no cover - user-facing nicety
    import webbrowser

    time.sleep(0.4)
    try:
        webbrowser.open(url)
    except Exception:
        pass
