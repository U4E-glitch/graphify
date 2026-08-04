"""Command line entry point: ``python -m mailsearch <command>``."""

from __future__ import annotations

import argparse
import sys
import time
from collections.abc import Sequence
from pathlib import Path

from . import __version__
from . import query as query_module
from .auth import Authenticator, AuthError, NotAuthenticated
from .config import Config, load_config
from .graph import GraphClient, GraphError
from .server import serve
from .store import HIGHLIGHT_END, HIGHLIGHT_START, Store
from .sync import Syncer, SyncStatus
from .textutil import iso_to_local, shorten

SETUP_HINT = (
    "No Azure application (client) ID configured yet.\n"
    "Register a free app in the Azure portal (two minutes — see README.md),\n"
    "then run:  mailsearch setup --client-id <application-client-id>"
)


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    config = load_config(args.data_dir)
    if getattr(args, "port", None):
        config = config.with_overrides(port=args.port)

    handler = {
        "setup": _cmd_setup,
        "login": _cmd_login,
        "logout": _cmd_logout,
        "status": _cmd_status,
        "sync": _cmd_sync,
        "search": _cmd_search,
        "serve": _cmd_serve,
        "reset": _cmd_reset,
    }[args.command]

    try:
        return handler(args, config)
    except NotAuthenticated as exc:
        print(f"{exc}\nRun `mailsearch login` to sign in.", file=sys.stderr)
        return 2
    except (AuthError, GraphError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nInterrupted.", file=sys.stderr)
        return 130


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="mailsearch",
        description="A private search engine for your Outlook mailbox.",
    )
    parser.add_argument("--version", action="version", version=f"mailsearch {__version__}")
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=None,
        help="where to keep the config, token cache and index (default: ~/.mailsearch)",
    )
    subparsers = parser.add_subparsers(dest="command")

    setup = subparsers.add_parser("setup", help="store the Azure app registration details")
    setup.add_argument("--client-id", required=True, help="Application (client) ID from Azure")
    setup.add_argument(
        "--tenant",
        default=None,
        help="'common' for any account (default), 'organizations', or a tenant id",
    )
    setup.add_argument("--port", type=int, default=None, help="port for the web UI")

    subparsers.add_parser("login", help="sign in to Outlook")
    subparsers.add_parser("logout", help="forget the stored Outlook session")
    subparsers.add_parser("status", help="show sign-in and index status")

    sync = subparsers.add_parser("sync", help="pull new mail into the local index")
    sync.add_argument(
        "--full", action="store_true", help="re-read every folder from the beginning"
    )

    search = subparsers.add_parser("search", help="search the local index")
    search.add_argument("terms", nargs="+", help="what to look for")
    search.add_argument("-n", "--limit", type=int, default=15, help="how many results to show")
    search.add_argument("--all", action="store_true", help="include Junk and Deleted Items")
    search.add_argument(
        "--sort",
        choices=["relevance", "newest", "oldest"],
        default=None,
        help="result order (default: relevance when searching text)",
    )

    web = subparsers.add_parser("serve", help="run the web UI (default command)")
    web.add_argument("--port", type=int, default=None, help="port to listen on")
    web.add_argument("--no-browser", action="store_true", help="do not open a browser window")

    subparsers.add_parser("reset", help="delete the local index (keeps the sign-in)")

    parser.set_defaults(command="serve", port=None, no_browser=False)
    return parser


# -- commands -------------------------------------------------------------
def _cmd_setup(args: argparse.Namespace, config: Config) -> int:
    updated = config.with_overrides(client_id=args.client_id, tenant=args.tenant, port=args.port)
    path = updated.save()
    print(f"Saved settings to {path}")
    print(f"  client id : {updated.client_id}")
    print(f"  tenant    : {updated.tenant}")
    print("\nNext: mailsearch login")
    return 0


def _cmd_login(args: argparse.Namespace, config: Config) -> int:
    if not config.is_configured:
        print(SETUP_HINT, file=sys.stderr)
        return 2
    auth = Authenticator(config)
    flow = auth.begin_device_login()
    print("\nTo connect your Outlook account:")
    print(f"  1. Open {flow.verification_uri}")
    print(f"  2. Enter the code:  {flow.user_code}")
    print("  3. Sign in and approve the request.\n")
    print("Waiting…", end="", flush=True)
    auth.complete_device_login(flow)
    print(" signed in.")

    store = Store(config.index_path)
    try:
        address = GraphClient(config, auth).account_address()
        if address:
            auth.remember_account(address)
            store.set_meta("account", address)
            print(f"Connected as {address}")
    finally:
        store.close()
    print("\nNext: mailsearch sync")
    return 0


def _cmd_logout(args: argparse.Namespace, config: Config) -> int:
    Authenticator(config).sign_out()
    print("Signed out. The local index is untouched — run `mailsearch reset` to erase it.")
    return 0


def _cmd_status(args: argparse.Namespace, config: Config) -> int:
    auth = Authenticator(config)
    store = Store(config.index_path)
    try:
        stats = store.stats()
    finally:
        store.close()

    print(f"data dir   : {config.data_dir}")
    print(f"client id  : {config.client_id or '(not configured — run mailsearch setup)'}")
    print(f"signed in  : {'yes' if auth.is_signed_in else 'no'}")
    if auth.account or stats.get("account"):
        print(f"account    : {auth.account or stats['account']}")
    print(f"messages   : {stats['messages']:,} in {stats['folders']} folders")
    if stats["newest_ts"]:
        newest = time.strftime("%Y-%m-%d", time.localtime(stats["newest_ts"]))
        oldest = time.strftime("%Y-%m-%d", time.localtime(stats["oldest_ts"]))
        print(f"covering   : {oldest} → {newest}")
    print(f"last sync  : {stats['last_sync'] or 'never'}")
    return 0


def _cmd_sync(args: argparse.Namespace, config: Config) -> int:
    if not config.is_configured:
        print(SETUP_HINT, file=sys.stderr)
        return 2
    auth = Authenticator(config)
    if not auth.is_signed_in:
        raise NotAuthenticated("Not signed in to Outlook yet.")

    store = Store(config.index_path)
    try:
        syncer = Syncer(store, GraphClient(config, auth), on_progress=_progress_line)
        status = syncer.run(full=args.full)
    finally:
        store.close()

    sys.stderr.write("\r" + " " * 78 + "\r")
    if status["error"]:
        print(f"sync failed: {status['error']}", file=sys.stderr)
        return 1
    print(
        f"Indexed {status['indexed']:,} messages"
        + (f", removed {status['removed']:,}" if status["removed"] else "")
        + f" across {status['folders_done']} folders in {status['elapsed']}s."
    )
    return 0


_last_progress = 0.0


def _progress_line(status: SyncStatus) -> None:
    global _last_progress
    now = time.time()
    if now - _last_progress < 0.2 and status.running:
        return
    _last_progress = now
    if not sys.stderr.isatty():
        return
    folder = shorten(status.folder, 30)
    line = (
        f"\r[{status.folders_done}/{status.folders_total or '?'}] "
        f"{status.indexed:,} indexed  {folder}"
    )
    sys.stderr.write(line.ljust(78)[:78])
    sys.stderr.flush()


def _cmd_search(args: argparse.Namespace, config: Config) -> int:
    text = " ".join(args.terms)
    parsed = query_module.parse(text + " ")  # a trailing space means "whole words"
    if args.sort:
        parsed.sort = args.sort

    store = Store(config.index_path)
    try:
        if store.stats()["messages"] == 0:
            print("The index is empty. Run `mailsearch sync` first.", file=sys.stderr)
            return 1
        started = time.perf_counter()
        payload = store.search(parsed, limit=args.limit, include_all=args.all)
    finally:
        store.close()
    took = (time.perf_counter() - started) * 1000

    for warning in parsed.warnings:
        print(f"note: {warning}", file=sys.stderr)

    results = payload["results"]
    if not results:
        print(f"No messages match {text!r}.")
        return 0

    print(f"{payload['total']:,} match{'es' if payload['total'] != 1 else ''} ({took:.0f} ms)\n")
    for index, row in enumerate(results, start=1):
        sender = row["from_name"] or row["from_address"] or "(unknown sender)"
        when = iso_to_local(row["received_at"])
        flags = "".join(["📎" if row["has_attachments"] else "", "•" if not row["is_read"] else ""])
        print(f"{index:>3}. {row['subject'] or '(no subject)'}  {flags}")
        print(f"     {sender}  ·  {when}  ·  {row['folder_name']}")
        snippet = _plain(row["body_snippet"]) or row["preview"]
        if snippet:
            print(f"     {shorten(snippet, 150)}")
        print()
    if payload["total"] > len(results):
        print(f"… {payload['total'] - len(results):,} more. Use -n to show more.")
    return 0


def _plain(snippet: str) -> str:
    """Render index highlight markers for a terminal."""
    if not snippet:
        return ""
    if sys.stdout.isatty():
        return snippet.replace(HIGHLIGHT_START, "\033[1;33m").replace(HIGHLIGHT_END, "\033[0m")
    return snippet.replace(HIGHLIGHT_START, "").replace(HIGHLIGHT_END, "")


def _cmd_serve(args: argparse.Namespace, config: Config) -> int:
    if not config.is_configured:
        print(SETUP_HINT, file=sys.stderr)
        return 2
    serve(config, port=args.port, open_browser=not args.no_browser)
    return 0


def _cmd_reset(args: argparse.Namespace, config: Config) -> int:
    store = Store(config.index_path)
    try:
        count = store.stats()["messages"]
        store.reset_index()
    finally:
        store.close()
    print(f"Cleared {count:,} indexed messages. Run `mailsearch sync` to rebuild.")
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
