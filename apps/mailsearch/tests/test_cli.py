import json

import pytest
from fakes import indexed_row

from mailsearch import cli
from mailsearch.config import load_config
from mailsearch.store import Store


@pytest.fixture()
def home(tmp_path, monkeypatch):
    monkeypatch.setenv("MAILSEARCH_HOME", str(tmp_path))
    monkeypatch.delenv("MAILSEARCH_CLIENT_ID", raising=False)
    return tmp_path


def run(*args) -> int:
    return cli.main(list(args))


def test_setup_writes_the_client_id(home, capsys):
    assert run("setup", "--client-id", "abc-123", "--tenant", "common") == 0
    saved = json.loads((home / "config.json").read_text())
    assert saved["client_id"] == "abc-123"
    assert saved["tenant"] == "common"
    assert "Saved settings" in capsys.readouterr().out


def test_config_file_is_not_world_readable(home):
    run("setup", "--client-id", "abc-123")
    assert (home / "config.json").stat().st_mode & 0o077 == 0


def test_environment_overrides_the_stored_client_id(home, monkeypatch):
    run("setup", "--client-id", "from-file")
    monkeypatch.setenv("MAILSEARCH_CLIENT_ID", "from-env")
    assert load_config(home).client_id == "from-env"


def test_commands_that_need_setup_explain_how(home, capsys):
    assert run("sync") == 2
    assert "mailsearch setup" in capsys.readouterr().err


def test_status_on_a_fresh_install(home, capsys):
    run("setup", "--client-id", "abc-123")
    assert run("status") == 0
    out = capsys.readouterr().out
    assert "signed in  : no" in out
    assert "messages   : 0" in out


def test_search_on_an_empty_index_says_so(home, capsys):
    assert run("search", "invoice") == 1
    assert "index is empty" in capsys.readouterr().err


def test_search_prints_matches(home, capsys):
    config = load_config(home)
    with Store(config.index_path) as store:
        store.upsert_folder("inbox", "Inbox")
        store.upsert_messages([
            indexed_row("m1", "Invoice 42", "please pay the invoice by Friday"),
            indexed_row("m2", "Lunch", "see you at noon"),
        ])

    assert run("search", "invoice") == 0
    out = capsys.readouterr().out
    assert "1 match" in out
    assert "Invoice 42" in out
    assert "Alice Adams" in out
    assert "Lunch" not in out


def test_search_accepts_the_full_query_syntax(home, capsys):
    config = load_config(home)
    with Store(config.index_path) as store:
        store.upsert_folder("inbox", "Inbox")
        store.upsert_messages([
            indexed_row("m1", "Invoice", "payable", attachments=True),
            indexed_row("m2", "Invoice", "payable"),
        ])

    assert run("search", "invoice", "has:attachment") == 0
    assert "1 match" in capsys.readouterr().out


def test_search_reports_no_matches_without_failing(home, capsys):
    config = load_config(home)
    with Store(config.index_path) as store:
        store.upsert_messages([indexed_row("m1", "Hello", "nothing relevant")])
    assert run("search", "zzzznotpresent") == 0
    assert "No messages match" in capsys.readouterr().out


def test_reset_clears_the_index(home, capsys):
    config = load_config(home)
    with Store(config.index_path) as store:
        store.upsert_messages([indexed_row("m1", "Hello", "text")])

    assert run("reset") == 0
    assert "Cleared 1" in capsys.readouterr().out
    with Store(config.index_path) as store:
        assert store.stats()["messages"] == 0


def test_logout_is_safe_when_never_logged_in(home, capsys):
    assert run("logout") == 0
    assert "Signed out" in capsys.readouterr().out


def test_version_flag(capsys):
    with pytest.raises(SystemExit) as exit_info:
        run("--version")
    assert exit_info.value.code == 0
    assert "mailsearch" in capsys.readouterr().out
