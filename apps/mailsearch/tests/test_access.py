"""The passcode and session handling that guard remote access."""

import json
import threading
import urllib.error
import urllib.request
from functools import partial
from http.server import ThreadingHTTPServer

import pytest
from fakes import indexed_row

from mailsearch import access
from mailsearch import server as server_module
from mailsearch.config import SOURCE_THUNDERBIRD, Config
from mailsearch.server import AppState, Handler, RemoteAccessRefused, serve


# -- hashing --------------------------------------------------------------
def test_a_passcode_verifies_against_its_own_hash():
    stored = access.hash_passcode("open sesame")
    assert access.verify_passcode("open sesame", stored)
    assert not access.verify_passcode("Open Sesame", stored)
    assert not access.verify_passcode("", stored)


def test_the_passcode_is_not_recoverable_from_what_is_stored():
    stored = access.hash_passcode("hunter2hunter2")
    assert "hunter2" not in stored
    assert stored.startswith("pbkdf2$")


def test_the_same_passcode_hashes_differently_every_time():
    """A per-passcode salt, so two installs never share a hash."""
    assert access.hash_passcode("same one") != access.hash_passcode("same one")


def test_a_damaged_stored_hash_fails_closed():
    for broken in ("", "nonsense", "pbkdf2$notanumber$aa$bb", "sha1$1$aa$bb"):
        assert not access.verify_passcode("anything", broken)


def test_session_tokens_change_with_the_passcode():
    """Changing the passcode has to log every phone out."""
    secret = access.new_secret()
    first = access.session_token(access.hash_passcode("one"), secret)
    second = access.session_token(access.hash_passcode("two"), secret)
    assert first != second
    assert len(first) > 20


def test_loopback_recognises_this_machine():
    assert access.is_loopback("127.0.0.1")
    assert access.is_loopback("::1")
    assert not access.is_loopback("192.168.1.40")
    assert not access.is_loopback("100.101.102.103")


# -- guessing limits ------------------------------------------------------
def test_repeated_wrong_guesses_lock_an_address_out():
    limiter = access.AttemptLimiter(max_attempts=3, lockout_seconds=60)
    assert limiter.locked_for("10.0.0.9", now=1000) == 0
    for _ in range(3):
        limiter.record_failure("10.0.0.9", now=1000)
    assert limiter.locked_for("10.0.0.9", now=1000) == pytest.approx(60)
    # A different phone is unaffected.
    assert limiter.locked_for("10.0.0.8", now=1000) == 0


def test_the_lockout_expires():
    limiter = access.AttemptLimiter(max_attempts=2, lockout_seconds=60)
    limiter.record_failure("10.0.0.9", now=1000)
    limiter.record_failure("10.0.0.9", now=1000)
    assert limiter.locked_for("10.0.0.9", now=1030) == pytest.approx(30)
    assert limiter.locked_for("10.0.0.9", now=1061) == 0


def test_a_correct_passcode_clears_the_count():
    limiter = access.AttemptLimiter(max_attempts=2, lockout_seconds=60)
    limiter.record_failure("10.0.0.9", now=1000)
    limiter.clear("10.0.0.9")
    limiter.record_failure("10.0.0.9", now=1000)
    assert limiter.locked_for("10.0.0.9", now=1000) == 0


# -- serving --------------------------------------------------------------
def test_serving_beyond_this_machine_without_a_passcode_is_refused(tmp_path):
    config = Config(source=SOURCE_THUNDERBIRD, data_dir=tmp_path)
    with pytest.raises(RemoteAccessRefused, match="passcode"):
        serve(config, host="0.0.0.0", open_browser=False)


@pytest.fixture()
def locked_app(tmp_path):
    """An app with a passcode set, as it would be for phone access."""
    config = Config(
        source=SOURCE_THUNDERBIRD,
        data_dir=tmp_path,
        passcode=access.hash_passcode("correct horse"),
        session_secret="test-secret",
    )
    state = AppState(config)
    state.store.upsert_folder("inbox", "Inbox", path="Inbox")
    state.store.upsert_messages([indexed_row("m1", "Invoice 42", "please pay the invoice")])
    yield state
    state.close()


@pytest.fixture()
def remote(locked_app):
    """A client talking to a real running server over a real socket."""
    server = ThreadingHTTPServer(("127.0.0.1", 0), partial(Handler, locked_app))
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"

    def call(path, *, method="GET", body=None, headers=None):
        merged = {"X-Mailsearch": "1"}
        merged.update(headers or {})
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(base + path, data=data, method=method, headers=merged)
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read() or b"{}"), dict(response.headers)
        except urllib.error.HTTPError as error:
            raw = error.read()
            payload = json.loads(raw) if raw else {}
            return error.code, payload, dict(error.headers or {})

    call.base = base  # type: ignore[attr-defined]
    try:
        yield call
    finally:
        server.shutdown()
        server.server_close()


def test_the_lock_endpoint_says_whether_a_passcode_is_set(remote):
    status, payload, _ = remote("/api/lock")
    assert status == 200
    assert payload["passcode_set"] is True
    # These requests come from 127.0.0.1, which counts as this machine.
    assert payload["local"] is True
    assert payload["locked"] is False


def test_a_request_from_this_machine_is_not_locked(remote):
    status, payload, _ = remote("/api/status")
    assert status == 200
    assert payload["index"]["messages"] == 1


def test_unlocking_with_the_right_passcode_sets_a_session_cookie(remote):
    status, payload, headers = remote(
        "/api/unlock", method="POST", body={"passcode": "correct horse"}
    )
    assert status == 200 and payload["ok"] is True
    cookie = headers.get("Set-Cookie", "")
    assert access.COOKIE_NAME in cookie
    assert "HttpOnly" in cookie
    assert "SameSite=Lax" in cookie


def test_the_wrong_passcode_is_rejected(remote):
    status, payload, headers = remote(
        "/api/unlock", method="POST", body={"passcode": "wrong"}
    )
    assert status == 401
    assert "not right" in payload["error"]
    assert "Set-Cookie" not in headers


def test_guessing_repeatedly_is_slowed_down(remote):
    codes = [
        remote("/api/unlock", method="POST", body={"passcode": f"guess-{n}"})[0]
        for n in range(access.MAX_ATTEMPTS + 1)
    ]
    assert codes[-1] == 429


def test_a_foreign_origin_is_refused_even_with_a_valid_session(remote):
    status, _, _ = remote("/api/status", headers={"Origin": "https://evil.example"})
    assert status == 403


def test_a_domain_name_in_host_is_refused_but_an_address_is_not(remote):
    # DNS rebinding sends the attacker's *name*; a phone sends an IP or a
    # Tailscale name.
    assert remote("/api/status", headers={"Host": "attacker.example"})[0] == 403
    assert remote("/api/status", headers={"Host": "100.101.102.103:8765"})[0] == 200
    assert remote("/api/status", headers={"Host": "laptop.tailnet.ts.net"})[0] == 200


# -- as another device ----------------------------------------------------
@pytest.fixture()
def phone(remote, monkeypatch):
    """The same client, but the server now treats it as a remote device.

    Only the peer address distinguishes a phone from the machine itself, and a
    test cannot dial in from another address — so the check that reads it is
    the one thing stubbed out. Everything else is the real server.
    """
    monkeypatch.setattr(server_module.access, "is_loopback", lambda address: False)
    return remote


def test_a_phone_is_locked_out_until_it_unlocks(phone):
    status, payload, _ = phone("/api/status")
    assert status == 401
    assert payload["needs_passcode"] is True

    status, payload, _ = phone("/api/lock")
    assert status == 200 and payload["locked"] is True and payload["local"] is False


def test_a_phone_that_unlocks_can_search(phone):
    _, _, headers = phone("/api/unlock", method="POST", body={"passcode": "correct horse"})
    cookie = headers["Set-Cookie"].split(";")[0]

    status, payload, _ = phone("/api/status", headers={"Cookie": cookie})
    assert status == 200
    assert payload["index"]["messages"] == 1

    status, payload, _ = phone("/api/search?q=invoice", headers={"Cookie": cookie})
    assert status == 200
    assert payload["total"] == 1


def test_a_made_up_cookie_does_not_get_in(phone):
    status, _, _ = phone(
        "/api/status", headers={"Cookie": f"{access.COOKIE_NAME}=not-a-real-token"}
    )
    assert status == 401


def test_a_session_stops_working_once_the_passcode_changes(phone, locked_app):
    _, _, headers = phone("/api/unlock", method="POST", body={"passcode": "correct horse"})
    cookie = headers["Set-Cookie"].split(";")[0]
    assert phone("/api/status", headers={"Cookie": cookie})[0] == 200

    locked_app.config = locked_app.config.with_overrides(
        passcode=access.hash_passcode("a different one")
    )
    assert phone("/api/status", headers={"Cookie": cookie})[0] == 401


def test_a_server_opened_up_with_no_passcode_serves_nothing_remotely(tmp_path, monkeypatch):
    """Belt and braces behind the refusal to start: still no mail leaves."""
    monkeypatch.setattr(server_module.access, "is_loopback", lambda address: False)
    config = Config(source=SOURCE_THUNDERBIRD, data_dir=tmp_path)
    state = AppState(config)
    http = ThreadingHTTPServer(("127.0.0.1", 0), partial(Handler, state))
    threading.Thread(target=http.serve_forever, daemon=True).start()
    try:
        request = urllib.request.Request(
            f"http://127.0.0.1:{http.server_address[1]}/api/status",
            headers={"X-Mailsearch": "1"},
        )
        with pytest.raises(urllib.error.HTTPError) as raised:
            urllib.request.urlopen(request, timeout=10)
        assert raised.value.code == 401
    finally:
        http.shutdown()
        http.server_close()
        state.close()
