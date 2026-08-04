import pytest
from fakes import indexed_row

from mailsearch import query as q
from mailsearch.store import HIGHLIGHT_END, HIGHLIGHT_START, Store


@pytest.fixture()
def store():
    with Store(":memory:") as opened:
        opened.upsert_folder("inbox", "Inbox", path="Inbox")
        opened.upsert_folder("junk", "Junk Email", path="Junk Email", excluded=True)
        yield opened


def search(store, text, **kwargs):
    return store.search(q.parse(text), **kwargs)


def test_indexes_and_finds_a_word_from_the_body(store):
    store.upsert_messages([indexed_row("m1", "Hello", "the quarterly invoice is attached")])
    found = search(store, "invoice ")
    assert found["total"] == 1
    assert found["results"][0]["id"] == "m1"


def test_search_covers_subject_sender_recipients_and_body(store):
    store.upsert_messages([
        indexed_row("subject-hit", subject="Renewal notice", body="nothing else"),
        indexed_row("body-hit", subject="Hello", body="your renewal is due"),
        indexed_row("sender-hit", subject="Hi", body="x", from_name="Renewal Team"),
        indexed_row("recipient-hit", subject="Hi", body="x", to_recipients="renewal@example.com"),
    ])
    ids = {row["id"] for row in search(store, "renewal ")["results"]}
    assert ids == {"subject-hit", "body-hit", "sender-hit", "recipient-hit"}


def test_subject_matches_outrank_body_matches(store):
    store.upsert_messages([
        indexed_row("body", subject="Weekly note", body="mentions budget once " + "filler " * 200),
        indexed_row("subject", subject="Budget approved", body="short"),
    ])
    results = search(store, "budget ")["results"]
    assert [row["id"] for row in results] == ["subject", "body"]


def test_snippet_marks_the_matching_words(store):
    store.upsert_messages([indexed_row("m1", "Hi", "please pay the invoice by Friday")])
    snippet = search(store, "invoice ")["results"][0]["body_snippet"]
    assert HIGHLIGHT_START + "invoice" + HIGHLIGHT_END in snippet


def test_phrase_search_is_exact(store):
    store.upsert_messages([
        indexed_row("m1", "A", "a purchase order arrived"),
        indexed_row("m2", "B", "purchase of an order form"),
    ])
    results = search(store, '"purchase order" ')["results"]
    assert [row["id"] for row in results] == ["m1"]


def test_prefix_search_while_typing(store):
    store.upsert_messages([indexed_row("m1", "Invoices", "quarterly numbers")])
    assert search(store, "invoi")["total"] == 1
    assert search(store, "invoi ")["total"] == 0


def test_negation_excludes(store):
    store.upsert_messages([
        indexed_row("keep", "Invoice", "please pay"),
        indexed_row("drop", "Invoice", "this is a newsletter"),
    ])
    results = search(store, "invoice -newsletter ")["results"]
    assert [row["id"] for row in results] == ["keep"]


def test_negation_only_query_returns_everything_else(store):
    store.upsert_messages([
        indexed_row("keep", "Hello", "ordinary mail"),
        indexed_row("drop", "Hello", "unsubscribe from this newsletter"),
    ])
    results = search(store, "-newsletter ")["results"]
    assert [row["id"] for row in results] == ["keep"]


def test_filters_apply_without_any_text(store):
    store.upsert_messages([
        indexed_row("with", "A", "x", attachments=True),
        indexed_row("without", "B", "x"),
    ])
    results = search(store, "has:attachment ")["results"]
    assert [row["id"] for row in results] == ["with"]


def test_date_filters_use_received_time(store):
    store.upsert_messages([
        indexed_row("old", "A", "report", received_ts=1_700_000_000),
        indexed_row("new", "B", "report", received_ts=1_790_000_000),
    ])
    results = search(store, "report after:2026-01-01 ")["results"]
    assert [row["id"] for row in results] == ["new"]


def test_junk_is_hidden_unless_asked_for(store):
    store.upsert_messages([
        indexed_row("good", "Invoice", "real one"),
        indexed_row("junk", "Invoice", "spam one", folder="Junk Email", excluded=True),
    ])
    assert [row["id"] for row in search(store, "invoice ")["results"]] == ["good"]
    both = search(store, "invoice ", include_all=True)
    assert {row["id"] for row in both["results"]} == {"good", "junk"}


def test_folder_filter_reaches_into_excluded_folders(store):
    store.upsert_messages([
        indexed_row("junk", "Invoice", "spam one", folder="Junk Email", excluded=True),
    ])
    assert search(store, "invoice folder:junk ")["total"] == 1


def test_sort_orders(store):
    store.upsert_messages([
        indexed_row("old", "Report", "one", received_ts=1_700_000_000),
        indexed_row("new", "Report", "two", received_ts=1_790_000_000),
    ])
    newest = store.search(q.parse("report sort:newest "))["results"]
    oldest = store.search(q.parse("report sort:oldest "))["results"]
    assert [row["id"] for row in newest] == ["new", "old"]
    assert [row["id"] for row in oldest] == ["old", "new"]


def test_paging(store):
    store.upsert_messages([
        indexed_row(f"m{index}", "Report", "body", received_ts=1_700_000_000 + index)
        for index in range(10)
    ])
    first = search(store, "report ", limit=4, offset=0)
    second = search(store, "report ", limit=4, offset=4)
    assert first["total"] == 10
    assert len(first["results"]) == 4
    assert not {row["id"] for row in first["results"]} & {row["id"] for row in second["results"]}


def test_reindexing_a_message_replaces_the_old_text(store):
    store.upsert_messages([indexed_row("m1", "Draft", "original wording")])
    store.upsert_messages([indexed_row("m1", "Final", "revised wording")])
    assert search(store, "original ")["total"] == 0
    assert search(store, "revised ")["total"] == 1
    assert store.stats()["messages"] == 1


def test_deleting_removes_it_from_the_text_index_too(store):
    store.upsert_messages([indexed_row("m1", "Invoice", "payable now")])
    assert store.delete_messages(["m1"]) == 1
    assert search(store, "payable ")["total"] == 0
    assert store.stats()["messages"] == 0


def test_message_returns_the_full_body(store):
    body = "line one\nline two\n" + "long tail " * 200
    store.upsert_messages([indexed_row("m1", "Subject", body)])
    message = store.message("m1")
    assert message is not None
    assert message["body"] == body
    assert store.message("missing") is None


def test_facets_summarise_senders_and_folders(store):
    store.upsert_messages([
        indexed_row("a", "Invoice", "x", from_address="alice@example.com", from_name="Alice"),
        indexed_row("b", "Invoice", "x", from_address="alice@example.com", from_name="Alice"),
        indexed_row("c", "Invoice", "x", from_address="bob@example.com",
                    from_name="Bob", folder="Archive"),
    ])
    facets = store.facets(q.parse("invoice "))
    assert facets["senders"][0]["address"] == "alice@example.com"
    assert facets["senders"][0]["count"] == 2
    assert {entry["name"] for entry in facets["folders"]} == {"Inbox", "Archive"}


def test_dropping_a_folder_removes_its_mail(store):
    store.upsert_messages([indexed_row("m1", "Invoice", "gone soon", folder="Inbox")])
    store.drop_folders_missing_from(["junk"])
    assert search(store, "invoice ")["total"] == 0
    assert store.stats()["messages"] == 0


def test_stats_and_reset(store):
    store.upsert_messages([
        indexed_row("m1", "A", "x", received_ts=1_700_000_000),
        indexed_row("m2", "B", "y", received_ts=1_790_000_000),
    ])
    stats = store.stats()
    assert stats["messages"] == 2
    assert stats["oldest_ts"] == 1_700_000_000
    assert stats["newest_ts"] == 1_790_000_000

    store.reset_index()
    assert store.stats()["messages"] == 0
    assert store.folders()  # folders survive so a re-sync knows where to look


def test_a_query_full_of_punctuation_does_not_break_fts(store):
    store.upsert_messages([indexed_row("m1", "Quote", 'he said "hello" (loudly)')])
    for text in ['"hello" ', "((( ", "* ", "NEAR( ", 'AND OR "" ']:
        store.search(q.parse(text))  # must not raise


def test_search_persists_across_reopen(tmp_path):
    path = tmp_path / "index.sqlite3"
    with Store(path) as first:
        first.upsert_folder("inbox", "Inbox")
        first.upsert_messages([indexed_row("m1", "Invoice", "payable now")])
    with Store(path) as second:
        assert second.search(q.parse("payable "))["total"] == 1
