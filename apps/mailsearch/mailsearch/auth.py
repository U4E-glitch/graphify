"""OAuth 2.0 device authorization against Microsoft identity platform.

The device-code grant is the right fit for a local app: there is no client
secret to protect, no redirect URI to register, and the password is typed into
Microsoft's own page rather than anything this app renders.  The app receives a
short-lived access token plus a refresh token, and refreshes silently from then
on.

Flow (RFC 8628, as implemented by Microsoft):

1. POST /devicecode        -> user_code + verification_uri + device_code
2. user visits the URI, types the code, approves
3. POST /token (polling)   -> authorization_pending ... then the tokens
"""

from __future__ import annotations

import json
import threading
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .config import Config, ensure_data_dir
from .httpclient import HttpError, Response, UrllibTransport, post_form

DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

#: Refresh this many seconds before the token actually expires, so a long
#: request started just under the wire does not die halfway through.
EXPIRY_SKEW_SECONDS = 300


class AuthError(Exception):
    """Login failed, or the stored credentials cannot be used any more."""


class NotAuthenticated(AuthError):
    """No usable token: the user has to sign in."""


class AuthorizationPending(AuthError):
    """The user has not finished approving the code yet."""


@dataclass
class DeviceCode:
    """The half of the flow shown to the user."""

    user_code: str
    verification_uri: str
    device_code: str
    expires_at: float
    interval: int = 5
    message: str = ""

    @property
    def expires_in(self) -> int:
        return max(0, int(self.expires_at - time.time()))

    def public(self) -> dict[str, Any]:
        """The fields safe to hand to the browser (no ``device_code``)."""
        return {
            "user_code": self.user_code,
            "verification_uri": self.verification_uri,
            "expires_in": self.expires_in,
            "message": self.message,
        }


@dataclass
class Tokens:
    access_token: str
    refresh_token: str = ""
    expires_at: float = 0.0
    scope: str = ""
    account: str = ""

    @property
    def is_fresh(self) -> bool:
        return bool(self.access_token) and time.time() < self.expires_at - EXPIRY_SKEW_SECONDS

    def to_dict(self) -> dict[str, Any]:
        return {
            "access_token": self.access_token,
            "refresh_token": self.refresh_token,
            "expires_at": self.expires_at,
            "scope": self.scope,
            "account": self.account,
        }

    @classmethod
    def from_response(cls, payload: Mapping[str, Any], previous: Tokens | None = None) -> Tokens:
        expires_in = payload.get("expires_in")
        try:
            lifetime = float(expires_in)  # type: ignore[arg-type]
        except (TypeError, ValueError):
            lifetime = 3600.0
        return cls(
            access_token=str(payload.get("access_token", "")),
            # A refresh response may omit the refresh token, which means "keep
            # using the one you have".
            refresh_token=str(
                payload.get("refresh_token") or (previous.refresh_token if previous else "")
            ),
            expires_at=time.time() + lifetime,
            scope=str(payload.get("scope", "")),
            account=(previous.account if previous else ""),
        )


class TokenStore:
    """The token cache on disk, kept at mode 0600."""

    def __init__(self, path: Path) -> None:
        self.path = Path(path)
        self._lock = threading.Lock()

    def load(self) -> Tokens | None:
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if not isinstance(raw, dict):
            return None
        if not raw.get("refresh_token") and not raw.get("access_token"):
            return None
        return Tokens(
            access_token=str(raw.get("access_token", "")),
            refresh_token=str(raw.get("refresh_token", "")),
            expires_at=float(raw.get("expires_at") or 0.0),
            scope=str(raw.get("scope", "")),
            account=str(raw.get("account", "")),
        )

    def save(self, tokens: Tokens) -> None:
        with self._lock:
            ensure_data_dir(self.path.parent)
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(tokens.to_dict(), indent=2) + "\n", encoding="utf-8")
            try:
                tmp.chmod(0o600)
            except OSError:  # pragma: no cover
                pass
            tmp.replace(self.path)

    def clear(self) -> None:
        with self._lock:
            try:
                self.path.unlink()
            except FileNotFoundError:
                pass


class Authenticator:
    """Owns the tokens: starts logins, refreshes, and hands out access tokens."""

    def __init__(
        self,
        config: Config,
        transport: Any | None = None,
        store: TokenStore | None = None,
    ) -> None:
        self.config = config
        self.transport = transport or UrllibTransport()
        self.store = store or TokenStore(config.token_path)
        self._lock = threading.Lock()
        self._tokens: Tokens | None = self.store.load()

    # -- state ------------------------------------------------------------
    @property
    def is_signed_in(self) -> bool:
        tokens = self._tokens
        return bool(tokens and (tokens.refresh_token or tokens.is_fresh))

    @property
    def account(self) -> str:
        return self._tokens.account if self._tokens else ""

    def remember_account(self, account: str) -> None:
        with self._lock:
            if self._tokens and account and self._tokens.account != account:
                self._tokens.account = account
                self.store.save(self._tokens)

    def sign_out(self) -> None:
        with self._lock:
            self._tokens = None
            self.store.clear()

    # -- device code flow -------------------------------------------------
    def begin_device_login(self) -> DeviceCode:
        if not self.config.is_configured:
            raise AuthError(
                "No Azure application (client) ID configured. "
                "Run `mailsearch setup --client-id <id>` first — see the README."
            )
        response = post_form(
            self.transport,
            self.config.device_code_url,
            {"client_id": self.config.client_id, "scope": self.config.scope_string},
        )
        if not response.ok:
            raise AuthError(_friendly(HttpError(response)))
        payload = response.json() or {}
        try:
            interval = int(payload.get("interval", 5))
        except (TypeError, ValueError):
            interval = 5
        try:
            lifetime = float(payload.get("expires_in", 900))
        except (TypeError, ValueError):
            lifetime = 900.0
        return DeviceCode(
            user_code=str(payload.get("user_code", "")),
            verification_uri=str(
                payload.get("verification_uri") or payload.get("verification_url") or ""
            ),
            device_code=str(payload.get("device_code", "")),
            expires_at=time.time() + lifetime,
            interval=max(1, interval),
            message=str(payload.get("message", "")),
        )

    def poll_device_login(self, flow: DeviceCode) -> Tokens:
        """One polling attempt.  Raises :class:`AuthorizationPending` if early."""
        response = post_form(
            self.transport,
            self.config.token_url,
            {
                "grant_type": DEVICE_CODE_GRANT,
                "client_id": self.config.client_id,
                "device_code": flow.device_code,
            },
        )
        if response.ok:
            tokens = Tokens.from_response(response.json() or {})
            if not tokens.access_token:
                raise AuthError("Microsoft returned a login response with no access token.")
            self._remember(tokens)
            return tokens

        error = HttpError(response)
        code = error.code
        if code == "authorization_pending":
            raise AuthorizationPending("waiting for you to approve the code")
        if code == "slow_down":
            flow.interval += 5
            raise AuthorizationPending("polling too fast; slowing down")
        if code == "expired_token":
            raise AuthError("The sign-in code expired. Start the sign-in again.")
        if code == "authorization_declined":
            raise AuthError("Sign-in was declined in the browser.")
        raise AuthError(_friendly(error))

    def complete_device_login(
        self,
        flow: DeviceCode,
        *,
        sleep: Callable[[float], None] = time.sleep,
        now: Callable[[], float] = time.time,
    ) -> Tokens:
        """Poll until the user approves, the code expires, or login fails."""
        while True:
            if now() >= flow.expires_at:
                raise AuthError("The sign-in code expired. Start the sign-in again.")
            try:
                return self.poll_device_login(flow)
            except AuthorizationPending:
                sleep(flow.interval)

    # -- tokens -----------------------------------------------------------
    def access_token(self, *, force_refresh: bool = False) -> str:
        with self._lock:
            tokens = self._tokens
            if tokens is None:
                raise NotAuthenticated("Not signed in to Outlook yet.")
            if tokens.is_fresh and not force_refresh:
                return tokens.access_token
            if not tokens.refresh_token:
                raise NotAuthenticated("Session expired. Sign in to Outlook again.")
            return self._refresh_locked(tokens).access_token

    def _refresh_locked(self, tokens: Tokens) -> Tokens:
        response = post_form(
            self.transport,
            self.config.token_url,
            {
                "grant_type": "refresh_token",
                "client_id": self.config.client_id,
                "refresh_token": tokens.refresh_token,
                "scope": self.config.scope_string,
            },
        )
        if not response.ok:
            error = HttpError(response)
            if error.code in {"invalid_grant", "invalid_client", "unauthorized_client"}:
                # The refresh token was revoked, expired, or the app registration
                # changed.  Nothing to salvage; make the user sign in again.
                self._tokens = None
                self.store.clear()
                raise NotAuthenticated(
                    "Outlook sign-in is no longer valid. Please sign in again."
                )
            raise AuthError(_friendly(error))
        refreshed = Tokens.from_response(response.json() or {}, previous=tokens)
        if not refreshed.access_token:
            raise AuthError("Microsoft returned a refresh response with no access token.")
        refreshed.account = tokens.account
        self._tokens = refreshed
        self.store.save(refreshed)
        return refreshed

    def _remember(self, tokens: Tokens) -> None:
        with self._lock:
            if self._tokens and self._tokens.account:
                tokens.account = self._tokens.account
            self._tokens = tokens
            self.store.save(tokens)


def _friendly(error: HttpError) -> str:
    payload = error.payload
    description = payload.get("error_description")
    if isinstance(description, str) and "userAudience" in description:
        # The registration accepts personal Microsoft accounts only, so it has
        # to be asked at /consumers/ rather than the /common/ default.
        return (
            "This app registration is for personal Microsoft accounts only. Re-run "
            "setup with the matching endpoint:\n"
            "  mailsearch setup --client-id <your-id> --tenant consumers"
        )
    if isinstance(description, str) and description:
        # Microsoft's descriptions carry a trace id and timestamp on later
        # lines; the first line is the part a human needs.
        return description.splitlines()[0].strip()
    return str(error)


def describe_response(response: Response) -> str:  # pragma: no cover - debugging aid
    return f"{response.status} {response.body[:200]!r}"
