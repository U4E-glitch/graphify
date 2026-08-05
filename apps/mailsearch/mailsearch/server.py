"""The local web app: a small JSON API plus the static search UI.

By default it binds to 127.0.0.1.  Because a page on the internet can also
reach localhost, every API call must carry the ``X-Mailsearch`` header — a
custom header forces a CORS preflight that this server never approves — and
requests carrying a foreign ``Origin`` are refused outright.

It can also be opened up so a phone can reach it (over Tailscale, or a home
network).  That requires a passcode: connections from anywhere other than this
machine must unlock first and carry the resulting session cookie.
"""

from __future__ import annotations

import ipaddress
import json
import mimetypes
import threading
import time
import urllib.parse
from collections.abc import Callable
from dataclasses import dataclass
from functools import lru_cache, partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from . import access
from . import query as query_module
from .auth import Authenticator, AuthError, DeviceCode, NotAuthenticated
from .config import Config
from .graph import GraphClient, GraphError
from .store import Store
from .sync import GraphSyncer, ThunderbirdSyncer

WEB_ROOT = Path(__file__).parent / "web"
API_HEADER = "X-Mailsearch"
#: Names that mean "this machine". Any other *name* in a Host header is refused
#: (see Handler._host_allowed); bare IP addresses and Tailscale names are fine,
#: since those are how a phone legitimately reaches the app.
LOOPBACK_HOSTS = {"localhost", "127.0.0.1", "[::1]", "::1"}


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
        self.syncer = (
            ThunderbirdSyncer(self.store, profile=config.profile or None)
            if config.uses_thunderbird
            else GraphSyncer(self.store, self.graph)
        )
        self._login = LoginState()
        self._login_lock = threading.Lock()
        self.limiter = access.AttemptLimiter()

    # -- remote access ----------------------------------------------------
    @property
    def requires_passcode(self) -> bool:
        return self.config.has_passcode

    def expected_token(self) -> str:
        if not self.config.has_passcode or not self.config.session_secret:
            return ""
        return access.session_token(self.config.passcode, self.config.session_secret)

    def unlock(self, passcode: str, address: str) -> tuple[bool, str, float]:
        """Check a passcode. Returns (ok, session token, seconds to wait)."""
        wait = self.limiter.locked_for(address)
        if wait > 0:
            return False, "", wait
        if not access.verify_passcode(passcode, self.config.passcode):
            self.limiter.record_failure(address)
            return False, "", self.limiter.locked_for(address)
        self.limiter.clear(address)
        return True, self.expected_token(), 0.0

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
        thunderbird = self.config.uses_thunderbird
        return {
            "configured": self.config.is_configured,
            # Reading Thunderbird's files needs no Outlook session at all, so
            # the UI should never show a sign-in prompt for it.
            "signed_in": True if thunderbird else self.auth.is_signed_in,
            "source": self.config.source,
            "account": stats.get("account", "") if thunderbird
            else (self.auth.account or stats.get("account", "")),
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

    # -- access control ----------------------------------------------------
    @property
    def client_address_text(self) -> str:
        return self.client_address[0] if self.client_address else ""

    @property
    def from_this_machine(self) -> bool:
        return access.is_loopback(self.client_address_text)

    def _same_origin(self) -> bool:
        """The request must not have been sent by some other website."""
        origin = self.headers.get("Origin")
        if not origin:
            return True  # not a cross-origin request at all
        hostname = (urllib.parse.urlparse(origin).hostname or "").lower()
        host = (self.headers.get("Host") or "").rsplit(":", 1)[0].strip("[]").lower()
        # Our own page sends an Origin matching the address it was loaded from.
        return hostname == host or (hostname in LOOPBACK_HOSTS and host in LOOPBACK_HOSTS)

    def _unlocked(self) -> bool:
        """True when this request may see mail."""
        if self.from_this_machine:
            return True  # already on the machine holding the index
        if not self.app.requires_passcode:
            return False  # opened up without a passcode: refuse rather than leak
        expected = self.app.expected_token()
        return bool(expected) and self._cookie(access.COOKIE_NAME) == expected

    def _cookie(self, name: str) -> str:
        raw = self.headers.get("Cookie") or ""
        for part in raw.split(";"):
            key, _, value = part.strip().partition("=")
            if key == name:
                return value
        return ""

    def _host_allowed(self) -> bool:
        """Defend against DNS rebinding without pinning one address.

        A rebinding attack needs the victim's browser to keep sending the
        attacker's *domain name* in Host while the name resolves to this
        machine. Legitimate access always arrives as a bare IP address, a
        loopback name, or a Tailscale name — never as somebody's domain.
        """
        host = (self.headers.get("Host") or "").rsplit(":", 1)[0].strip("[]").lower()
        if not host or host in LOOPBACK_HOSTS:
            return True
        if host.endswith(".ts.net"):  # Tailscale's own DNS names
            return True
        if host == _machine_hostname():
            return True
        try:
            ipaddress.ip_address(host)
        except ValueError:
            return False
        return True

    def _guard(self) -> bool:
        """Refuse anything that did not come from our own page, or is locked."""
        if not self._host_allowed():
            self._error("Refused: unexpected Host header.", 403)
            return False
        if not self._same_origin():
            self._error("Refused: cross-origin request.", 403)
            return False
        if not self.headers.get(API_HEADER):
            self._error(f"Refused: missing {API_HEADER} header.", 403)
            return False
        if not self._unlocked():
            self._error("Locked. Enter the passcode to continue.", 401, needs_passcode=True)
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
        if path == "/api/lock":
            if not self._host_allowed() or not self._same_origin():
                return self._error("Refused.", 403)
            return self._json({
                "locked": not self._unlocked(),
                "passcode_set": self.app.requires_passcode,
                "local": self.from_this_machine,
            })
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
        if path == "/api/unlock":
            # Reachable while locked — it is how you stop being locked.
            if not self._host_allowed() or not self._same_origin() \
                    or not self.headers.get(API_HEADER):
                return self._error("Refused.", 403)
            return self._unlock(self._body())
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
    def _unlock(self, body: dict[str, Any]) -> None:
        if not self.app.requires_passcode:
            return self._error("No passcode is set on this computer.", 400)
        ok, token, wait = self.app.unlock(str(body.get("passcode") or ""),
                                          self.client_address_text)
        if not ok:
            if wait > 0:
                return self._error(
                    f"Too many wrong tries. Wait {int(wait) + 1} seconds.", 429,
                    retry_after=int(wait) + 1,
                )
            return self._error("That passcode is not right.", 401)

        body_out = json.dumps({"ok": True}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body_out)))
        self.send_header("Cache-Control", "no-store")
        # Not marked Secure: over Tailscale the transport is already encrypted,
        # and a Secure cookie would simply never be stored over plain http.
        self.send_header(
            "Set-Cookie",
            f"{access.COOKIE_NAME}={token}; Path=/; HttpOnly; SameSite=Lax; "
            f"Max-Age={access.SESSION_DAYS * 86400}",
        )
        self.end_headers()
        self.wfile.write(body_out)

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
            "connect-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'",
        )
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()
        self.wfile.write(body)


@lru_cache(maxsize=1)
def _machine_hostname() -> str:
    import socket

    try:
        return socket.gethostname().lower()
    except OSError:  # pragma: no cover
        return ""


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
    if not access.is_loopback(host) and not config.has_passcode:
        raise RemoteAccessRefused(
            f"Refusing to listen on {host} without a passcode — anyone who can reach "
            "this machine could read your mail.\nSet one first:  mailsearch passcode"
        )

    app = AppState(config)
    chosen = port or config.port
    server = ThreadingHTTPServer((host, chosen), partial(Handler, app))
    server.daemon_threads = True
    bound_port = server.server_address[1]
    url = f"http://{_display_host(host)}:{bound_port}/"

    if ready:
        ready(url)
    else:
        print(f"mailsearch is running at {url}")
        if not access.is_loopback(host):
            for address in reachable_addresses():
                print(f"  from your phone:  http://{address}:{bound_port}/")
            print("  (the passcode is asked for once per device)")
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


class RemoteAccessRefused(Exception):
    """Asked to listen beyond this machine with no passcode set."""


def _display_host(host: str) -> str:
    """0.0.0.0 is not somewhere you can point a browser."""
    return "localhost" if host in ("0.0.0.0", "::", "") else host


def reachable_addresses() -> list[str]:
    """Addresses this machine can be reached on, Tailscale first.

    Tailscale hands out 100.64.0.0/10 addresses, which work from anywhere the
    phone has signal; a 192.168/10./172.16 address only works on the same
    network. Listing them in that order puts the useful one first.
    """
    import socket

    found: list[str] = []
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
        found = sorted({info[4][0] for info in infos})
    except (OSError, UnicodeError):
        found = []

    try:  # the hostname does not always resolve to the Tailscale address
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("100.100.100.100", 80))  # Tailscale's own resolver
        found.append(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass

    def rank(address: str) -> int:
        first, _, rest = address.partition(".")
        second = int(rest.partition(".")[0] or 0)
        if first == "100" and 64 <= second <= 127:
            return 0  # Tailscale: reachable from anywhere
        if address.startswith("127."):
            return 3
        return 1

    unique = sorted(set(found), key=lambda address: (rank(address), address))
    return [address for address in unique if not address.startswith("127.")]


def _open_browser_later(url: str) -> None:  # pragma: no cover - user-facing nicety
    import webbrowser

    time.sleep(0.4)
    try:
        webbrowser.open(url)
    except Exception:
        pass
