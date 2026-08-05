"""Where the app keeps its settings, its token cache and its index."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, replace
from pathlib import Path

#: Delegated permissions requested at login.  ``offline_access`` is what lets
#: the app refresh silently instead of asking for a code every hour.
DEFAULT_SCOPES = ("offline_access", "User.Read", "Mail.Read")

DEFAULT_AUTHORITY = "https://login.microsoftonline.com"
#: "common" accepts both personal (outlook.com, hotmail.com, live.com) and
#: work/school accounts.  A single tenant id restricts login to one org.
DEFAULT_TENANT = "common"
DEFAULT_GRAPH_ENDPOINT = "https://graph.microsoft.com/v1.0"
DEFAULT_PORT = 8765

#: Where the mail comes from.  "graph" signs in to Outlook directly and needs an
#: Azure app registration; "thunderbird" reads the copy Thunderbird already
#: keeps on this machine and needs no account access at all.
SOURCE_GRAPH = "graph"
SOURCE_THUNDERBIRD = "thunderbird"
SOURCES = (SOURCE_GRAPH, SOURCE_THUNDERBIRD)

CONFIG_FILENAME = "config.json"
TOKENS_FILENAME = "tokens.json"
INDEX_FILENAME = "index.sqlite3"


def default_data_dir() -> Path:
    override = os.environ.get("MAILSEARCH_HOME")
    if override:
        return Path(override).expanduser()
    return Path.home() / ".mailsearch"


@dataclass(frozen=True)
class Config:
    """Everything the app needs to know before it can talk to Outlook."""

    client_id: str = ""
    tenant: str = DEFAULT_TENANT
    authority: str = DEFAULT_AUTHORITY
    graph_endpoint: str = DEFAULT_GRAPH_ENDPOINT
    scopes: tuple[str, ...] = DEFAULT_SCOPES
    port: int = DEFAULT_PORT
    data_dir: Path = Path()

    #: Which mail source to index (see SOURCE_* above).
    source: str = SOURCE_GRAPH
    #: Thunderbird profile directory, when the automatic search picks wrong.
    profile: str = ""
    #: PBKDF2 hash of the passcode that guards remote access. Empty means the
    #: app has never been opened up beyond this computer.
    passcode: str = ""
    #: Random per-install value that signs session cookies.
    session_secret: str = ""

    # -- derived paths ----------------------------------------------------
    @property
    def config_path(self) -> Path:
        return self.data_dir / CONFIG_FILENAME

    @property
    def token_path(self) -> Path:
        return self.data_dir / TOKENS_FILENAME

    @property
    def index_path(self) -> Path:
        return self.data_dir / INDEX_FILENAME

    @property
    def device_code_url(self) -> str:
        return f"{self.authority}/{self.tenant}/oauth2/v2.0/devicecode"

    @property
    def token_url(self) -> str:
        return f"{self.authority}/{self.tenant}/oauth2/v2.0/token"

    @property
    def scope_string(self) -> str:
        return " ".join(self.scopes)

    @property
    def is_configured(self) -> bool:
        """True when the chosen source has everything it needs to run."""
        if self.source == SOURCE_THUNDERBIRD:
            return True  # nothing to configure; the mail is already on disk
        return bool(self.client_id)

    @property
    def uses_thunderbird(self) -> bool:
        return self.source == SOURCE_THUNDERBIRD

    @property
    def has_passcode(self) -> bool:
        return bool(self.passcode)

    # -- persistence ------------------------------------------------------
    def save(self) -> Path:
        ensure_data_dir(self.data_dir)
        payload = {
            "client_id": self.client_id,
            "tenant": self.tenant,
            "authority": self.authority,
            "graph_endpoint": self.graph_endpoint,
            "scopes": list(self.scopes),
            "port": self.port,
            "source": self.source,
            "profile": self.profile,
            "passcode": self.passcode,
            "session_secret": self.session_secret,
        }
        path = self.config_path
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        _restrict(path)
        return path

    def with_overrides(self, **kwargs: object) -> Config:
        clean = {key: value for key, value in kwargs.items() if value is not None}
        if "scopes" in clean:
            clean["scopes"] = tuple(clean["scopes"])  # type: ignore[arg-type]
        return replace(self, **clean)  # type: ignore[arg-type]


def ensure_data_dir(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    _restrict(path, directory=True)
    return path


def _restrict(path: Path, directory: bool = False) -> None:
    """Keep tokens and indexed mail readable only by the current user."""
    try:
        path.chmod(0o700 if directory else 0o600)
    except OSError:  # pragma: no cover - Windows and exotic filesystems
        pass


def load_config(data_dir: Path | None = None) -> Config:
    """Read the stored config, then let environment variables win.

    Environment overrides make it easy to point a second copy of the app at a
    different mailbox without editing files:

        MAILSEARCH_HOME, MAILSEARCH_CLIENT_ID, MAILSEARCH_TENANT,
        MAILSEARCH_AUTHORITY, MAILSEARCH_GRAPH_ENDPOINT, MAILSEARCH_PORT,
        MAILSEARCH_SOURCE, MAILSEARCH_PROFILE
    """
    directory = Path(data_dir).expanduser() if data_dir else default_data_dir()
    config = Config(data_dir=directory)

    stored = directory / CONFIG_FILENAME
    if stored.is_file():
        try:
            raw = json.loads(stored.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raw = {}
        if isinstance(raw, dict):
            config = config.with_overrides(
                client_id=raw.get("client_id"),
                tenant=raw.get("tenant"),
                authority=raw.get("authority"),
                graph_endpoint=raw.get("graph_endpoint"),
                scopes=raw.get("scopes") or None,
                port=_as_int(raw.get("port")),
                source=raw.get("source"),
                profile=raw.get("profile"),
                passcode=raw.get("passcode"),
                session_secret=raw.get("session_secret"),
            )

    return config.with_overrides(
        client_id=os.environ.get("MAILSEARCH_CLIENT_ID") or None,
        source=os.environ.get("MAILSEARCH_SOURCE") or None,
        profile=os.environ.get("MAILSEARCH_PROFILE") or None,
        tenant=os.environ.get("MAILSEARCH_TENANT") or None,
        authority=os.environ.get("MAILSEARCH_AUTHORITY") or None,
        graph_endpoint=os.environ.get("MAILSEARCH_GRAPH_ENDPOINT") or None,
        port=_as_int(os.environ.get("MAILSEARCH_PORT")),
    )


def _as_int(value: object) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
