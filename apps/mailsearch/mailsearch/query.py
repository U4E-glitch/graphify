"""Turn what a person types into an FTS5 expression plus SQL filters.

The syntax is deliberately the one people already know from Gmail and Outlook:

    invoice acme                  both words, anywhere in the mail
    "purchase order"              exact phrase
    from:alice invoice            narrowed to a sender
    subject:renewal -spam         subject match, excluding a word
    invoice OR receipt            either one
    has:attachment after:2024-01  filters that never touch the text index
    tax*                          prefix match

The last bare word is prefix-matched while the query is still being typed
(no trailing space), so results narrow as you type without needing a ``*``.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any

# from:alice  |  subject:"end of year"  |  "quoted phrase"  |  bare-word
_TOKEN_RE = re.compile(
    r"""
    (?P<neg>-)?
    (?:
        (?P<field>[A-Za-z_]+):(?:"(?P<fvalue_q>[^"]*)"|(?P<fvalue>[^\s"]*))
      | "(?P<phrase>[^"]*)"
      | (?P<word>\S+)
    )
    """,
    re.VERBOSE,
)

#: Query field -> full-text column it searches.
_TEXT_FIELDS = {
    "from": "sender",
    "sender": "sender",
    "to": "recipients",
    "recipient": "recipients",
    "recipients": "recipients",
    "cc": "recipients",
    "subject": "subject",
    "title": "subject",
    "body": "body",
    "content": "body",
}

_DATE_AFTER = {"after", "since", "newer"}
_DATE_BEFORE = {"before", "until", "older"}

_RELATIVE_UNITS = {"d": 1, "w": 7, "m": 30, "y": 365}

SORT_RELEVANCE = "relevance"
SORT_NEWEST = "newest"
SORT_OLDEST = "oldest"
_SORTS = {
    "relevance": SORT_RELEVANCE,
    "best": SORT_RELEVANCE,
    "date": SORT_NEWEST,
    "newest": SORT_NEWEST,
    "new": SORT_NEWEST,
    "recent": SORT_NEWEST,
    "oldest": SORT_OLDEST,
    "old": SORT_OLDEST,
}


@dataclass
class Query:
    """A parsed query, ready to be handed to the index."""

    raw: str = ""
    fts: str = ""
    negative_fts: str = ""
    where: list[str] = field(default_factory=list)
    params: list[Any] = field(default_factory=list)
    terms: list[str] = field(default_factory=list)
    sort: str = ""
    include_all: bool = False
    warnings: list[str] = field(default_factory=list)

    @property
    def has_text(self) -> bool:
        return bool(self.fts)

    @property
    def is_empty(self) -> bool:
        return not self.fts and not self.negative_fts and not self.where

    @property
    def effective_sort(self) -> str:
        if self.sort:
            return self.sort
        return SORT_RELEVANCE if self.has_text else SORT_NEWEST


def parse(text: str, *, now: datetime | None = None) -> Query:
    query = Query(raw=text or "")
    moment = now or datetime.now().astimezone()

    positives: list[str] = []
    negatives: list[str] = []
    pending_or = False
    # Prefix-match the final word only while it is still being typed.
    allow_prefix_tail = bool(text) and not text[-1].isspace()

    matches = [m for m in _TOKEN_RE.finditer(text or "") if m.group(0).strip()]
    for index, match in enumerate(matches):
        is_last = index == len(matches) - 1
        negated = bool(match.group("neg"))
        word = match.group("word")

        if word and word.upper() == "OR" and not negated:
            pending_or = True
            continue

        expression = ""
        if match.group("field") is not None:
            field_name = match.group("field").lower()
            value = match.group("fvalue_q")
            quoted = value is not None
            if value is None:
                value = match.group("fvalue") or ""
            expression = _field_clause(query, field_name, value, negated, quoted, moment)
        elif match.group("phrase") is not None:
            phrase = match.group("phrase").strip()
            if phrase:
                expression = fts_quote(phrase)
                query.terms.extend(phrase.split())
        elif word:
            prefix = word.endswith("*") or (allow_prefix_tail and is_last and not negated)
            expression = fts_quote(word, prefix=prefix)
            query.terms.append(word.rstrip("*"))

        if not expression:
            pending_or = False
            continue

        if negated:
            negatives.append(expression)
            pending_or = False
        elif pending_or and positives:
            positives[-1] = f"({positives[-1]} OR {expression})"
            pending_or = False
        else:
            positives.append(expression)

    query.fts = " AND ".join(positives)
    query.negative_fts = " OR ".join(negatives)
    if query.fts and query.negative_fts:
        query.fts = f"({query.fts}) NOT ({query.negative_fts})"
        query.negative_fts = ""
    return query


def fts_quote(term: str, *, prefix: bool = False) -> str:
    """Quote a term so FTS5 treats it as literal text, never as syntax."""
    core = term.rstrip("*") if prefix else term
    core = core.strip()
    if not core:
        return ""
    escaped = core.replace('"', '""')
    return f'"{escaped}"*' if prefix else f'"{escaped}"'


def _field_clause(
    query: Query,
    name: str,
    value: str,
    negated: bool,
    quoted: bool,
    now: datetime,
) -> str:
    """Handle ``field:value``; returns an FTS fragment or "" if it was SQL."""
    value = value.strip()

    column = _TEXT_FIELDS.get(name)
    if column:
        if not value:
            return ""
        # Prefix-match unquoted field values, so `from:ali` finds "alice";
        # a quoted value means the person wants that word exactly.
        inner = fts_quote(value, prefix=not quoted)
        if not inner:
            return ""
        query.terms.extend(value.split())
        return f"{column}:{inner}"

    if name in {"folder", "in", "mailbox", "label"} and value:
        query.include_all = True
        query.where.append(
            "(" + _negate("LOWER(m.folder_name) = ? OR LOWER(m.folder_name) LIKE ?", negated) + ")"
        )
        query.params.extend([value.lower(), f"%{value.lower()}%"])
        return ""

    if name in _DATE_AFTER and value:
        stamp = _to_epoch(value, now, end=False)
        if stamp is None:
            query.warnings.append(f"could not read the date in `{name}:{value}`")
        else:
            query.where.append("m.received_ts >= ?")
            query.params.append(stamp)
        return ""

    if name in _DATE_BEFORE and value:
        stamp = _to_epoch(value, now, end=True)
        if stamp is None:
            query.warnings.append(f"could not read the date in `{name}:{value}`")
        else:
            query.where.append("m.received_ts <= ?")
            query.params.append(stamp)
        return ""

    if name in {"newer_than", "last"} and value:
        delta = _relative_days(value)
        if delta is not None:
            query.where.append("m.received_ts >= ?")
            query.params.append(int((now - timedelta(days=delta)).timestamp()))
        return ""

    if name == "older_than" and value:
        delta = _relative_days(value)
        if delta is not None:
            query.where.append("m.received_ts <= ?")
            query.params.append(int((now - timedelta(days=delta)).timestamp()))
        return ""

    if name == "has":
        lowered = value.lower()
        if lowered in {"attachment", "attachments", "file", "files"}:
            query.where.append(_negate("m.has_attachments = 1", negated))
        return ""

    if name == "is":
        lowered = value.lower()
        if lowered == "unread":
            query.where.append(_negate("m.is_read = 0", negated))
        elif lowered == "read":
            query.where.append(_negate("m.is_read = 1", negated))
        elif lowered in {"important", "high"}:
            query.where.append(_negate("LOWER(m.importance) = 'high'", negated))
        return ""

    if name == "sort" and value:
        query.sort = _SORTS.get(value.lower(), "")
        return ""

    # Not a known field — treat "foo:bar" as ordinary text rather than
    # silently dropping it.
    literal = f"{name}:{value}" if value else name
    query.terms.append(literal)
    return fts_quote(literal)


def _negate(clause: str, negated: bool) -> str:
    return f"NOT ({clause})" if negated else clause


def _relative_days(value: str) -> int | None:
    match = re.fullmatch(r"(\d+)\s*([dwmy])?", value.strip().lower())
    if not match:
        return None
    amount = int(match.group(1))
    unit = match.group(2) or "d"
    return amount * _RELATIVE_UNITS[unit]


def _to_epoch(value: str, now: datetime, *, end: bool) -> int | None:
    """Read a date the way a person would write one."""
    text = value.strip().lower()
    if not text:
        return None

    if text == "today":
        base = now.replace(hour=0, minute=0, second=0, microsecond=0)
        return int((base + timedelta(days=1) if end else base).timestamp())
    if text == "yesterday":
        base = (now - timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
        return int((base + timedelta(days=1) if end else base).timestamp())

    relative = _relative_days(text) if re.fullmatch(r"\d+\s*[dwmy]", text) else None
    if relative is not None:
        return int((now - timedelta(days=relative)).timestamp())

    normalized = text.replace("/", "-").replace(".", "-")
    for pattern, span in (("%Y-%m-%d", "day"), ("%Y-%m", "month"), ("%Y", "year")):
        try:
            parsed = datetime.strptime(normalized, pattern)
        except ValueError:
            continue
        parsed = parsed.replace(tzinfo=now.tzinfo)
        if not end:
            return int(parsed.timestamp())
        if span == "day":
            return int((parsed + timedelta(days=1)).timestamp())
        if span == "month":
            year = parsed.year + (1 if parsed.month == 12 else 0)
            month = 1 if parsed.month == 12 else parsed.month + 1
            return int(parsed.replace(year=year, month=month).timestamp())
        return int(parsed.replace(year=parsed.year + 1).timestamp())
    return None
