"""The passcode that guards the app when it is reachable beyond this computer.

Bound to localhost the app needs no lock: anyone who can reach it already has
your files.  The moment it listens on an address a phone can reach, that stops
being true, so opening it up requires a passcode, and every request from a
non-loopback address has to carry a valid session cookie.

The passcode is stored as a PBKDF2-SHA256 hash, and the session cookie is an
HMAC over that hash with a per-install secret — so changing the passcode
invalidates every session that was ever handed out.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import threading
import time
from base64 import urlsafe_b64encode
from dataclasses import dataclass, field

#: Cost of hashing the passcode. High enough that guessing at it is slow, low
#: enough to be unnoticeable when unlocking.
PBKDF2_ROUNDS = 240_000
SALT_BYTES = 16
COOKIE_NAME = "mailsearch_session"
SESSION_DAYS = 30

#: Failed-attempt limits, per client address.
MAX_ATTEMPTS = 6
LOCKOUT_SECONDS = 300

LOOPBACK = frozenset({"127.0.0.1", "::1", "::ffff:127.0.0.1", "localhost"})


def hash_passcode(passcode: str, *, salt: bytes | None = None) -> str:
    """Hash a passcode for storage: ``pbkdf2$rounds$salt$digest``."""
    if not passcode:
        return ""
    salt = salt or os.urandom(SALT_BYTES)
    digest = hashlib.pbkdf2_hmac("sha256", passcode.encode("utf-8"), salt, PBKDF2_ROUNDS)
    return "$".join(["pbkdf2", str(PBKDF2_ROUNDS), _b64(salt), _b64(digest)])


def verify_passcode(passcode: str, stored: str) -> bool:
    """Constant-time check of a passcode against its stored hash."""
    if not passcode or not stored:
        return False
    try:
        scheme, rounds, salt, digest = stored.split("$")
        if scheme != "pbkdf2":
            return False
        candidate = hashlib.pbkdf2_hmac(
            "sha256", passcode.encode("utf-8"), _unb64(salt), int(rounds)
        )
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(candidate, _unb64(digest))


def session_token(passcode_hash: str, secret: str) -> str:
    """The one valid cookie value for this passcode and install.

    Deriving it rather than storing a session list means unlocking survives a
    restart of the app, and changing the passcode revokes every phone at once.
    """
    return _b64(
        hmac.new(secret.encode("utf-8"), passcode_hash.encode("utf-8"), hashlib.sha256).digest()
    )


def new_secret() -> str:
    return secrets.token_urlsafe(32)


def is_loopback(address: str) -> bool:
    return (address or "").strip("[]") in LOOPBACK


@dataclass
class AttemptLimiter:
    """Slows down guessing at the passcode, per client address."""

    max_attempts: int = MAX_ATTEMPTS
    lockout_seconds: float = LOCKOUT_SECONDS
    _failures: dict[str, list[float]] = field(default_factory=dict)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def locked_for(self, address: str, *, now: float | None = None) -> float:
        """Seconds until this address may try again; 0 when it may try now."""
        moment = now if now is not None else time.time()
        with self._lock:
            recent = self._recent(address, moment)
            if len(recent) < self.max_attempts:
                return 0.0
            return max(0.0, self.lockout_seconds - (moment - recent[0]))

    def record_failure(self, address: str, *, now: float | None = None) -> None:
        moment = now if now is not None else time.time()
        with self._lock:
            recent = self._recent(address, moment)
            recent.append(moment)
            self._failures[address] = recent

    def clear(self, address: str) -> None:
        with self._lock:
            self._failures.pop(address, None)

    def _recent(self, address: str, now: float) -> list[float]:
        window = now - self.lockout_seconds
        return [stamp for stamp in self._failures.get(address, []) if stamp > window]


def _b64(raw: bytes) -> str:
    return urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _unb64(text: str) -> bytes:
    from base64 import urlsafe_b64decode

    padding = "=" * (-len(text) % 4)
    return urlsafe_b64decode(text + padding)
