import json
import threading
import time
import urllib.error
import urllib.request
from functools import partial
from http.server import ThreadingHTTPServer

import pytest
from fakes import FakeTransport, indexed_row

from mailsearch.auth import Tokens, TokenStore
from mailsearch.config import Config
from mailsearch.server import AppState, Handler


@pytest.fixture()
def app(tmp_path):
    config = Config(client_id="test-client", data_dir=tmp_path)
    TokenStore(config.token_path).save(
        Tokens(access_token="at-1", refresh_token="rt-1", expires_at=time.time() + 3600)
    )
    state = AppState(config)
    # Nothing in these tests should reach the network.
    state.auth.transport = FakeTransport(lambda *a: (500, {"error": "unexpected network call"}))
    state.graph.transport = state.auth.transport
    state.store.upsert_folder("inbox", "Inbox", path="Inbox")
    state.store.upsert_messages([
        indexed_row("m1", "Invoice 42", "please pay the invoice by Friday",
                    received_ts=1_790_000_100),
        indexed_row("m2", "Lunch", "see you at noon",
                    from_name="Bob", from_address="bob@example.com"),
        indexed_row("junk1", "Invoice", "phishing attempt", folder="Junk Email", excluded=True),
    ])
    yield state
    state.close()


@pytest.fixture()
def client(app):
    server = ThreadingHTTPServer(("127.0.0.1", 0), partial(Handler, app))
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_address[1]}"

    def call(path, *, method="GET", body=None, headers=None, api=True):
        merged = {"X-Mailsearch": "1"} if api else {}
        merged.update(headers or {})
        data = json.dumps(body).encode() if body is not None else None
        if data is not None:
            merged["Content-Type"] = "application/json"
        request = urllib.request.Request(base + path, data=data, method=method, headers=merged)
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, response.read(), dict(response.headers)
        except urllib.error.HTTPError as error:
            return error.code, error.read(), dict(error.headers or {})

    call.base = base  # type: ignore[attr-defined]
    try:
        yield call
    finally:
        server.shutdown()
        server.server_close()


def payload(response):
    return json.loads(response[1].decode())


# -- the guard ------------------------------------------------------------
def test_api_calls_without_the_custom_header_are_refused(client):
    status, body, _ = client("/api/status", api=False)
    assert status == 403
    assert "X-Mailsearch" in json.loads(body)["error"]


def test_cross_origin_calls_are_refused(client):
    status, _, _ = client("/api/search?q=invoice", headers={"Origin": "https://evil.example"})
    assert status == 403


def test_a_foreign_host_header_is_refused(client):
    status, _, _ = client("/api/status", headers={"Host": "attacker.example"})
    assert status == 403


def test_same_origin_requests_are_allowed(client):
    status, _, _ = client("/api/status", headers={"Origin": client.base})
    assert status == 200


# -- static ---------------------------------------------------------------
def test_the_ui_is_served_at_the_root(client):
    status, body, headers = client("/", api=False)
    assert status == 200
    assert b"<title>Mail Search</title>" in body
    assert "default-src 'none'" in headers["Content-Security-Policy"]


def test_static_assets_load(client):
    for path in ("/static/app.js", "/static/styles.css", "/static/favicon.svg"):
        status, body, _ = client(path, api=False)
        assert status == 200 and body


def test_path_traversal_is_refused(client):
    status, _, _ = client("/static/../../../../etc/passwd", api=False)
    assert status in (400, 403, 404)


# -- endpoints ------------------------------------------------------------
def test_status_reports_the_index(client):
    data = payload(client("/api/status"))
    assert data["signed_in"] is True
    assert data["configured"] is True
    assert data["index"]["messages"] == 3
    assert data["sync"]["running"] is False


def test_search_returns_ranked_results(client):
    data = payload(client("/api/search?q=invoice&facets=1"))
    assert data["total"] == 1  # junk is excluded by default
    assert data["results"][0]["id"] == "m1"
    assert data["query"]["terms"] == ["invoice"]
    assert "took_ms" in data
    assert data["facets"]["senders"][0]["address"] == "alice@example.com"


def test_search_can_include_junk_and_deleted(client):
    data = payload(client("/api/search?q=invoice&all=1"))
    assert {row["id"] for row in data["results"]} == {"m1", "junk1"}


def test_search_supports_sorting_and_paging(client):
    first = payload(client("/api/search?q=e&limit=1&offset=0&sort=newest"))
    second = payload(client("/api/search?q=e&limit=1&offset=1&sort=newest"))
    assert first["total"] >= 2
    assert first["results"][0]["id"] != second["results"][0]["id"]


def test_an_empty_query_returns_no_results_rather_than_everything(client):
    data = payload(client("/api/search?q="))
    assert data["query"]["empty"] is True
    assert data["results"] == []


def test_message_returns_the_body(client):
    data = payload(client("/api/message?id=m1"))
    assert data["body"] == "please pay the invoice by Friday"
    assert data["subject"] == "Invoice 42"


def test_missing_message(client):
    status, body, _ = client("/api/message?id=nope")
    assert status == 404
    assert "not in the local index" in json.loads(body)["error"]


def test_folders_endpoint(client):
    data = payload(client("/api/folders"))
    assert any(folder["display_name"] == "Inbox" for folder in data["folders"])


def test_unknown_routes_are_404(client):
    assert client("/api/nope")[0] == 404
    assert client("/nope", api=False)[0] == 404


# -- actions --------------------------------------------------------------
def test_login_start_surfaces_the_user_code_but_not_the_device_code(client, app):
    app.auth.transport = FakeTransport(
        lambda *a: (
            200,
            {
                "user_code": "ABCD-EFGH",
                "device_code": "secret-device-code",
                "verification_uri": "https://microsoft.com/devicelogin",
                "expires_in": 900,
                "interval": 5,
            },
        )
    )
    body = client("/api/login", method="POST", body={})[1].decode()
    assert "ABCD-EFGH" in body
    assert "secret-device-code" not in body
    assert payload(client("/api/login"))["state"] in ("pending", "complete")


def test_logout_clears_the_session(client, app):
    assert client("/api/logout", method="POST", body={})[0] == 200
    assert payload(client("/api/status"))["signed_in"] is False


def test_sync_without_a_session_asks_for_login(client, app):
    app.auth.sign_out()
    status, body, _ = client("/api/sync", method="POST", body={})
    assert status == 401
    assert json.loads(body)["needs_login"] is True


def test_reset_empties_the_index(client, app):
    assert client("/api/reset", method="POST", body={})[0] == 200
    assert payload(client("/api/status"))["index"]["messages"] == 0
