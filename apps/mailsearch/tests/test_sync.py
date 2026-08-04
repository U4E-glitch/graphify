import time

import pytest
from fakes import FakeTransport, graph_message

from mailsearch import query as q
from mailsearch.auth import Authenticator, Tokens, TokenStore
from mailsearch.config import Config
from mailsearch.graph import GraphClient
from mailsearch.store import Store
from mailsearch.sync import Syncer


class FakeMailbox:
    """A tiny stand-in for a mailbox behind the Graph endpoints."""

    def __init__(self, folders, pages):
        self.folders = folders          # [{"id":..., "displayName":...}]
        self.pages = pages              # {url_fragment: (status, payload)}
        self.seen: list[str] = []

    def __call__(self, method, url, data, headers):
        self.seen.append(url)
        if url.endswith("/me?$select=displayName%2Cmail%2CuserPrincipalName") or "/me?" in url:
            return 200, {"mail": "me@example.com"}
        if "/childFolders" in url:
            return 200, {"value": []}
        if "/mailFolders?" in url:
            return 200, {"value": self.folders}
        for fragment, reply in self.pages.items():
            if fragment in url:
                return reply
        return 200, {"value": [], "@odata.deltaLink": "https://graph/delta/none"}


@pytest.fixture()
def config(tmp_path):
    return Config(client_id="test-client", data_dir=tmp_path)


def build(config, mailbox):
    TokenStore(config.token_path).save(
        Tokens(access_token="at-1", refresh_token="rt-1", expires_at=time.time() + 3600)
    )
    transport = FakeTransport(mailbox)
    auth = Authenticator(config, transport=transport)
    graph = GraphClient(config, auth, transport=transport, sleep=lambda _: None)
    store = Store(config.index_path)
    return store, Syncer(store, graph)


def test_first_sync_indexes_every_folder(config):
    mailbox = FakeMailbox(
        folders=[
            {"id": "inbox", "displayName": "Inbox"},
            {"id": "archive", "displayName": "Archive"},
        ],
        pages={
            "mailFolders/inbox/messages/delta": (
                200,
                {
                    "value": [graph_message("m1", "Invoice", "please pay the invoice")],
                    "@odata.deltaLink": "https://graph/delta/inbox-1",
                },
            ),
            "mailFolders/archive/messages/delta": (
                200,
                {
                    "value": [graph_message("m2", "Old note", "archived text")],
                    "@odata.deltaLink": "https://graph/delta/archive-1",
                },
            ),
        },
    )
    store, syncer = build(config, mailbox)
    try:
        status = syncer.run()

        assert status["error"] == ""
        assert status["indexed"] == 2
        assert status["folders_done"] == 2
        assert store.search(q.parse("invoice "))["total"] == 1
        assert store.search(q.parse("archived "))["total"] == 1
        assert store.get_meta("account") == "me@example.com"
        assert store.get_meta("last_sync_at")
        assert store.delta_link("inbox") == "https://graph/delta/inbox-1"
    finally:
        store.close()


def test_paged_folders_are_followed_to_the_end(config):
    replies = [
        (200, {"value": [graph_message("m1", "One", "first page")],
               "@odata.nextLink": "https://graph/mailFolders/inbox/messages/delta?page=2"}),
        (200, {"value": [graph_message("m2", "Two", "second page")],
               "@odata.deltaLink": "https://graph/delta/inbox-1"}),
    ]

    class Paged(FakeMailbox):
        def __call__(self, method, url, data, headers):
            if "messages/delta" in url:
                self.seen.append(url)
                return replies.pop(0)
            return super().__call__(method, url, data, headers)

    store, syncer = build(config, Paged([{"id": "inbox", "displayName": "Inbox"}], {}))
    try:
        assert syncer.run()["indexed"] == 2
        assert store.stats()["messages"] == 2
    finally:
        store.close()


def test_second_sync_resumes_from_the_saved_delta_link(config):
    mailbox = FakeMailbox(
        folders=[{"id": "inbox", "displayName": "Inbox"}],
        pages={
            "messages/delta": (
                200,
                {"value": [graph_message("m1", "Hello", "body")],
                 "@odata.deltaLink": "https://graph/delta/inbox-1"},
            ),
            "delta/inbox-1": (
                200,
                {"value": [graph_message("m2", "Follow-up", "newer body")],
                 "@odata.deltaLink": "https://graph/delta/inbox-2"},
            ),
        },
    )
    store, syncer = build(config, mailbox)
    try:
        syncer.run()
        mailbox.seen.clear()
        second = syncer.run()

        assert second["indexed"] == 1  # only the new message came back
        assert any("delta/inbox-1" in url for url in mailbox.seen)
        assert store.delta_link("inbox") == "https://graph/delta/inbox-2"
        assert store.stats()["messages"] == 2
    finally:
        store.close()


def test_deletions_reported_by_delta_leave_the_index(config):
    mailbox = FakeMailbox(
        folders=[{"id": "inbox", "displayName": "Inbox"}],
        pages={
            "messages/delta": (
                200,
                {"value": [graph_message("m1", "Invoice", "payable")],
                 "@odata.deltaLink": "https://graph/delta/inbox-1"},
            ),
            "delta/inbox-1": (
                200,
                {"value": [{"id": "m1", "@removed": {"reason": "deleted"}}],
                 "@odata.deltaLink": "https://graph/delta/inbox-2"},
            ),
        },
    )
    store, syncer = build(config, mailbox)
    try:
        syncer.run()
        assert store.stats()["messages"] == 1
        status = syncer.run()
        assert status["removed"] == 1
        assert store.search(q.parse("payable "))["total"] == 0
    finally:
        store.close()


def test_a_stale_delta_token_restarts_that_folder(config):
    calls = {"n": 0}

    def mailbox(method, url, data, headers):
        if "/me?" in url:
            return 200, {"mail": "me@example.com"}
        if "/childFolders" in url:
            return 200, {"value": []}
        if "/mailFolders?" in url:
            return 200, {"value": [{"id": "inbox", "displayName": "Inbox"}]}
        if "delta/stale" in url:
            calls["n"] += 1
            return 410, {"error": {"code": "resyncRequired", "message": "token expired"}}
        return 200, {
            "value": [graph_message("m1", "Recovered", "found again")],
            "@odata.deltaLink": "https://graph/delta/inbox-fresh",
        }

    store, syncer = build(config, mailbox)
    try:
        store.upsert_folder("inbox", "Inbox")
        store.save_delta_link("inbox", "https://graph/delta/stale")
        status = syncer.run()

        assert calls["n"] == 1
        assert status["error"] == ""
        assert store.search(q.parse("recovered "))["total"] == 1
        assert store.delta_link("inbox") == "https://graph/delta/inbox-fresh"
    finally:
        store.close()


def test_a_mailbox_without_delta_support_falls_back_to_plain_listing(config):
    def mailbox(method, url, data, headers):
        if "/me?" in url:
            return 200, {"mail": "me@example.com"}
        if "/childFolders" in url:
            return 200, {"value": []}
        if "/mailFolders?" in url:
            return 200, {"value": [{"id": "inbox", "displayName": "Inbox"}]}
        if "messages/delta" in url:
            return 400, {"error": {"code": "BadRequest", "message": "delta not supported"}}
        return 200, {"value": [graph_message("m1", "Listed", "plain listing worked")]}

    store, syncer = build(config, mailbox)
    try:
        status = syncer.run()
        assert status["error"] == ""
        assert store.search(q.parse("listing "))["total"] == 1
    finally:
        store.close()


def test_folders_removed_in_outlook_are_dropped_locally(config):
    mailbox = FakeMailbox(folders=[{"id": "inbox", "displayName": "Inbox"}], pages={})
    store, syncer = build(config, mailbox)
    try:
        store.upsert_folder("gone", "Old Project")
        store.upsert_messages([
            {"id": "x1", "folder_id": "gone", "folder_name": "Old Project", "subject": "stale",
             "body": "obsolete text", "received_ts": 1, "excluded": 0}
        ])
        syncer.run()
        assert store.search(q.parse("obsolete "))["total"] == 0
        assert {folder["id"] for folder in store.folders()} == {"inbox"}
    finally:
        store.close()


def test_a_failing_sync_reports_the_error_instead_of_raising(config):
    def mailbox(method, url, data, headers):
        if "/me?" in url:
            return 200, {"mail": "me@example.com"}
        return 500, {"error": {"code": "ServiceUnavailable", "message": "try later"}}

    store, syncer = build(config, mailbox)
    try:
        status = syncer.run()
        assert status["running"] is False
        assert status["phase"] == "failed"
        assert "ServiceUnavailable" in status["error"] or "500" in status["error"]
    finally:
        store.close()


def test_a_revoked_session_asks_for_a_new_login(config):
    def mailbox(method, url, data, headers):
        if url.endswith("/token"):
            # Microsoft's answer once the refresh token has been revoked.
            return 400, {"error": "invalid_grant", "error_description": "AADSTS50173: revoked"}
        return 401, {"error": {"code": "InvalidAuthenticationToken"}}

    store, syncer = build(config, mailbox)
    try:
        status = syncer.run()
        assert status["needs_login"] is True
        assert status["phase"] == "failed"
    finally:
        store.close()


def test_background_sync_reports_progress_and_finishes(config):
    mailbox = FakeMailbox(
        folders=[{"id": "inbox", "displayName": "Inbox"}],
        pages={
            "messages/delta": (
                200,
                {"value": [graph_message("m1", "Hi", "hello there")],
                 "@odata.deltaLink": "https://graph/delta/inbox-1"},
            )
        },
    )
    store, syncer = build(config, mailbox)
    try:
        assert syncer.start() is True
        syncer.join(timeout=10)
        status = syncer.status
        assert status["running"] is False
        assert status["phase"] == "done"
        assert status["indexed"] == 1
        assert status["elapsed"] >= 0
    finally:
        store.close()
