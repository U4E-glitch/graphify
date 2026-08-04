import time

import pytest
from fakes import FakeTransport, graph_message

from mailsearch.auth import Authenticator, NotAuthenticated, Tokens, TokenStore
from mailsearch.config import Config
from mailsearch.graph import (
    GraphClient,
    GraphError,
    MailFolder,
    normalize_message,
    parse_timestamp,
)


@pytest.fixture()
def config(tmp_path):
    return Config(client_id="test-client", data_dir=tmp_path)


def signed_in(config) -> Authenticator:
    TokenStore(config.token_path).save(
        Tokens(access_token="at-1", refresh_token="rt-1", expires_at=time.time() + 3600)
    )
    return Authenticator(config, transport=FakeTransport(lambda *a: (200, {})))


def client(config, handler, **kwargs) -> GraphClient:
    transport = FakeTransport(handler)
    graph = GraphClient(
        config, signed_in(config), transport=transport, sleep=lambda _: None, **kwargs
    )
    graph.test_transport = transport  # type: ignore[attr-defined]
    return graph


def test_requests_carry_the_bearer_token_and_ask_for_plain_text_bodies(config):
    seen = {}

    def handler(method, url, data, headers):
        seen.update(headers)
        return 200, {"value": []}

    graph = client(config, handler)
    graph.delta_page(graph.initial_delta_url("inbox"))
    assert seen["Authorization"] == "Bearer at-1"
    assert 'outlook.body-content-type="text"' in seen["Prefer"]
    assert "odata.maxpagesize=50" in seen["Prefer"]


def test_mail_folders_walks_into_child_folders(config):
    def handler(method, url, data, headers):
        if "/childFolders" in url and "inbox" in url:
            return 200, {"value": [
                {"id": "receipts", "displayName": "Receipts", "parentFolderId": "inbox",
                 "childFolderCount": 0},
            ]}
        if "/childFolders" in url:
            return 200, {"value": []}
        return 200, {"value": [
            {"id": "inbox", "displayName": "Inbox", "childFolderCount": 1},
            {"id": "junk", "displayName": "Junk Email", "childFolderCount": 0},
        ]}

    folders = client(config, handler).mail_folders()
    by_id = {folder.id: folder for folder in folders}
    assert set(by_id) == {"inbox", "junk", "receipts"}
    assert by_id["receipts"].path == "Inbox/Receipts"
    assert by_id["junk"].is_low_signal is True
    assert by_id["inbox"].is_low_signal is False


def test_folder_listing_follows_pagination(config):
    pages = {
        0: {"value": [{"id": "a", "displayName": "A"}], "@odata.nextLink": "https://graph/next"},
        1: {"value": [{"id": "b", "displayName": "B"}]},
    }
    calls = {"n": 0}

    def handler(method, url, data, headers):
        reply = pages[calls["n"]]
        calls["n"] += 1
        return 200, reply

    assert {folder.id for folder in client(config, handler).mail_folders()} == {"a", "b"}


def test_delta_page_splits_new_mail_from_deletions(config):
    def handler(method, url, data, headers):
        return 200, {
            "value": [
                graph_message("m1", "Kept"),
                {"id": "m2", "@removed": {"reason": "deleted"}},
            ],
            "@odata.deltaLink": "https://graph/delta-token",
        }

    page = client(config, handler).delta_page("https://graph/delta")
    assert [message["id"] for message in page.messages] == ["m1"]
    assert page.removed_ids == ["m2"]
    assert page.delta_link == "https://graph/delta-token"


def test_throttling_is_retried_and_then_succeeds(config):
    from mailsearch.httpclient import Response

    replies = [
        Response(status=429, headers={"Retry-After": "0"}, body=b"{}"),
        Response(status=200, body=b'{"value": []}'),
    ]

    def handler(method, url, data, headers):
        return replies.pop(0)

    graph = client(config, handler)
    graph.delta_page("https://graph/delta")
    assert len(graph.test_transport.calls) == 2


def test_a_401_triggers_one_refresh_before_giving_up(config):
    calls = {"n": 0}

    def handler(method, url, data, headers):
        if url.endswith("/token"):
            return 200, {"access_token": "at-2", "expires_in": 3600}
        calls["n"] += 1
        return 401, {"error": {"code": "InvalidAuthenticationToken"}}

    auth = signed_in(config)
    transport = FakeTransport(handler)
    auth.transport = transport
    graph = GraphClient(config, auth, transport=transport, sleep=lambda _: None)

    with pytest.raises(NotAuthenticated):
        graph.me()
    assert calls["n"] == 2  # tried again with a fresh token, then surfaced the error


def test_other_errors_keep_their_status_for_the_sync_fallbacks(config):
    reply = (410, {"error": {"code": "resyncRequired", "message": "gone"}})
    graph = client(config, lambda *a: reply)
    with pytest.raises(GraphError) as raised:
        graph.delta_page("https://graph/delta")
    assert raised.value.status == 410
    assert raised.value.code == "resyncRequired"


def test_account_address_prefers_the_mailbox_over_the_login_name(config):
    profile = (200, {"mail": "me@example.com", "userPrincipalName": "me@corp"})
    graph = client(config, lambda *a: profile)
    assert graph.account_address() == "me@example.com"


# -- normalisation --------------------------------------------------------
INBOX = MailFolder(id="inbox", display_name="Inbox", path="Inbox")
JUNK = MailFolder(id="junk", display_name="Junk Email", path="Junk Email")


def test_normalize_flattens_a_graph_message():
    message = graph_message("m1", "Invoice 42", "please pay", attachments=True)
    row = normalize_message(message, INBOX)
    assert row["id"] == "m1"
    assert row["subject"] == "Invoice 42"
    assert row["from_name"] == "Alice Adams"
    assert row["from_address"] == "alice@example.com"
    assert row["to_recipients"] == "Me <me@example.com>"
    assert row["body"] == "please pay"
    assert row["has_attachments"] == 1
    assert row["folder_name"] == "Inbox"
    assert row["excluded"] == 0
    assert row["received_ts"] == parse_timestamp("2026-05-01T09:00:00Z")


def test_normalize_marks_low_signal_folders():
    assert normalize_message(graph_message("m1"), JUNK)["excluded"] == 1


def test_html_bodies_are_reduced_to_readable_text():
    html = (
        "<html><head><style>p{color:red}</style></head><body>"
        "<p>Hello <b>Bob</b>,</p><p>The invoice is attached.</p>"
        "<script>alert(1)</script></body></html>"
    )
    row = normalize_message(graph_message("m1", "Hi", html, content_type="html"), INBOX)
    assert "Hello Bob," in row["body"]
    assert "The invoice is attached." in row["body"]
    assert "color:red" not in row["body"]
    assert "alert(1)" not in row["body"]
    assert "<" not in row["body"]


def test_markup_hiding_under_a_text_content_type_is_still_stripped():
    html = "<div><p>Hello <b>Bob</b>,</p><p>Signed, Alice</p></div>"
    row = normalize_message(graph_message("m1", "Hi", html, content_type="text"), INBOX)
    assert row["body"] == "Hello Bob,\n\nSigned, Alice"


def test_plain_text_containing_a_stray_angle_bracket_is_left_alone():
    text = "Costs < 500 EUR, and the reply is due 5 > 3 days from now."
    row = normalize_message(graph_message("m1", "Hi", text, content_type="text"), INBOX)
    assert row["body"] == text


def test_missing_fields_do_not_break_normalisation():
    row = normalize_message({"id": "m1"}, INBOX)
    assert row["subject"] == ""
    assert row["from_address"] == ""
    assert row["received_ts"] == 0


def test_timestamp_parsing():
    from datetime import datetime, timezone

    expected = int(datetime(2026, 5, 1, 9, 0, tzinfo=timezone.utc).timestamp())
    assert parse_timestamp("2026-05-01T09:00:00Z") == expected
    # Graph sometimes uses an explicit offset instead of the Z suffix.
    assert parse_timestamp("2026-05-01T11:00:00+02:00") == expected
    assert parse_timestamp("nonsense") == 0
    assert parse_timestamp(None) == 0
