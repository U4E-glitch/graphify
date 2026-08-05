"""Reading Thunderbird's own mail files, with no Microsoft account involved."""

import time

import pytest

from mailsearch import query as q
from mailsearch import thunderbird
from mailsearch.store import Store
from mailsearch.sync import ThunderbirdSyncer

# A minimal but realistic mbox: two messages, the second one HTML with an
# attachment, exactly as Thunderbird would have written them.
MBOX = """From - Mon Jun 02 09:00:00 2026
X-Mozilla-Status: 0001
Message-ID: <invoice-1@contoso.com>
Date: Mon, 2 Jun 2026 09:00:00 +0000
From: Contoso Billing <billing@contoso.com>
To: Me <me@example.com>
Subject: Invoice 2026-041 from Contoso
Content-Type: text/plain; charset=utf-8

Dear customer,

Your invoice 2026-041 for 1,240.00 EUR is attached and payable by 30 June.

From - Tue Jun 03 11:30:00 2026
X-Mozilla-Status: 0000
Message-ID: <viewing-2@example.org>
Date: Tue, 3 Jun 2026 11:30:00 +0000
From: =?utf-8?q?Marta_Ru=C3=ADz?= <marta@example.org>
To: me@example.com
Cc: agent@example.org
Subject: =?utf-8?q?Re=3A_apartment_viewing_on_Saturday?=
Content-Type: multipart/mixed; boundary="BOUND"

--BOUND
Content-Type: text/html; charset=utf-8

<html><body><p>Saturday at 11:00 works.</p><p>Bring the <b>contract</b>.</p></body></html>
--BOUND
Content-Type: application/pdf; name="tenancy-contract.pdf"
Content-Disposition: attachment; filename="tenancy-contract.pdf"
Content-Transfer-Encoding: base64

JVBERi0xLjQK
--BOUND--
"""

RECEIPT = """From - Sat May 30 14:00:00 2026
X-Mozilla-Status: 0001
Message-ID: <receipt-3@shop.example>
Date: Sat, 30 May 2026 14:00:00 +0000
From: Shop <orders@shop.example>
To: me@example.com
Subject: Your receipt for order 88213
Content-Type: text/plain

Thank you for your order. Total 42.00 EUR.
"""

JUNK = """From - Fri May 29 03:00:00 2026
X-Mozilla-Status: 0000
Message-ID: <junk-4@spam.example>
Date: Fri, 29 May 2026 03:00:00 +0000
From: Prize Team <prizes@spam.example>
To: me@example.com
Subject: Your invoice is waiting, claim now
Content-Type: text/plain

Click here about your invoice.
"""

DELETED = """From - Mon Jun 02 09:00:00 2026
X-Mozilla-Status: 0009
Message-ID: <deleted-1@example.com>
Date: Mon, 2 Jun 2026 09:00:00 +0000
From: Someone <someone@example.com>
Subject: Already deleted
Content-Type: text/plain

This message is marked deleted and awaiting compaction.
"""


@pytest.fixture()
def profile(tmp_path):
    """A directory shaped like a Thunderbird profile."""
    root = tmp_path / "abc123.default-release"
    inbox_dir = root / "ImapMail" / "outlook.office365.com"
    inbox_dir.mkdir(parents=True)
    (inbox_dir / "INBOX").write_text(MBOX, encoding="utf-8")
    (inbox_dir / "INBOX.msf").write_text("index, not mail", encoding="utf-8")

    archive = inbox_dir / "INBOX.sbd"
    archive.mkdir()
    (archive / "Receipts").write_text(RECEIPT, encoding="utf-8")

    local = root / "Mail" / "Local Folders"
    local.mkdir(parents=True)
    (local / "Trash").write_text(JUNK, encoding="utf-8")
    return root


# -- discovery ------------------------------------------------------------
def test_a_profile_directory_is_recognised(profile):
    assert thunderbird.find_profiles(profile) == [profile]


def test_a_profile_is_found_under_a_root_directory(profile):
    assert thunderbird.find_profiles(profile.parent) == [profile]


def test_nothing_is_found_where_there_is_no_mail(tmp_path):
    assert thunderbird.find_profiles(tmp_path / "empty") == []


def test_profiles_ini_points_at_the_default_profile(tmp_path, profile):
    root = profile.parent
    (root / "profiles.ini").write_text(
        f"[Profile0]\nName=default\nIsRelative=1\nPath={profile.name}\nDefault=1\n",
        encoding="utf-8",
    )
    assert thunderbird.find_profiles(root)[0] == profile


def test_folders_are_found_including_nested_ones(profile):
    folders = {folder.display: folder for folder in thunderbird.find_folders(profile)}
    assert set(folders) == {
        "outlook.office365.com/INBOX",
        "outlook.office365.com/INBOX/Receipts",
        "Local Folders/Trash",
    }
    assert folders["outlook.office365.com/INBOX"].kind == "mbox"
    # Trash stays out of default results, the way Junk does for Outlook.
    assert folders["Local Folders/Trash"].is_low_signal
    assert not folders["outlook.office365.com/INBOX"].is_low_signal


def test_index_files_are_not_mistaken_for_mail(profile):
    assert not any(
        folder.path.name.endswith(".msf") for folder in thunderbird.find_folders(profile)
    )


def test_maildir_folders_are_recognised(tmp_path):
    root = tmp_path / "profile"
    folder = root / "Mail" / "Local Folders" / "Inbox"
    (folder / "cur").mkdir(parents=True)
    (folder / "tmp").mkdir()
    (folder / "cur" / "1699000000.mail").write_text(
        "Message-ID: <m1@example.com>\nSubject: Maildir message\n"
        "From: a@example.com\nDate: Mon, 2 Jun 2026 09:00:00 +0000\n\nBody text here.\n",
        encoding="utf-8",
    )
    folders = thunderbird.find_folders(root)
    assert [f.kind for f in folders] == ["maildir"]
    messages = list(thunderbird.read_messages(folders[0]))
    assert len(messages) == 1
    assert thunderbird.normalize(messages[0], folders[0])["subject"] == "Maildir message"


# -- parsing --------------------------------------------------------------
def inbox(profile):
    return next(
        folder for folder in thunderbird.find_folders(profile)
        if folder.display.endswith("/INBOX")
    )


def test_messages_are_read_from_an_mbox(profile):
    messages = list(thunderbird.read_messages(inbox(profile)))
    assert len(messages) == 2


def test_a_plain_message_becomes_an_index_row(profile):
    folder = inbox(profile)
    row = thunderbird.normalize(list(thunderbird.read_messages(folder))[0], folder)
    assert row["id"] == "<invoice-1@contoso.com>"
    assert row["subject"] == "Invoice 2026-041 from Contoso"
    assert row["from_name"] == "Contoso Billing"
    assert row["from_address"] == "billing@contoso.com"
    assert row["to_recipients"] == "Me <me@example.com>"
    assert "1,240.00 EUR" in str(row["body"])
    assert row["is_read"] == 1  # X-Mozilla-Status 0001
    assert row["has_attachments"] == 0
    assert row["received_ts"] > 0


def test_encoded_headers_are_decoded(profile):
    folder = inbox(profile)
    row = thunderbird.normalize(list(thunderbird.read_messages(folder))[1], folder)
    assert row["subject"] == "Re: apartment viewing on Saturday"
    assert row["from_name"] == "Marta Ruíz"
    assert row["cc_recipients"] == "agent@example.org"
    assert row["is_read"] == 0


def test_html_bodies_are_flattened_and_attachments_named(profile):
    folder = inbox(profile)
    row = thunderbird.normalize(list(thunderbird.read_messages(folder))[1], folder)
    body = str(row["body"])
    assert "Saturday at 11:00 works." in body
    assert "<b>" not in body
    assert row["has_attachments"] == 1
    # The file name is searchable text, so "tenancy" finds the message.
    assert "tenancy-contract.pdf" in body


def test_messages_deleted_in_thunderbird_are_skipped(tmp_path):
    root = tmp_path / "profile"
    directory = root / "Mail" / "Local Folders"
    directory.mkdir(parents=True)
    (directory / "Inbox").write_text(DELETED, encoding="utf-8")
    folder = thunderbird.find_folders(root)[0]
    assert list(thunderbird.read_messages(folder)) == []


def test_a_message_without_an_id_still_gets_a_stable_one(tmp_path):
    root = tmp_path / "profile"
    directory = root / "Mail" / "Local Folders"
    directory.mkdir(parents=True)
    (directory / "Inbox").write_text(
        "From - Mon Jun 02 09:00:00 2026\nFrom: a@example.com\nSubject: No id\n"
        "Date: Mon, 2 Jun 2026 09:00:00 +0000\n\nBody\n",
        encoding="utf-8",
    )
    folder = thunderbird.find_folders(root)[0]
    message = list(thunderbird.read_messages(folder))[0]
    first = thunderbird.normalize(message, folder, 0)["id"]
    second = thunderbird.normalize(message, folder, 0)["id"]
    assert first == second and first.startswith("<generated-")


# -- syncing --------------------------------------------------------------
def test_a_sync_indexes_everything_and_search_finds_it(profile, tmp_path):
    with Store(tmp_path / "index.sqlite3") as store:
        status = ThunderbirdSyncer(store, profile=str(profile)).run()

        assert status["error"] == ""
        assert status["phase"] == "done"
        # Two in INBOX, one in Receipts, one in Trash.
        assert status["indexed"] == 4
        # The junk copy is in Trash, which stays out of results by default.
        assert store.search(q.parse("invoice "))["total"] == 1
        assert store.search(q.parse("invoice "), include_all=True)["total"] == 2
        assert store.search(q.parse("receipt "))["total"] == 1
        # An attachment's file name is searchable even though its contents are not.
        assert store.search(q.parse("tenancy "))["total"] == 1


def test_a_second_sync_skips_folders_that_have_not_changed(profile, tmp_path):
    with Store(tmp_path / "index.sqlite3") as store:
        syncer = ThunderbirdSyncer(store, profile=str(profile))
        syncer.run()
        second = syncer.run()
        assert second["indexed"] == 0
        assert store.stats()["messages"] == 4


def test_new_mail_in_a_folder_is_picked_up(profile, tmp_path):
    with Store(tmp_path / "index.sqlite3") as store:
        syncer = ThunderbirdSyncer(store, profile=str(profile))
        syncer.run()

        mbox = profile / "ImapMail" / "outlook.office365.com" / "INBOX"
        with mbox.open("a", encoding="utf-8") as handle:
            handle.write(
                "\nFrom - Wed Jun 04 08:00:00 2026\nMessage-ID: <new-3@example.com>\n"
                "Date: Wed, 4 Jun 2026 08:00:00 +0000\nFrom: New Sender <new@example.com>\n"
                "Subject: Parking permit renewal\nContent-Type: text/plain\n\n"
                "Your parking permit is due for renewal.\n"
            )
        # Fingerprints are second-resolution; make sure the change is visible.
        future = time.time() + 2
        import os

        os.utime(mbox, (future, future))

        status = syncer.run()
        assert status["indexed"] == 3  # the changed folder is re-read in full
        assert store.search(q.parse("parking "))["total"] == 1


def test_mail_deleted_from_a_folder_leaves_the_index(profile, tmp_path):
    with Store(tmp_path / "index.sqlite3") as store:
        syncer = ThunderbirdSyncer(store, profile=str(profile))
        syncer.run()
        assert store.search(q.parse("apartment "))["total"] == 1

        mbox = profile / "ImapMail" / "outlook.office365.com" / "INBOX"
        first, _, _ = MBOX.partition("From - Tue Jun 03")
        mbox.write_text(first, encoding="utf-8")
        future = time.time() + 2
        import os

        os.utime(mbox, (future, future))

        status = syncer.run()
        assert status["removed"] == 1
        assert store.search(q.parse("apartment "))["total"] == 0


def test_a_missing_profile_is_reported_helpfully(tmp_path):
    with Store(tmp_path / "index.sqlite3") as store:
        status = ThunderbirdSyncer(store, profile=str(tmp_path / "nowhere")).run()
        assert status["phase"] == "failed"
        assert "Thunderbird" in status["error"]


def test_the_account_name_is_taken_from_the_folder_layout(profile, tmp_path):
    with Store(tmp_path / "index.sqlite3") as store:
        ThunderbirdSyncer(store, profile=str(profile)).run()
        assert store.stats()["account"] in {"outlook.office365.com", "Local Folders"}


def test_the_same_message_in_two_folders_is_indexed_once(profile, tmp_path):
    """A copy of a mail in Archive is the same mail, not a second result."""
    copy = profile / "ImapMail" / "outlook.office365.com" / "INBOX.sbd" / "Archive"
    copy.write_text(MBOX, encoding="utf-8")

    with Store(tmp_path / "index.sqlite3") as store:
        ThunderbirdSyncer(store, profile=str(profile)).run()
        found = store.search(q.parse("apartment "))
        assert found["total"] == 1
        assert found["results"][0]["folder_name"].endswith(("INBOX", "Archive"))
