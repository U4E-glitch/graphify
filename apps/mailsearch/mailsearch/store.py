"""The local index: SQLite plus an FTS5 full-text table.

Message metadata lives in ``messages``; the searchable text lives in
``messages_fts``.  The body is stored *only* in the FTS table — it is the
bulkiest field, FTS5 can produce snippets straight from it, and keeping one
copy halves the size of a mailbox on disk.

The two tables share a rowid (``messages.rid``), so updating or deleting a
message is a primary-key lookup rather than a scan.
"""

from __future__ import annotations

import sqlite3
import threading
import time
from collections.abc import Iterable, Mapping, Sequence
from pathlib import Path
from typing import Any

from .config import ensure_data_dir
from .query import SORT_NEWEST, SORT_OLDEST, Query

SCHEMA_VERSION = 1

#: Markers wrapped around matched words by FTS5. Control characters cannot
#: occur in real mail text, so the browser can swap them for <mark> after
#: escaping the surrounding text — snippets never carry raw HTML.
HIGHLIGHT_START = "\x02"
HIGHLIGHT_END = "\x03"

#: bm25 weights, one per FTS column: msg_id, subject, sender, recipients, body.
#: A hit in the subject line matters far more than one buried in a footer.
_BM25 = "bm25(messages_fts, 0.0, 12.0, 6.0, 3.0, 1.0)"

_MESSAGE_COLUMNS = (
    "id", "folder_id", "folder_name", "excluded", "subject", "from_name",
    "from_address", "to_recipients", "cc_recipients", "received_at",
    "received_ts", "sent_at", "has_attachments", "is_read", "importance",
    "conversation_id", "web_link", "preview",
)

_SELECT_FIELDS = """
    m.id, m.subject, m.from_name, m.from_address, m.to_recipients,
    m.received_at, m.received_ts, m.has_attachments, m.is_read,
    m.importance, m.folder_name, m.web_link, m.preview, m.conversation_id
"""

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT
);

CREATE TABLE IF NOT EXISTS folders (
    id             TEXT PRIMARY KEY,
    display_name   TEXT NOT NULL,
    path           TEXT NOT NULL DEFAULT '',
    parent_id      TEXT NOT NULL DEFAULT '',
    delta_link     TEXT NOT NULL DEFAULT '',
    excluded       INTEGER NOT NULL DEFAULT 0,
    last_synced_at INTEGER NOT NULL DEFAULT 0,
    last_seen_at   INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS messages (
    rid              INTEGER PRIMARY KEY AUTOINCREMENT,
    id               TEXT NOT NULL UNIQUE,
    folder_id        TEXT NOT NULL DEFAULT '',
    folder_name      TEXT NOT NULL DEFAULT '',
    excluded         INTEGER NOT NULL DEFAULT 0,
    subject          TEXT NOT NULL DEFAULT '',
    from_name        TEXT NOT NULL DEFAULT '',
    from_address     TEXT NOT NULL DEFAULT '',
    to_recipients    TEXT NOT NULL DEFAULT '',
    cc_recipients    TEXT NOT NULL DEFAULT '',
    received_at      TEXT NOT NULL DEFAULT '',
    received_ts      INTEGER NOT NULL DEFAULT 0,
    sent_at          TEXT NOT NULL DEFAULT '',
    has_attachments  INTEGER NOT NULL DEFAULT 0,
    is_read          INTEGER NOT NULL DEFAULT 1,
    importance       TEXT NOT NULL DEFAULT 'normal',
    conversation_id  TEXT NOT NULL DEFAULT '',
    web_link         TEXT NOT NULL DEFAULT '',
    preview          TEXT NOT NULL DEFAULT '',
    indexed_at       INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS messages_received_idx ON messages(received_ts DESC);
CREATE INDEX IF NOT EXISTS messages_folder_idx   ON messages(folder_id);
CREATE INDEX IF NOT EXISTS messages_sender_idx   ON messages(from_address);

CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
    msg_id UNINDEXED,
    subject,
    sender,
    recipients,
    body,
    tokenize = "unicode61 remove_diacritics 2"
);
"""


class Store:
    """Every read and write of the index goes through here."""

    def __init__(self, path: Path | str) -> None:
        self.path = Path(path)
        if str(self.path) != ":memory:":
            ensure_data_dir(self.path.parent)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(str(self.path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA synchronous=NORMAL")
        self._conn.executescript(SCHEMA)
        self._conn.commit()
        self.set_meta("schema_version", str(SCHEMA_VERSION))
        if str(self.path) != ":memory:":
            try:
                self.path.chmod(0o600)
            except OSError:  # pragma: no cover
                pass

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def __enter__(self) -> Store:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    # -- meta -------------------------------------------------------------
    def set_meta(self, key: str, value: str) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO meta(key, value) VALUES(?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, value),
            )
            self._conn.commit()

    def get_meta(self, key: str, default: str = "") -> str:
        with self._lock:
            row = self._conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else default

    # -- folders ----------------------------------------------------------
    def upsert_folder(
        self,
        folder_id: str,
        display_name: str,
        *,
        path: str = "",
        parent_id: str = "",
        excluded: bool = False,
    ) -> None:
        now = int(time.time())
        with self._lock:
            self._conn.execute(
                """
                INSERT INTO folders(id, display_name, path, parent_id, excluded, last_seen_at)
                VALUES(?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    display_name = excluded.display_name,
                    path         = excluded.path,
                    parent_id    = excluded.parent_id,
                    excluded     = excluded.excluded,
                    last_seen_at = excluded.last_seen_at
                """,
                (folder_id, display_name, path or display_name, parent_id, int(excluded), now),
            )
            self._conn.commit()

    def delta_link(self, folder_id: str) -> str:
        with self._lock:
            row = self._conn.execute(
                "SELECT delta_link FROM folders WHERE id = ?", (folder_id,)
            ).fetchone()
        return row["delta_link"] if row else ""

    def save_delta_link(self, folder_id: str, link: str) -> None:
        with self._lock:
            self._conn.execute(
                "UPDATE folders SET delta_link = ?, last_synced_at = ? WHERE id = ?",
                (link, int(time.time()), folder_id),
            )
            self._conn.commit()

    def clear_delta_links(self) -> None:
        with self._lock:
            self._conn.execute("UPDATE folders SET delta_link = ''")
            self._conn.commit()

    def folders(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                """
                SELECT f.id, f.display_name, f.path, f.excluded,
                       (SELECT COUNT(*) FROM messages m WHERE m.folder_id = f.id) AS message_count
                FROM folders f
                ORDER BY LOWER(f.path)
                """
            ).fetchall()
        return [dict(row) for row in rows]

    def drop_folders_missing_from(self, keep_ids: Iterable[str]) -> int:
        """Remove folders (and their mail) that no longer exist in Outlook."""
        keep = set(keep_ids)
        with self._lock:
            existing = [row["id"] for row in self._conn.execute("SELECT id FROM folders")]
            gone = [fid for fid in existing if fid not in keep]
            for folder_id in gone:
                rids = [
                    row["rid"]
                    for row in self._conn.execute(
                        "SELECT rid FROM messages WHERE folder_id = ?", (folder_id,)
                    )
                ]
                self._delete_rids(rids)
                self._conn.execute("DELETE FROM folders WHERE id = ?", (folder_id,))
            self._conn.commit()
        return len(gone)

    # -- messages ---------------------------------------------------------
    def upsert_messages(self, rows: Sequence[Mapping[str, Any]]) -> int:
        """Insert or replace a batch of messages.  Returns how many were written."""
        written = 0
        now = int(time.time())
        with self._lock:
            for row in rows:
                identifier = str(row.get("id") or "")
                if not identifier:
                    continue
                existing = self._conn.execute(
                    "SELECT rid FROM messages WHERE id = ?", (identifier,)
                ).fetchone()
                values = [_column_value(column, row.get(column)) for column in _MESSAGE_COLUMNS]
                if existing:
                    rid = existing["rid"]
                    assignments = ", ".join(f"{column} = ?" for column in _MESSAGE_COLUMNS)
                    self._conn.execute(
                        f"UPDATE messages SET {assignments}, indexed_at = ? WHERE rid = ?",
                        [*values, now, rid],
                    )
                    self._conn.execute("DELETE FROM messages_fts WHERE rowid = ?", (rid,))
                else:
                    placeholders = ", ".join("?" for _ in _MESSAGE_COLUMNS)
                    cursor = self._conn.execute(
                        f"INSERT INTO messages({', '.join(_MESSAGE_COLUMNS)}, indexed_at) "
                        f"VALUES({placeholders}, ?)",
                        [*values, now],
                    )
                    rid = int(cursor.lastrowid or 0)
                self._conn.execute(
                    "INSERT INTO messages_fts(rowid, msg_id, subject, sender, recipients, body) "
                    "VALUES(?, ?, ?, ?, ?, ?)",
                    (
                        rid,
                        identifier,
                        str(row.get("subject") or ""),
                        _sender_text(row),
                        _recipient_text(row),
                        str(row.get("body") or ""),
                    ),
                )
                written += 1
            self._conn.commit()
        return written

    def delete_messages(self, message_ids: Iterable[str]) -> int:
        ids = [mid for mid in message_ids if mid]
        if not ids:
            return 0
        with self._lock:
            rids: list[int] = []
            for chunk in _chunks(ids, 400):
                placeholders = ", ".join("?" for _ in chunk)
                rids.extend(
                    row["rid"]
                    for row in self._conn.execute(
                        f"SELECT rid FROM messages WHERE id IN ({placeholders})", chunk
                    )
                )
            removed = self._delete_rids(rids)
            self._conn.commit()
        return removed

    def _delete_rids(self, rids: Sequence[int]) -> int:
        for chunk in _chunks(list(rids), 400):
            placeholders = ", ".join("?" for _ in chunk)
            self._conn.execute(
                f"DELETE FROM messages_fts WHERE rowid IN ({placeholders})", chunk
            )
            self._conn.execute(f"DELETE FROM messages WHERE rid IN ({placeholders})", chunk)
        return len(rids)

    def message(self, message_id: str) -> dict[str, Any] | None:
        """One message with its full body, for the reading pane."""
        with self._lock:
            row = self._conn.execute(
                f"""
                SELECT {_SELECT_FIELDS}, m.cc_recipients, m.sent_at, m.folder_id,
                       (SELECT body FROM messages_fts WHERE rowid = m.rid) AS body
                FROM messages m WHERE m.id = ?
                """,
                (message_id,),
            ).fetchone()
        return dict(row) if row else None

    # -- search -----------------------------------------------------------
    def search(
        self,
        query: Query,
        *,
        limit: int = 25,
        offset: int = 0,
        include_all: bool = False,
    ) -> dict[str, Any]:
        """Run a parsed query and return ranked results plus a total count."""
        limit = max(1, min(int(limit), 200))
        offset = max(0, int(offset))
        where, params, joins_fts = self._compile(query, include_all)

        if joins_fts:
            source = "FROM messages_fts JOIN messages m ON m.rid = messages_fts.rowid"
            snippet = (
                f"snippet(messages_fts, 4, '{HIGHLIGHT_START}', '{HIGHLIGHT_END}', '…', 18)"
                " AS body_snippet, "
                f"snippet(messages_fts, 1, '{HIGHLIGHT_START}', '{HIGHLIGHT_END}', '', 24)"
                " AS subject_snippet, "
                f"{_BM25} AS score"
            )
        else:
            source = "FROM messages m"
            snippet = "'' AS body_snippet, '' AS subject_snippet, 0.0 AS score"

        by_date = "m.received_ts DESC"
        order = {
            SORT_NEWEST: by_date,
            SORT_OLDEST: "m.received_ts ASC",
        }.get(query.effective_sort, f"score ASC, {by_date}" if joins_fts else by_date)

        clause = f"WHERE {' AND '.join(where)}" if where else ""
        sql = (
            f"SELECT {_SELECT_FIELDS}, {snippet} {source} {clause} "
            f"ORDER BY {order} LIMIT ? OFFSET ?"
        )
        count_sql = f"SELECT COUNT(*) AS total {source} {clause}"

        with self._lock:
            total = int(self._conn.execute(count_sql, params).fetchone()["total"])
            rows = self._conn.execute(sql, [*params, limit, offset]).fetchall()

        return {
            "total": total,
            "limit": limit,
            "offset": offset,
            "results": [_result_row(row) for row in rows],
        }

    def facets(
        self, query: Query, *, include_all: bool = False, sample: int = 2000
    ) -> dict[str, Any]:
        """Top senders and folders across the matching set (sampled for speed)."""
        where, params, joins_fts = self._compile(query, include_all)
        source = (
            "FROM messages_fts JOIN messages m ON m.rid = messages_fts.rowid"
            if joins_fts
            else "FROM messages m"
        )
        clause = f"WHERE {' AND '.join(where)}" if where else ""
        base = (
            f"SELECT m.from_address, m.from_name, m.folder_name {source} {clause} "
            f"LIMIT {int(sample)}"
        )
        with self._lock:
            rows = self._conn.execute(base, params).fetchall()

        senders: dict[str, dict[str, Any]] = {}
        folders: dict[str, int] = {}
        for row in rows:
            address = (row["from_address"] or row["from_name"] or "").lower()
            if address:
                entry = senders.setdefault(
                    address, {"address": row["from_address"], "name": row["from_name"], "count": 0}
                )
                entry["count"] += 1
            folder = row["folder_name"] or ""
            if folder:
                folders[folder] = folders.get(folder, 0) + 1

        top_senders = sorted(senders.values(), key=lambda item: -item["count"])[:8]
        top_folders = [
            {"name": name, "count": count}
            for name, count in sorted(folders.items(), key=lambda item: -item[1])[:8]
        ]
        return {"senders": top_senders, "folders": top_folders, "sampled": len(rows)}

    def _compile(self, query: Query, include_all: bool) -> tuple[list[str], list[Any], bool]:
        where: list[str] = []
        params: list[Any] = []
        joins_fts = bool(query.fts)

        if joins_fts:
            where.append("messages_fts MATCH ?")
            params.append(query.fts)
        elif query.negative_fts:
            where.append(
                "m.rid NOT IN (SELECT rowid FROM messages_fts WHERE messages_fts MATCH ?)"
            )
            params.append(query.negative_fts)

        where.extend(query.where)
        params.extend(query.params)

        if not (include_all or query.include_all):
            where.append("m.excluded = 0")
        return where, params, joins_fts

    # -- stats ------------------------------------------------------------
    def stats(self) -> dict[str, Any]:
        with self._lock:
            row = self._conn.execute(
                """
                SELECT COUNT(*) AS total,
                       COALESCE(MIN(NULLIF(received_ts, 0)), 0) AS oldest,
                       COALESCE(MAX(received_ts), 0) AS newest
                FROM messages
                """
            ).fetchone()
            folder_count = int(
                self._conn.execute("SELECT COUNT(*) AS n FROM folders").fetchone()["n"]
            )
        return {
            "messages": int(row["total"]),
            "oldest_ts": int(row["oldest"]),
            "newest_ts": int(row["newest"]),
            "folders": folder_count,
            "last_sync": self.get_meta("last_sync_at"),
            "account": self.get_meta("account"),
        }

    def reset_index(self) -> None:
        """Throw away indexed mail, keeping folders so a full sync can restart."""
        with self._lock:
            self._conn.execute("DELETE FROM messages_fts")
            self._conn.execute("DELETE FROM messages")
            self._conn.execute("UPDATE folders SET delta_link = ''")
            self._conn.commit()
            self._conn.execute("VACUUM")


#: Columns stored as integers; everything else in the table is text.
_INT_COLUMNS = frozenset({"excluded", "received_ts", "has_attachments", "is_read"})


def _column_value(column: str, raw: Any) -> Any:
    """Fill in a default rather than letting a missing field fail the insert."""
    if column in _INT_COLUMNS:
        try:
            return int(raw)
        except (TypeError, ValueError):
            return 1 if column == "is_read" else 0
    return "" if raw is None else str(raw)


def _sender_text(row: Mapping[str, Any]) -> str:
    name = str(row.get("from_name") or "")
    address = str(row.get("from_address") or "")
    return f"{name} {address}".strip()


def _recipient_text(row: Mapping[str, Any]) -> str:
    return " ".join(
        part
        for part in (str(row.get("to_recipients") or ""), str(row.get("cc_recipients") or ""))
        if part
    )


def _result_row(row: sqlite3.Row) -> dict[str, Any]:
    data = dict(row)
    data["has_attachments"] = bool(data.get("has_attachments"))
    data["is_read"] = bool(data.get("is_read"))
    score = data.pop("score", 0.0) or 0.0
    # bm25 is negative, with better matches further from zero; flip it so the
    # number reads the way people expect.
    data["score"] = round(-float(score), 4)
    return data


def _chunks(items: list[Any], size: int) -> Iterable[list[Any]]:
    for start in range(0, len(items), size):
        yield items[start : start + size]
