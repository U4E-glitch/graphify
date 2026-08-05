"""Index the mail Thunderbird has already downloaded.

Thunderbird signs in to Outlook with its own Microsoft registration and keeps a
copy of the mailbox on disk.  Reading that copy sidesteps the whole app
registration problem: nothing here talks to Microsoft at all, it just reads
files that are already on the machine.

Two on-disk formats appear in a profile, and both are handled by the standard
library's :mod:`mailbox`:

    Mail/Local Folders/Inbox          mbox  — one file holding every message
    ImapMail/outlook.office365.com/…  mbox  — the same, per IMAP account
    …/Inbox.sbd/Receipts              mbox  — subfolders live in a .sbd dir
    …/Inbox/cur/1699…                 maildir — one file per message

A folder is re-read only when its file has changed, so a second run over an
unchanged profile costs a stat() per folder.
"""

from __future__ import annotations

import email.parser
import email.utils
import hashlib
import mailbox
import os
import re
import sys
from collections.abc import Iterable, Iterator
from configparser import ConfigParser
from configparser import Error as ConfigParserError
from dataclasses import dataclass, field
from email.header import decode_header, make_header
from email.message import Message
from pathlib import Path

from .textutil import html_to_text

#: Files that sit beside mail folders but are not mail.
_SKIP_SUFFIXES = frozenset({
    ".msf", ".dat", ".html", ".json", ".sqlite", ".sqlite-wal", ".sqlite-shm",
    ".txt", ".ini", ".log", ".bak", ".tmp", ".xml", ".rdf", ".properties",
})
_SKIP_NAMES = frozenset({
    "msgFilterRules.dat", "filterlog.html", "rules.dat", "Junk", "msgfilterrules.dat",
})

#: Thunderbird's per-message status bits (X-Mozilla-Status).
_STATUS_READ = 0x0001
_STATUS_EXPUNGED = 0x0008

#: Folder names Thunderbird uses that should stay out of default results.
_LOW_SIGNAL = frozenset({
    "trash", "junk", "spam", "deleted items", "junk email", "bulk mail", "drafts",
})


@dataclass
class MailFolder:
    """One folder's worth of mail on disk."""

    path: Path
    display: str          # "outlook.office365.com/Inbox/Receipts"
    account: str
    kind: str             # "mbox" or "maildir"

    @property
    def id(self) -> str:
        return str(self.path)

    @property
    def name(self) -> str:
        return self.display.rsplit("/", 1)[-1]

    @property
    def is_low_signal(self) -> bool:
        return self.name.strip().lower() in _LOW_SIGNAL

    def fingerprint(self) -> str:
        """Cheap change detector: the folder is re-read only when this moves."""
        try:
            if self.kind == "maildir":
                # A maildir's mail lives in cur/ and new/; their mtimes move
                # whenever a message is added, removed or flagged.
                newest = 0.0
                count = 0
                for sub in ("cur", "new"):
                    directory = self.path / sub
                    if not directory.is_dir():
                        continue
                    with os.scandir(directory) as entries:
                        for entry in entries:
                            count += 1
                            newest = max(newest, entry.stat().st_mtime)
                return f"{newest:.0f}:{count}"
            stat = self.path.stat()
            return f"{stat.st_mtime:.0f}:{stat.st_size}"
        except OSError:
            return ""


@dataclass
class ScanResult:
    folders: list[MailFolder] = field(default_factory=list)
    profiles: list[Path] = field(default_factory=list)


# -- finding the profile --------------------------------------------------
def candidate_roots() -> list[Path]:
    """Where Thunderbird keeps profiles on each platform."""
    home = Path.home()
    if sys.platform == "darwin":
        return [home / "Library" / "Thunderbird"]
    if os.name == "nt":
        appdata = os.environ.get("APPDATA")
        roots = [Path(appdata) / "Thunderbird"] if appdata else []
        return roots + [home / "AppData" / "Roaming" / "Thunderbird"]
    return [
        home / ".thunderbird",
        home / ".mozilla-thunderbird",
        # Flatpak and Snap keep their own home directory.
        home / ".var" / "app" / "org.mozilla.Thunderbird" / ".thunderbird",
        home / "snap" / "thunderbird" / "common" / ".thunderbird",
    ]


def find_profiles(explicit: Path | str | None = None) -> list[Path]:
    """Profile directories, most likely first.

    ``profiles.ini`` names them, but a profile directory that simply contains a
    ``Mail`` or ``ImapMail`` folder is just as usable, so fall back to that.
    """
    if explicit:
        path = Path(explicit).expanduser()
        if _looks_like_profile(path):
            return [path]
        # Maybe they pointed at the root rather than a single profile.
        return sorted(child for child in _safe_iterdir(path) if _looks_like_profile(child))

    found: list[Path] = []
    for root in candidate_roots():
        if not root.is_dir():
            continue
        for profile in _profiles_from_ini(root):
            if profile not in found and _looks_like_profile(profile):
                found.append(profile)
        for child in sorted(_safe_iterdir(root)):
            if child not in found and _looks_like_profile(child):
                found.append(child)
    return found


def _profiles_from_ini(root: Path) -> list[Path]:
    ini = root / "profiles.ini"
    if not ini.is_file():
        return []
    parser = ConfigParser()
    try:
        parser.read(ini, encoding="utf-8")
    except (OSError, UnicodeDecodeError, ConfigParserError):
        return []

    default_first: list[Path] = []
    others: list[Path] = []
    for section in parser.sections():
        raw = parser.get(section, "Path", fallback="")
        if not raw:
            continue
        relative = parser.get(section, "IsRelative", fallback="1") == "1"
        path = (root / raw) if relative else Path(raw)
        if parser.get(section, "Default", fallback="0") == "1":
            default_first.append(path)
        else:
            others.append(path)
    return default_first + others


def _looks_like_profile(path: Path) -> bool:
    return path.is_dir() and any(
        (path / name).is_dir() for name in ("Mail", "ImapMail")
    )


def _safe_iterdir(path: Path) -> Iterable[Path]:
    try:
        return list(path.iterdir())
    except OSError:
        return []


# -- finding the folders --------------------------------------------------
def find_folders(profile: Path) -> list[MailFolder]:
    """Every mail folder inside one profile."""
    folders: list[MailFolder] = []
    for store in ("Mail", "ImapMail"):
        root = profile / store
        if not root.is_dir():
            continue
        for account_dir in sorted(_safe_iterdir(root)):
            if not account_dir.is_dir():
                continue
            account = account_dir.name
            folders.extend(_walk(account_dir, account, prefix=account))
    folders.sort(key=lambda folder: folder.display.lower())
    return folders


def _walk(directory: Path, account: str, prefix: str) -> Iterator[MailFolder]:
    for entry in sorted(_safe_iterdir(directory)):
        name = entry.name
        if name.startswith("."):
            continue

        if entry.is_dir():
            if name.endswith(".sbd"):
                # Subfolders of "Inbox" live in "Inbox.sbd".
                yield from _walk(entry, account, f"{prefix}/{name[:-4]}")
            elif (entry / "cur").is_dir():
                yield MailFolder(entry, f"{prefix}/{name}", account, "maildir")
            continue

        if name in _SKIP_NAMES or entry.suffix.lower() in _SKIP_SUFFIXES:
            continue
        try:
            if entry.stat().st_size == 0:
                continue
        except OSError:
            continue
        yield MailFolder(entry, f"{prefix}/{name}", account, "mbox")


# -- reading the mail -----------------------------------------------------
def read_messages(folder: MailFolder) -> Iterator[Message]:
    """Every message in a folder, skipping ones Thunderbird has deleted."""
    reader = _read_maildir if folder.kind == "maildir" else _read_mbox
    for message in reader(folder):
        if not _is_expunged(message):
            yield message


def _read_mbox(folder: MailFolder) -> Iterator[Message]:
    try:
        box = mailbox.mbox(str(folder.path), factory=None, create=False)
    except (OSError, ValueError):
        return
    try:
        for key in box.keys():
            try:
                yield box[key]
            except Exception:  # a torn mbox entry should not stop the folder
                continue
    finally:
        try:
            box.close()
        except Exception:  # pragma: no cover
            pass


def _read_maildir(folder: MailFolder) -> Iterator[Message]:
    """Read a maildir by hand.

    ``mailbox.Maildir`` insists on a ``new`` subdirectory, and Thunderbird's
    maildir folders only have ``cur`` and ``tmp`` — so reading the files
    directly is both simpler and more tolerant.
    """
    parser = email.parser.BytesParser()
    for sub in ("cur", "new"):
        directory = folder.path / sub
        if not directory.is_dir():
            continue
        for entry in sorted(_safe_iterdir(directory)):
            if not entry.is_file():
                continue
            try:
                with entry.open("rb") as handle:
                    yield parser.parse(handle)
            except (OSError, ValueError):
                continue


def _is_expunged(message: Message) -> bool:
    """True for messages deleted in Thunderbird but not yet compacted away."""
    return bool(_status_flags(message) & _STATUS_EXPUNGED)


def _status_flags(message: Message) -> int:
    raw = message.get("X-Mozilla-Status", "")
    try:
        return int(str(raw).strip(), 16)
    except (TypeError, ValueError):
        return 0


def decode(value: object) -> str:
    """Decode a possibly RFC 2047-encoded header into plain text."""
    if value is None:
        return ""
    text = str(value)
    if "=?" not in text:
        return " ".join(text.split())
    try:
        return " ".join(str(make_header(decode_header(text))).split())
    except (UnicodeDecodeError, LookupError, ValueError):
        return " ".join(text.split())


def _addresses(message: Message, header: str) -> str:
    raw = message.get_all(header)
    if not raw:
        return ""
    parts: list[str] = []
    for name, address in email.utils.getaddresses([decode(item) for item in raw]):
        if name and address and name.lower() != address.lower():
            parts.append(f"{name} <{address}>")
        elif address or name:
            parts.append(address or name)
    return ", ".join(parts)


def _first_address(message: Message, header: str) -> tuple[str, str]:
    raw = message.get_all(header)
    if not raw:
        return "", ""
    pairs = email.utils.getaddresses([decode(item) for item in raw])
    if not pairs:
        return "", ""
    name, address = pairs[0]
    return name, address


def body_and_attachments(message: Message) -> tuple[str, list[str]]:
    """The readable text of a message, plus the names of its attachments."""
    texts: list[str] = []
    html_fallback: list[str] = []
    attachments: list[str] = []

    for part in message.walk():
        if part.is_multipart():
            continue
        content_type = (part.get_content_type() or "").lower()
        disposition = (part.get_content_disposition() or "").lower()
        filename = decode(part.get_filename())

        if disposition == "attachment" or (filename and not content_type.startswith("text/")):
            if filename:
                attachments.append(filename)
            continue

        payload = _decoded_payload(part)
        if not payload:
            continue
        if content_type == "text/plain":
            texts.append(payload)
        elif content_type == "text/html":
            html_fallback.append(html_to_text(payload))

    body = "\n\n".join(texts).strip() or "\n\n".join(html_fallback).strip()
    return body, attachments


def _decoded_payload(part: Message) -> str:
    try:
        raw = part.get_payload(decode=True)
    except Exception:  # pragma: no cover - malformed encodings
        return ""
    if raw is None:
        payload = part.get_payload()
        return payload if isinstance(payload, str) else ""
    charset = part.get_content_charset() or "utf-8"
    try:
        return raw.decode(charset, errors="replace")
    except (LookupError, ValueError):
        return raw.decode("utf-8", errors="replace")


def message_id(message: Message, folder: MailFolder, ordinal: int) -> str:
    """A stable identity for a message, so re-reads update rather than duplicate."""
    raw = decode(message.get("Message-ID", "")).strip()
    if raw:
        return raw
    # No Message-ID (rare, but drafts and some senders omit it): fall back to a
    # digest of the parts that identify it, not the position in the file.
    digest = hashlib.sha256()
    for header in ("Date", "From", "To", "Subject"):
        digest.update(decode(message.get(header, "")).encode("utf-8", "replace"))
        digest.update(b"\x1f")
    digest.update(folder.display.encode("utf-8", "replace"))
    digest.update(str(ordinal).encode("ascii"))
    return f"<generated-{digest.hexdigest()[:32]}>"


_WHITESPACE = re.compile(r"\s+")


def normalize(message: Message, folder: MailFolder, ordinal: int = 0) -> dict[str, object]:
    """Turn a parsed email into the row shape the index stores."""
    body, attachments = body_and_attachments(message)
    from_name, from_address = _first_address(message, "From")
    received_ts, received_at = _timestamp(message)
    subject = decode(message.get("Subject", ""))

    # Attachment names are searchable text: "did anyone send me budget.xlsx?"
    searchable = body
    if attachments:
        searchable = f"{body}\n\n[attachments: {', '.join(attachments)}]"

    return {
        "id": message_id(message, folder, ordinal),
        "folder_id": folder.id,
        "folder_name": folder.display,
        "excluded": 1 if folder.is_low_signal else 0,
        "subject": subject,
        "from_name": from_name,
        "from_address": from_address,
        "to_recipients": _addresses(message, "To"),
        "cc_recipients": _addresses(message, "Cc"),
        "received_at": received_at,
        "received_ts": received_ts,
        "sent_at": received_at,
        "has_attachments": 1 if attachments else 0,
        "is_read": 1 if _status_flags(message) & _STATUS_READ else 0,
        "importance": _importance(message),
        "conversation_id": decode(message.get("In-Reply-To", "")) or "",
        # Thunderbird has no web link; the desktop app opens the message itself.
        "web_link": "",
        "preview": _WHITESPACE.sub(" ", body).strip()[:400],
        "body": searchable,
    }


def _timestamp(message: Message) -> tuple[int, str]:
    for header in ("Date", "Delivery-Date"):
        raw = message.get(header)
        if not raw:
            continue
        try:
            moment = email.utils.parsedate_to_datetime(str(raw))
        except (TypeError, ValueError):
            continue
        if moment is None:
            continue
        try:
            return int(moment.timestamp()), moment.isoformat()
        except (OverflowError, OSError, ValueError):
            continue
    return 0, ""


def _importance(message: Message) -> str:
    value = (message.get("Importance") or message.get("X-Priority") or "").strip().lower()
    if value.startswith("high") or value.startswith("1") or value.startswith("2"):
        return "high"
    if value.startswith("low") or value.startswith("4") or value.startswith("5"):
        return "low"
    return "normal"
