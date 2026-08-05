import json
import time
import urllib.parse

import pytest
from fakes import FakeTransport

from mailsearch.auth import (
    Authenticator,
    AuthError,
    AuthorizationPending,
    NotAuthenticated,
    TokenStore,
)
from mailsearch.config import Config


@pytest.fixture()
def config(tmp_path):
    return Config(client_id="test-client", data_dir=tmp_path)


def form(data: bytes | None) -> dict[str, str]:
    return dict(urllib.parse.parse_qsl((data or b"").decode()))


DEVICE_CODE_REPLY = {
    "user_code": "ABCD-EFGH",
    "device_code": "device-123",
    "verification_uri": "https://microsoft.com/devicelogin",
    "expires_in": 900,
    "interval": 5,
    "message": "Go to the page and enter the code.",
}


def test_begin_device_login_asks_for_the_configured_scopes(config):
    seen = {}

    def handler(method, url, data, headers):
        seen.update(form(data))
        return 200, DEVICE_CODE_REPLY

    auth = Authenticator(config, transport=FakeTransport(handler))
    flow = auth.begin_device_login()

    assert flow.user_code == "ABCD-EFGH"
    assert flow.verification_uri.endswith("devicelogin")
    assert seen["client_id"] == "test-client"
    assert "Mail.Read" in seen["scope"] and "offline_access" in seen["scope"]
    # The device code itself is a secret; it must not reach the browser.
    assert "device_code" not in flow.public()


def test_login_without_a_client_id_explains_setup(tmp_path):
    auth = Authenticator(Config(data_dir=tmp_path), transport=FakeTransport(lambda *a: (200, {})))
    with pytest.raises(AuthError, match="client\\) ID"):
        auth.begin_device_login()


def test_polling_waits_for_approval_then_stores_the_tokens(config):
    replies = [
        (400, {"error": "authorization_pending"}),
        (400, {"error": "authorization_pending"}),
        (200, {"access_token": "at-1", "refresh_token": "rt-1", "expires_in": 3600}),
    ]
    calls = {"count": 0}

    def handler(method, url, data, headers):
        if url.endswith("devicecode"):
            return 200, DEVICE_CODE_REPLY
        reply = replies[min(calls["count"], len(replies) - 1)]
        calls["count"] += 1
        return reply

    auth = Authenticator(config, transport=FakeTransport(handler))
    flow = auth.begin_device_login()
    slept: list[float] = []
    tokens = auth.complete_device_login(flow, sleep=slept.append)

    assert tokens.access_token == "at-1"
    assert slept == [5, 5]  # honoured the interval between attempts
    assert auth.is_signed_in
    saved = json.loads(config.token_path.read_text())
    assert saved["refresh_token"] == "rt-1"
    # not readable by anyone else on the machine
    assert config.token_path.stat().st_mode & 0o077 == 0


def test_slow_down_backs_off(config):
    from mailsearch.auth import DeviceCode

    auth = Authenticator(config, transport=FakeTransport(lambda *a: (400, {"error": "slow_down"})))
    flow = DeviceCode("CODE", "https://example", "device", time.time() + 60, interval=5)
    with pytest.raises(AuthorizationPending):
        auth.poll_device_login(flow)
    assert flow.interval == 10


def test_expired_code_is_reported_clearly(config):
    from mailsearch.auth import DeviceCode

    transport = FakeTransport(lambda *a: (400, {"error": "expired_token"}))
    auth = Authenticator(config, transport=transport)

    flow = DeviceCode("CODE", "https://example", "device", time.time() + 60)
    with pytest.raises(AuthError, match="expired"):
        auth.poll_device_login(flow)


def test_a_fresh_token_is_reused_without_calling_microsoft(config):
    store = TokenStore(config.token_path)
    from mailsearch.auth import Tokens

    store.save(Tokens(access_token="still-good", refresh_token="rt", expires_at=time.time() + 3600))
    transport = FakeTransport(lambda *a: (500, {"error": "should not be called"}))

    auth = Authenticator(config, transport=transport)
    assert auth.access_token() == "still-good"
    assert transport.calls == []


def test_an_expired_token_is_refreshed_silently(config):
    from mailsearch.auth import Tokens

    TokenStore(config.token_path).save(
        Tokens(access_token="stale", refresh_token="rt-1", expires_at=time.time() - 10)
    )
    seen = {}

    def handler(method, url, data, headers):
        seen.update(form(data))
        return 200, {"access_token": "at-2", "refresh_token": "rt-2", "expires_in": 3600}

    auth = Authenticator(config, transport=FakeTransport(handler))
    assert auth.access_token() == "at-2"
    assert seen["grant_type"] == "refresh_token"
    assert seen["refresh_token"] == "rt-1"
    # The rotated refresh token replaces the old one on disk.
    assert json.loads(config.token_path.read_text())["refresh_token"] == "rt-2"


def test_a_refresh_response_without_a_new_refresh_token_keeps_the_old_one(config):
    from mailsearch.auth import Tokens

    TokenStore(config.token_path).save(
        Tokens(access_token="stale", refresh_token="rt-1", expires_at=0)
    )
    auth = Authenticator(
        config,
        transport=FakeTransport(lambda *a: (200, {"access_token": "at-2", "expires_in": 3600})),
    )
    auth.access_token()
    assert json.loads(config.token_path.read_text())["refresh_token"] == "rt-1"


def test_a_revoked_session_is_cleared_and_asks_for_a_new_login(config):
    from mailsearch.auth import Tokens

    TokenStore(config.token_path).save(
        Tokens(access_token="stale", refresh_token="revoked", expires_at=0)
    )
    auth = Authenticator(
        config,
        transport=FakeTransport(
            lambda *a: (
                400,
                {"error": "invalid_grant", "error_description": "AADSTS700082: expired"},
            )
        ),
    )
    with pytest.raises(NotAuthenticated, match="sign in again"):
        auth.access_token()
    assert not config.token_path.exists()
    assert not auth.is_signed_in


def test_access_token_without_any_login(config):
    auth = Authenticator(config, transport=FakeTransport(lambda *a: (200, {})))
    with pytest.raises(NotAuthenticated):
        auth.access_token()


def test_sign_out_forgets_the_tokens(config):
    from mailsearch.auth import Tokens

    TokenStore(config.token_path).save(Tokens(access_token="a", refresh_token="b", expires_at=0))
    auth = Authenticator(config, transport=FakeTransport(lambda *a: (200, {})))
    assert auth.is_signed_in
    auth.sign_out()
    assert not auth.is_signed_in
    assert not config.token_path.exists()


def test_a_personal_accounts_only_registration_is_explained(config):
    """Microsoft's userAudience complaint, turned into the command that fixes it."""
    description = (
        "AADSTS500200: The request is not valid for the application's 'userAudience' "
        "configuration. In order to use /common/ endpoint, the application must not be "
        "configured with 'Consumer' as the user audience."
    )
    auth = Authenticator(
        config,
        transport=FakeTransport(
            lambda *a: (400, {"error": "invalid_request", "error_description": description})
        ),
    )
    with pytest.raises(AuthError, match="--tenant consumers"):
        auth.begin_device_login()

