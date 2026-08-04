"""A thin Microsoft Graph client covering just the mail endpoints we need."""

from __future__ import annotations

import time
from collections.abc import Callable, Iterator, Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from .auth import Authenticator, NotAuthenticated
from .config import Config
from .httpclient import HttpError, Response, UrllibTransport, request_with_retries

#: Fields pulled for every message.  ``body`` is the expensive one and the
#: reason the whole thing is worth indexing locally exactly once.
MESSAGE_FIELDS = (
    "id,subject,from,toRecipients,ccRecipients,receivedDateTime,sentDateTime,"
    "hasAttachments,isRead,importance,webLink,conversationId,bodyPreview,body"
)

FOLDER_FIELDS = "id,displayName,parentFolderId,totalItemCount,childFolderCount"

#: Ask Outlook to convert HTML bodies to plain text server-side, so the index
#: stores prose instead of markup and snippets never leak tags.
BODY_AS_TEXT = 'outlook.body-content-type="text"'

DEFAULT_PAGE_SIZE = 50


@dataclass
class MailFolder:
    id: str
    display_name: str
    parent_id: str = ""
    total_items: int = 0
    child_count: int = 0
    path: str = ""

    @property
    def is_low_signal(self) -> bool:
        """Folders kept out of default search results (still indexed)."""
        return self.display_name.strip().lower() in {
            "deleted items",
            "junk email",
            "junk e-mail",
            "trash",
            "spam",
            "conversation history",
            "sync issues",
            "conflicts",
            "local failures",
            "server failures",
        }


@dataclass
class DeltaPage:
    messages: list[dict[str, Any]] = field(default_factory=list)
    removed_ids: list[str] = field(default_factory=list)
    next_link: str = ""
    delta_link: str = ""


class GraphError(Exception):
    """Graph answered with something we cannot use."""

    def __init__(self, message: str, status: int = 0, code: str = "") -> None:
        super().__init__(message)
        self.status = status
        self.code = code


class GraphClient:
    def __init__(
        self,
        config: Config,
        auth: Authenticator,
        transport: Any | None = None,
        *,
        page_size: int = DEFAULT_PAGE_SIZE,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.config = config
        self.auth = auth
        self.transport = transport or UrllibTransport()
        self.page_size = page_size
        self._sleep = sleep

    # -- plumbing ---------------------------------------------------------
    def _headers(
        self, *, body_as_text: bool = False, page_size: int | None = None
    ) -> dict[str, str]:
        headers = {
            "Authorization": f"Bearer {self.auth.access_token()}",
            "Accept": "application/json",
        }
        prefer = []
        if body_as_text:
            prefer.append(BODY_AS_TEXT)
        if page_size:
            prefer.append(f"odata.maxpagesize={page_size}")
        if prefer:
            headers["Prefer"] = ",".join(prefer)
        return headers

    def get(
        self,
        url: str,
        params: Mapping[str, Any] | None = None,
        *,
        body_as_text: bool = False,
        page_size: int | None = None,
    ) -> dict[str, Any]:
        if not url.startswith("http"):
            url = f"{self.config.graph_endpoint}{url}"

        response = self._send(url, params, body_as_text, page_size)
        if response.status == 401:
            # The token may have been revoked mid-sync; one forced refresh is
            # worth trying before giving up on the whole run.
            self.auth.access_token(force_refresh=True)
            response = self._send(url, params, body_as_text, page_size)

        if not response.ok:
            raise self._translate(response)
        payload = response.json()
        return payload if isinstance(payload, dict) else {}

    def _send(
        self,
        url: str,
        params: Mapping[str, Any] | None,
        body_as_text: bool,
        page_size: int | None,
    ) -> Response:
        return request_with_retries(
            self.transport,
            "GET",
            url,
            headers=self._headers(body_as_text=body_as_text, page_size=page_size),
            params=params,
            sleep=self._sleep,
        )

    @staticmethod
    def _translate(response: Response) -> Exception:
        error = HttpError(response)
        if response.status in (401, 403):
            return NotAuthenticated(
                "Outlook rejected the request. Sign in again, and check that the "
                "app registration has the Mail.Read permission."
            )
        return GraphError(str(error), status=response.status, code=error.code)

    # -- endpoints --------------------------------------------------------
    def me(self) -> dict[str, Any]:
        return self.get("/me", {"$select": "displayName,mail,userPrincipalName"})

    def account_address(self) -> str:
        profile = self.me()
        return str(profile.get("mail") or profile.get("userPrincipalName") or "")

    def mail_folders(self) -> list[MailFolder]:
        """Every mail folder, including nested ones, with a readable path."""
        roots = list(self._folder_page("/me/mailFolders"))
        found: list[MailFolder] = []
        seen: set[str] = set()

        stack = [(folder, "") for folder in roots]
        while stack:
            folder, prefix = stack.pop()
            if folder.id in seen:
                continue
            seen.add(folder.id)
            folder.path = f"{prefix}/{folder.display_name}" if prefix else folder.display_name
            found.append(folder)
            if folder.child_count:
                children = list(self._folder_page(f"/me/mailFolders/{folder.id}/childFolders"))
                stack.extend((child, folder.path) for child in children)
        found.sort(key=lambda item: item.path.lower())
        return found

    def _folder_page(self, path: str) -> Iterator[MailFolder]:
        url: str | None = path
        params: dict[str, Any] | None = {"$select": FOLDER_FIELDS, "$top": 100}
        while url:
            payload = self.get(url, params)
            params = None  # nextLink already carries the query string
            for item in payload.get("value", []) or []:
                if not isinstance(item, dict) or not item.get("id"):
                    continue
                yield MailFolder(
                    id=str(item["id"]),
                    display_name=str(item.get("displayName") or "(unnamed)"),
                    parent_id=str(item.get("parentFolderId") or ""),
                    total_items=_as_int(item.get("totalItemCount")),
                    child_count=_as_int(item.get("childFolderCount")),
                )
            url = payload.get("@odata.nextLink") or ""

    def delta_page(self, url: str) -> DeltaPage:
        """Fetch one page of a folder's message delta stream."""
        payload = self.get(url, body_as_text=True, page_size=self.page_size)
        page = DeltaPage(
            next_link=str(payload.get("@odata.nextLink") or ""),
            delta_link=str(payload.get("@odata.deltaLink") or ""),
        )
        for item in payload.get("value", []) or []:
            if not isinstance(item, dict):
                continue
            identifier = str(item.get("id") or "")
            if not identifier:
                continue
            if "@removed" in item:
                page.removed_ids.append(identifier)
            else:
                page.messages.append(item)
        return page

    def initial_delta_url(self, folder_id: str) -> str:
        return (
            f"{self.config.graph_endpoint}/me/mailFolders/{folder_id}/messages/delta"
            f"?$select={MESSAGE_FIELDS}"
        )

    def list_messages_url(self, folder_id: str, *, since: str = "") -> str:
        """Plain listing, used when a mailbox does not support delta queries."""
        url = (
            f"{self.config.graph_endpoint}/me/mailFolders/{folder_id}/messages"
            f"?$select={MESSAGE_FIELDS}&$orderby=receivedDateTime%20desc&$top={self.page_size}"
        )
        if since:
            url += f"&$filter=receivedDateTime%20ge%20{since}"
        return url

    def list_page(self, url: str) -> DeltaPage:
        payload = self.get(url, body_as_text=True, page_size=self.page_size)
        page = DeltaPage(next_link=str(payload.get("@odata.nextLink") or ""))
        page.messages = [
            item
            for item in (payload.get("value") or [])
            if isinstance(item, dict) and item.get("id")
        ]
        return page


# -- normalisation --------------------------------------------------------
def _as_int(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _address(entry: Any) -> tuple[str, str]:
    if not isinstance(entry, dict):
        return "", ""
    inner = entry.get("emailAddress")
    if not isinstance(inner, dict):
        return "", ""
    return str(inner.get("name") or ""), str(inner.get("address") or "")


def _address_list(entries: Any) -> str:
    if not isinstance(entries, list):
        return ""
    parts: list[str] = []
    for entry in entries:
        name, address = _address(entry)
        if name and address and name.lower() != address.lower():
            parts.append(f"{name} <{address}>")
        elif address or name:
            parts.append(address or name)
    return ", ".join(parts)


def parse_timestamp(value: Any) -> int:
    """Graph timestamps are ISO-8601 UTC; store epoch seconds for sorting."""
    if not isinstance(value, str) or not value:
        return 0
    text = value.strip().replace("Z", "+00:00")
    try:
        moment = datetime.fromisoformat(text)
    except ValueError:
        return 0
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return int(moment.timestamp())


def body_text(message: Mapping[str, Any]) -> str:
    """The message body as plain text.

    The ``Prefer: outlook.body-content-type="text"`` header normally does the
    conversion server-side, but a mailbox can still hand back HTML, so strip
    tags as a fallback rather than indexing markup.
    """
    body = message.get("body")
    if not isinstance(body, dict):
        return str(message.get("bodyPreview") or "")
    content = str(body.get("content") or "")
    declared_html = str(body.get("contentType") or "").lower() == "html"
    if declared_html or looks_like_html(content):
        return strip_html(content)
    return content


def strip_html(html: str) -> str:
    from .textutil import html_to_text

    return html_to_text(html)


def looks_like_html(text: str) -> bool:
    from .textutil import looks_like_html as _looks_like_html

    return _looks_like_html(text)


def normalize_message(message: Mapping[str, Any], folder: MailFolder) -> dict[str, Any]:
    """Turn a Graph message into the flat row the index stores."""
    from_name, from_address = _address(message.get("from"))
    text = body_text(message)
    received = str(message.get("receivedDateTime") or "")
    return {
        "id": str(message.get("id") or ""),
        "folder_id": folder.id,
        "folder_name": folder.path or folder.display_name,
        "excluded": 1 if folder.is_low_signal else 0,
        "subject": str(message.get("subject") or ""),
        "from_name": from_name,
        "from_address": from_address,
        "to_recipients": _address_list(message.get("toRecipients")),
        "cc_recipients": _address_list(message.get("ccRecipients")),
        "received_at": received,
        "received_ts": parse_timestamp(received),
        "sent_at": str(message.get("sentDateTime") or ""),
        "has_attachments": 1 if message.get("hasAttachments") else 0,
        "is_read": 1 if message.get("isRead", True) else 0,
        "importance": str(message.get("importance") or "normal"),
        "conversation_id": str(message.get("conversationId") or ""),
        "web_link": str(message.get("webLink") or ""),
        "preview": " ".join(str(message.get("bodyPreview") or "").split())[:400],
        "body": text,
    }
