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

from . import thunderbird
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


class SyncEngine:
    """Status and threading, shared by every kind of sync.

    Subclasses implement :meth:`_sync`; everything about progress reporting,
    cancellation and running in the background lives here, so the server and
    the CLI hold the same handle whichever mail source is in use.
    """

    def __init__(
        self,
        store: Store,
        *,
        on_progress: Callable[[SyncStatus], None] | None = None,
    ) -> None:
        self.store = store
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
            self._sync(full=full)
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

    def _bump(self, *, indexed: int = 0, removed: int = 0) -> None:
        with self._lock:
            self._status.indexed += indexed
            self._status.removed += removed
            current = self._status
        if self._on_progress and (indexed or removed):
            self._on_progress(current)

    def _sync(self, *, full: bool) -> None:
        raise NotImplementedError


class GraphSyncer(SyncEngine):
    """Pulls mail from Outlook over Microsoft Graph."""

    def __init__(
        self,
        store: Store,
        graph: GraphClient,
        *,
        on_progress: Callable[[SyncStatus], None] | None = None,
    ) -> None:
        super().__init__(store, on_progress=on_progress)
        self.graph = graph

    def _sync(self, *, full: bool) -> None:
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


#: The original name, kept so existing callers and tests keep working.
Syncer = GraphSyncer


class ThunderbirdSyncer(SyncEngine):
    """Indexes the mail Thunderbird has already downloaded to this machine.

    No network, no account, no Microsoft app registration: it reads the mbox
    and maildir files in a Thunderbird profile.  A folder is re-read only when
    its file has changed since last time, so repeat runs are quick.
    """

    def __init__(
        self,
        store: Store,
        *,
        profile: str | None = None,
        on_progress: Callable[[SyncStatus], None] | None = None,
    ) -> None:
        super().__init__(store, on_progress=on_progress)
        self.profile = profile
        self.profiles: list[Any] = []

    def _sync(self, *, full: bool) -> None:
        self._update(phase="finding Thunderbird")
        self.profiles = thunderbird.find_profiles(self.profile)
        if not self.profiles:
            raise ProfileNotFound(
                "No Thunderbird mail was found on this computer. Install Thunderbird, "
                "add your Outlook account, let it download your mail, then try again. "
                "Use --profile to point at a profile folder directly."
            )

        folders: list[thunderbird.MailFolder] = []
        for profile in self.profiles:
            folders.extend(thunderbird.find_folders(profile))
        if not folders:
            raise ProfileNotFound(
                f"Thunderbird is installed ({self.profiles[0]}) but has no mail stored "
                "yet. In Thunderbird, open Account Settings → Synchronisation & Storage "
                "and turn on keeping messages on this computer."
            )

        self.store.drop_folders_missing_from(folder.id for folder in folders)
        for folder in folders:
            self.store.upsert_folder(
                folder.id,
                folder.name,
                path=folder.display,
                parent_id=folder.account,
                excluded=folder.is_low_signal,
            )
        if full:
            self.store.clear_delta_links()

        account = self.store.get_meta("account")
        if not account:
            self.store.set_meta("account", _account_hint(folders))

        self._update(folders_total=len(folders), phase="indexing")
        for index, folder in enumerate(folders, start=1):
            if self._cancel.is_set():
                self._update(phase="cancelled")
                break
            self._update(folder=folder.display, folders_done=index - 1)
            self._index_folder(folder)
            self._update(folders_done=index)

    def _index_folder(self, folder: thunderbird.MailFolder) -> None:
        fingerprint = folder.fingerprint()
        if fingerprint and fingerprint == self.store.delta_link(folder.id):
            return  # untouched since the last run

        batch: list[dict[str, Any]] = []
        seen: list[str] = []
        for ordinal, message in enumerate(thunderbird.read_messages(folder)):
            if self._cancel.is_set():
                return
            row = thunderbird.normalize(message, folder, ordinal)
            seen.append(str(row["id"]))
            batch.append(row)
            if len(batch) >= BATCH_SIZE:
                self._bump(indexed=self.store.upsert_messages(batch))
                batch.clear()
        if batch:
            self._bump(indexed=self.store.upsert_messages(batch))

        # Messages that vanished from the file are gone from the mailbox too.
        self._bump(removed=self.store.delete_missing_from_folder(folder.id, seen))
        self.store.save_delta_link(folder.id, fingerprint)


class ProfileNotFound(OSError):
    """Thunderbird, or its mail, could not be found on this machine."""


def _account_hint(folders: list[thunderbird.MailFolder]) -> str:
    """A readable name for what is being indexed, for the UI header."""
    accounts = [folder.account for folder in folders if folder.account]
    for account in accounts:
        if "@" in account:
            return account
    return accounts[0] if accounts else "Thunderbird"
