"""Text helpers: HTML to readable plain text, and date formatting."""

from __future__ import annotations

import re
from datetime import datetime, timezone
from html.parser import HTMLParser

#: Everything inside these never belongs in a search index.
_INVISIBLE = {"script", "style", "head", "title", "meta", "link"}
#: Block-level tags that should become a line break.
_BLOCKS = {
    "p", "div", "br", "tr", "li", "table", "blockquote", "section", "article",
    "h1", "h2", "h3", "h4", "h5", "h6", "hr", "ul", "ol", "pre", "header", "footer",
}


class _TextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in _INVISIBLE:
            self._skip_depth += 1
        elif tag in _BLOCKS:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in _INVISIBLE:
            self._skip_depth = max(0, self._skip_depth - 1)
        elif tag in _BLOCKS:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if self._skip_depth == 0 and data:
            self.parts.append(data)


#: Enough markup to be sure a "plain text" body is really HTML.
_HTML_HINT = re.compile(
    r"<\s*(?:html|body|head|div|p|br|table|tr|td|span|a\s|img\s|font|style)[^>]*>", re.IGNORECASE
)


def looks_like_html(text: str) -> bool:
    """True when a body claims to be plain text but is clearly markup.

    Outlook is asked for plain text, and usually obliges, but some mailboxes
    and forwarding rules hand back HTML under a text content type.  Indexing
    that verbatim would fill the index with tags and style rules.
    """
    if not text or "<" not in text:
        return False
    return len(_HTML_HINT.findall(text)) >= 2


def html_to_text(html: str) -> str:
    """Flatten an HTML email body into indexable prose."""
    if not html:
        return ""
    parser = _TextExtractor()
    try:
        parser.feed(html)
        parser.close()
    except Exception:  # pragma: no cover - malformed markup should not kill a sync
        return re.sub(r"<[^>]+>", " ", html)
    text = "".join(parser.parts)
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    text = re.sub(r"\n\s*\n\s*\n+", "\n\n", text)
    return "\n".join(line.strip() for line in text.split("\n")).strip()


def iso_to_local(value: str) -> str:
    """Render a Graph timestamp in the machine's own timezone."""
    if not value:
        return ""
    try:
        moment = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return value
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone().strftime("%Y-%m-%d %H:%M")


def shorten(text: str, limit: int = 160) -> str:
    collapsed = " ".join((text or "").split())
    if len(collapsed) <= limit:
        return collapsed
    return collapsed[: limit - 1].rstrip() + "…"
