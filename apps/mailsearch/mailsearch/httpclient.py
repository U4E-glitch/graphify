"""A very small HTTP layer built on the standard library.

The whole application depends on nothing outside the Python standard library,
so requests go through :class:`UrllibTransport`.  Everything that talks to the
network takes a transport object, which keeps the tests offline: they pass a
fake that returns canned responses.

Transports never raise on an HTTP status.  A 400 from the token endpoint is a
normal part of the device-code polling loop, so callers decide what counts as
an error.  Only genuine transport failures (DNS, TLS, connection reset) raise.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

USER_AGENT = "mailsearch/0.1 (local email search)"

#: Statuses worth trying again: throttling plus the transient gateway errors.
RETRY_STATUSES = frozenset({429, 500, 502, 503, 504})


@dataclass
class Response:
    """An HTTP response with the body already read into memory."""

    status: int
    headers: dict[str, str] = field(default_factory=dict)
    body: bytes = b""
    url: str = ""

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300

    def json(self) -> Any:
        if not self.body:
            return None
        return json.loads(self.body.decode("utf-8", "replace"))

    def header(self, name: str, default: str | None = None) -> str | None:
        lowered = name.lower()
        for key, value in self.headers.items():
            if key.lower() == lowered:
                return value
        return default


class TransportError(Exception):
    """The request never produced an HTTP response."""


class HttpError(Exception):
    """A response came back, but with a status the caller could not use."""

    def __init__(self, response: Response, message: str | None = None) -> None:
        self.response = response
        self.status = response.status
        super().__init__(message or self._describe(response))

    @staticmethod
    def _describe(response: Response) -> str:
        detail = ""
        try:
            payload = response.json()
        except ValueError:
            payload = None
        if isinstance(payload, dict):
            error = payload.get("error")
            if isinstance(error, dict):
                detail = f"{error.get('code', '')}: {error.get('message', '')}".strip(": ")
            elif isinstance(error, str):
                detail = f"{error}: {payload.get('error_description', '')}".strip(": ")
        if not detail:
            detail = response.body[:200].decode("utf-8", "replace")
        return f"HTTP {response.status} for {response.url or '<url>'} — {detail}"

    @property
    def payload(self) -> dict[str, Any]:
        try:
            data = self.response.json()
        except ValueError:
            return {}
        return data if isinstance(data, dict) else {}

    @property
    def code(self) -> str:
        """The provider's machine-readable error code, if there is one."""
        payload = self.payload
        error = payload.get("error")
        if isinstance(error, str):
            return error
        if isinstance(error, dict):
            return str(error.get("code", ""))
        return ""


def build_url(url: str, params: Mapping[str, Any] | None = None) -> str:
    if not params:
        return url
    pairs: list[tuple[str, str]] = []
    for key, value in params.items():
        if value is None:
            continue
        if isinstance(value, (list, tuple)):
            pairs.extend((key, str(item)) for item in value)
        else:
            pairs.append((key, str(value)))
    if not pairs:
        return url
    joiner = "&" if "?" in url else "?"
    return url + joiner + urllib.parse.urlencode(pairs)


class UrllibTransport:
    """The real transport, backed by :mod:`urllib.request`."""

    def __init__(self, timeout: float = 60.0) -> None:
        self.timeout = timeout

    def request(
        self,
        method: str,
        url: str,
        *,
        headers: Mapping[str, str] | None = None,
        params: Mapping[str, Any] | None = None,
        data: bytes | None = None,
        timeout: float | None = None,
    ) -> Response:
        full_url = build_url(url, params)
        request = urllib.request.Request(full_url, data=data, method=method.upper())
        request.add_header("User-Agent", USER_AGENT)
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        try:
            with urllib.request.urlopen(request, timeout=timeout or self.timeout) as raw:
                return Response(
                    status=raw.status,
                    headers=dict(raw.headers.items()),
                    body=raw.read(),
                    url=full_url,
                )
        except urllib.error.HTTPError as exc:  # a response, just not a happy one
            return Response(
                status=exc.code,
                headers=dict(exc.headers.items()) if exc.headers else {},
                body=exc.read(),
                url=full_url,
            )
        except urllib.error.URLError as exc:
            raise TransportError(f"could not reach {full_url}: {exc.reason}") from exc
        except (TimeoutError, OSError) as exc:
            raise TransportError(f"could not reach {full_url}: {exc}") from exc


def post_form(
    transport: Any,
    url: str,
    fields: Mapping[str, str],
    *,
    headers: Mapping[str, str] | None = None,
) -> Response:
    """POST an ``application/x-www-form-urlencoded`` body."""
    body = urllib.parse.urlencode(fields).encode("utf-8")
    merged = {"Content-Type": "application/x-www-form-urlencoded"}
    merged.update(headers or {})
    return transport.request("POST", url, headers=merged, data=body)


def retry_after_seconds(response: Response, default: float) -> float:
    raw = response.header("Retry-After")
    if not raw:
        return default
    try:
        return max(0.0, float(raw.strip()))
    except ValueError:
        return default


def request_with_retries(
    transport: Any,
    method: str,
    url: str,
    *,
    headers: Mapping[str, str] | None = None,
    params: Mapping[str, Any] | None = None,
    data: bytes | None = None,
    attempts: int = 5,
    backoff: float = 1.0,
    max_backoff: float = 60.0,
    sleep: Callable[[float], None] = time.sleep,
    retry_statuses: Sequence[int] | frozenset[int] = RETRY_STATUSES,
) -> Response:
    """Issue a request, retrying throttling and transient transport failures.

    Microsoft Graph throttles aggressively during the first sync of a large
    mailbox and answers with 429 plus a ``Retry-After`` header, which is
    honoured here in preference to the computed backoff.
    """
    delay = backoff
    last_error: Exception | None = None
    for attempt in range(1, max(1, attempts) + 1):
        try:
            response = transport.request(
                method, url, headers=headers, params=params, data=data
            )
        except TransportError as exc:
            last_error = exc
            if attempt >= attempts:
                raise
        else:
            if response.status not in retry_statuses or attempt >= attempts:
                return response
            delay = retry_after_seconds(response, delay)
        sleep(min(delay, max_backoff))
        delay = min(delay * 2, max_backoff)
    raise last_error or TransportError(f"request to {url} failed")
