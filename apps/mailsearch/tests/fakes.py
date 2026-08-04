"""Test doubles: an offline transport, plus helpers for building messages."""

from __future__ import annotations

import json
from collections.abc import Callable, Mapping
from typing import Any

from mailsearch.httpclient import Response, build_url


class FakeTransport:
    """Answers requests from a handler function instead of the network."""

    def __init__(self, handler: Callable[[str, str, bytes | None, Mapping[str, str]], Any]) -> None:
        self.handler = handler
        self.calls: list[tuple[str, str, bytes | None]] = []

    def request(
        self,
        method: str,
        url: str,
        *,
        headers: Mapping[str, str] | None = None,
        params: Mapping[str, Any] | None = None,
        data: bytes | None = None,
        timeout: float | None = None,
    ) -> Response:
        full = build_url(url, params)
        self.calls.append((method.upper(), full, data))
        result = self.handler(method.upper(), full, data, headers or {})
        if isinstance(result, Response):
            result.url = result.url or full
            return result
        status, payload = result
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        return Response(
            status=status, headers={"Content-Type": "application/json"}, body=body, url=full
        )

    @property
    def urls(self) -> list[str]:
        return [call[1] for call in self.calls]


def graph_message(
    identifier: str,
    subject: str = "",
    body: str = "",
    *,
    sender: str = "Alice Adams",
    address: str = "alice@example.com",
    to: str = "me@example.com",
    received: str = "2026-05-01T09:00:00Z",
    attachments: bool = False,
    read: bool = True,
    content_type: str = "text",
) -> dict[str, Any]:
    """A message shaped the way Microsoft Graph returns one."""
    return {
        "id": identifier,
        "subject": subject,
        "from": {"emailAddress": {"name": sender, "address": address}},
        "toRecipients": [{"emailAddress": {"name": "Me", "address": to}}],
        "ccRecipients": [],
        "receivedDateTime": received,
        "sentDateTime": received,
        "hasAttachments": attachments,
        "isRead": read,
        "importance": "normal",
        "webLink": f"https://outlook.office.com/mail/{identifier}",
        "conversationId": f"conv-{identifier}",
        "bodyPreview": body[:100],
        "body": {"contentType": content_type, "content": body},
    }


def indexed_row(
    identifier: str,
    subject: str = "",
    body: str = "",
    *,
    from_name: str = "Alice Adams",
    from_address: str = "alice@example.com",
    to_recipients: str = "me@example.com",
    folder: str = "Inbox",
    received_ts: int = 1_780_000_000,
    attachments: bool = False,
    read: bool = True,
    excluded: bool = False,
    importance: str = "normal",
) -> dict[str, Any]:
    """A row shaped the way the index stores one."""
    return {
        "id": identifier,
        "folder_id": folder.lower(),
        "folder_name": folder,
        "excluded": int(excluded),
        "subject": subject,
        "from_name": from_name,
        "from_address": from_address,
        "to_recipients": to_recipients,
        "cc_recipients": "",
        "received_at": "2026-05-01T09:00:00Z",
        "received_ts": received_ts,
        "sent_at": "2026-05-01T09:00:00Z",
        "has_attachments": int(attachments),
        "is_read": int(read),
        "importance": importance,
        "conversation_id": f"conv-{identifier}",
        "web_link": f"https://outlook.office.com/mail/{identifier}",
        "preview": body[:100],
        "body": body,
    }
