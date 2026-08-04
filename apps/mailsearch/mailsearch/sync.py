"""Pull mail from Outlook into the local index.

Syncing is per folder and incremental.  Graph hands back a *delta link* at the
end of each folder's stream; storing it means the next run asks only "what
changed since?", so the first sync is the slow one and every sync after it is
seconds.  Delta streams also report deletions and moves, which a plain
"messages newer than X" query never would.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from dataclasses import asdict, dataclass
from typing import Any

from .auth import AuthError, NotAuthenticated
from .graph import GraphClient, GraphError, MailFolder, normalize_message
from .store import Store

#: Rows buffered before a write.  Big enough to keep SQLite busy, small enough
#: that progress in the UI keeps moving.
BATCH_SIZE = 50


@dataclass
class SyncStatus:
    running: bool = False
    phase: str = "idle"
    folder: str = ""
    folders_done: int = 0
    folders_total: int = 0
    indexed: int = 0
    removed: int = 0
    started_at: float = 0.0
    finished_at: float = 0.0
    error: str = ""
    needs_login: bool = False

    def snapshot(self) -> dict[str, Any]:
        data = asdict(self)
        data["elapsed"] = round(
            (self.finished_at or time.time()) - self.started_at if self.started_at else 0.0, 1
        )
        return data


class Syncer:
    """Runs a sync, in the foreground or on a background thread."""

    def __init__(
        self,
        store: Store,
        graph: GraphClient,
        *,
        on_progress: Callable[[SyncStatus], None] | None = None,
    ) -> None:
        self.store = store
        self.graph = graph
        self._on_progress = on_progress
        self._status = SyncStatus()
        self._lock = threading.Lock()
        self._cancel = threading.Event()
        self._thread: threading.Thread | None = None

    # -- status -----------------------------------------------------------
    @property
    def status(self) -> dict[str, Any]:
        with self._lock:
            return self._status.snapshot()

    @property
    def is_running(self) -> bool:
        with self._lock:
            return self._status.running

    def _update(self, **fields: Any) -> None:
        with self._lock:
            for key, value in fields.items():
                setattr(self._status, key, value)
            current = self._status
        if self._on_progress:
            self._on_progress(current)

    # -- control ----------------------------------------------------------
    def cancel(self) -> None:
        self._cancel.set()

    def start(self, *, full: bool = False) -> bool:
        """Kick off a sync in the background.  False if one is already running."""
        with self._lock:
            if self._status.running:
                return False
            self._status = SyncStatus(running=True, phase="starting", started_at=time.time())
        self._cancel.clear()
        self._thread = threading.Thread(
            target=self._run_guarded, kwargs={"full": full}, daemon=True, name="mailsearch-sync"
        )
        self._thread.start()
        return True

    def join(self, timeout: float | None = None) -> None:
        if self._thread:
            self._thread.join(timeout)

    def _run_guarded(self, *, full: bool) -> None:
        try:
            self.run(full=full, _already_started=True)
        except Exception as exc:  # pragma: no cover - defensive: never kill the thread
            self._update(
                running=False,
                phase="failed",
                error=str(exc),
                finished_at=time.time(),
                needs_login=isinstance(exc, NotAuthenticated),
            )

    # -- the work ---------------------------------------------------------
    def run(self, *, full: bool = False, _already_started: bool = False) -> dict[str, Any]:
        if not _already_started:
            with self._lock:
                if self._status.running:
                    return self._status.snapshot()
                self._status = SyncStatus(running=True, phase="starting", started_at=time.time())
            self._cancel.clear()

        try:
            self._update(phase="listing folders")
            account = self.graph.account_address()
            if account:
                self.store.set_meta("account", account)

            folders = self.graph.mail_folders()
            self.store.drop_folders_missing_from(folder.id for folder in folders)
            for folder in folders:
                self.store.upsert_folder(
                    folder.id,
                    folder.display_name,
                    path=folder.path,
                    parent_id=folder.parent_id,
                    excluded=folder.is_low_signal,
                )
            if full:
                self.store.clear_delta_links()

            self._update(folders_total=len(folders), phase="syncing")
            for index, folder in enumerate(folders, start=1):
                if self._cancel.is_set():
                    self._update(phase="cancelled")
                    break
                self._update(folder=folder.path or folder.display_name, folders_done=index - 1)
                self._sync_folder(folder)
                self._update(folders_done=index)

            finished = not self._cancel.is_set()
            if finished:
                self.store.set_meta("last_sync_at", _now_iso())
            self._update(
                running=False,
                phase="done" if finished else "cancelled",
                folder="",
                finished_at=time.time(),
            )
        except NotAuthenticated as exc:
            self._update(
                running=False,
                phase="failed",
                error=str(exc),
                needs_login=True,
                finished_at=time.time(),
            )
        except (AuthError, GraphError, OSError) as exc:
            self._update(running=False, phase="failed", error=str(exc), finished_at=time.time())
        return self.status

    def _sync_folder(self, folder: MailFolder) -> None:
        stored_link = self.store.delta_link(folder.id)
        url = stored_link or self.graph.initial_delta_url(folder.id)
        use_delta = True

        while url:
            if self._cancel.is_set():
                return
            try:
                page = self.graph.delta_page(url) if use_delta else self.graph.list_page(url)
            except GraphError as exc:
                if use_delta and stored_link and _is_stale_delta(exc):
                    # The saved token aged out; start this folder's stream over.
                    self.store.save_delta_link(folder.id, "")
                    stored_link = ""
                    url = self.graph.initial_delta_url(folder.id)
                    continue
                if use_delta and _is_delta_unsupported(exc):
                    # Some mailboxes do not offer delta queries; fall back to a
                    # plain listing, which still finds new and changed mail.
                    use_delta = False
                    url = self.graph.list_messages_url(folder.id)
                    continue
                raise

            if page.messages:
                rows = [normalize_message(message, folder) for message in page.messages]
                for start in range(0, len(rows), BATCH_SIZE):
                    written = self.store.upsert_messages(rows[start : start + BATCH_SIZE])
                    self._bump(indexed=written)
            if page.removed_ids:
                removed = self.store.delete_messages(page.removed_ids)
                self._bump(removed=removed)

            if page.delta_link:
                self.store.save_delta_link(folder.id, page.delta_link)
                return
            url = page.next_link
        if not use_delta:
            self.store.save_delta_link(folder.id, "")

    def _bump(self, *, indexed: int = 0, removed: int = 0) -> None:
        with self._lock:
            self._status.indexed += indexed
            self._status.removed += removed
            current = self._status
        if self._on_progress and (indexed or removed):
            self._on_progress(current)


def _is_stale_delta(error: GraphError) -> bool:
    text = str(error).lower()
    return (
        getattr(error, "status", 0) == 410
        or "resyncrequired" in text
        or "syncstatenotfound" in text
        or "synchronization state" in text
    )


def _is_delta_unsupported(error: GraphError) -> bool:
    status = getattr(error, "status", 0)
    text = str(error).lower()
    return status in (400, 405, 501) or "not supported" in text or "badrequest" in text


def _now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime())
