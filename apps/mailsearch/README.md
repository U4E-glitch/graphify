# Mail Search

A private search engine for your Outlook mailbox.

Connect your Outlook account once, and the app downloads your mail to **your own
computer** and builds a full-text index of it. After that you can search every
message you have ever received — subject, sender, recipients and the entire body
— by typing a word or two. Results come back in milliseconds, with the matching
words highlighted.

```
$ mailsearch search invoice contoso
2 matches (4 ms)

  1. Invoice 2026-041 from Contoso  📎
     Contoso Billing  ·  2026-06-14 09:12  ·  Inbox
     Your invoice 2026-041 for 1,240.00 EUR is attached and payable by 30 June.
```

There is a web interface too — `mailsearch serve` opens a search page in your
browser with a reading pane, filters and keyboard navigation.

---

## What it does and does not do

- Mail is fetched from Outlook over Microsoft's official API (Microsoft Graph),
  using the account you sign in with.
- Everything is stored locally: `~/.mailsearch/index.sqlite3`. Searching never
  touches the network.
- Nothing is uploaded anywhere. There is no server, no account, no telemetry.
- Access is **read-only**. The app asks for the `Mail.Read` permission, so it
  cannot send, delete or change anything in your mailbox.

---

## Setup

You need Python 3.10 or newer. There is nothing to `pip install` — the app runs
on the standard library alone.

### 1. Register a free app in Azure (once, about two minutes)

Microsoft requires every program that reads a mailbox to be registered. It is
free, and it is what makes the sign-in page trust this app.

1. Go to <https://portal.azure.com> and sign in with your Outlook account.
2. Search for **Microsoft Entra ID** (formerly Azure Active Directory) →
   **App registrations** → **New registration**.
3. Fill in:
   - **Name**: anything, e.g. `Mail Search`
   - **Supported account types**: *Accounts in any organizational directory and
     personal Microsoft accounts* — this covers both outlook.com/hotmail
     addresses and work or school accounts.
   - **Redirect URI**: leave it empty.
4. Press **Register**.
5. On the app's **Authentication** page, scroll to **Advanced settings** and set
   **Allow public client flows** to **Yes**. Save. *(This is what enables the
   sign-in-with-a-code flow. Without it, login fails with `unauthorized_client`.)*
6. On the **API permissions** page: **Add a permission** → **Microsoft Graph** →
   **Delegated permissions** → tick **Mail.Read**, **User.Read** and
   **offline_access** → **Add permissions**.
7. Copy the **Application (client) ID** from the app's Overview page.

> Work or school account? Your organisation may require an administrator to
> approve the permissions. If sign-in reports that admin consent is needed,
> send that page to your IT department.

### 2. Point the app at your registration

```bash
cd apps/mailsearch
python3 -m mailsearch setup --client-id <the-application-client-id>
```

Use `--tenant <your-tenant-id>` if your organisation only allows its own
directory. The default, `common`, accepts any Microsoft account.

### 3. Sign in and download your mail

```bash
python3 -m mailsearch login    # shows a code to type at microsoft.com/devicelogin
python3 -m mailsearch sync     # downloads and indexes; the first run is the slow one
```

The first sync fetches every message in every folder — expect a few minutes for
a large mailbox. Every sync after that is incremental (Microsoft tells the app
only what changed), so it takes seconds.

### 4. Search

```bash
python3 -m mailsearch search "wire transfer"     # in the terminal
python3 -m mailsearch serve                      # or in the browser
```

Installing it as a command is optional:

```bash
pip install -e apps/mailsearch    # then just: mailsearch serve
```

---

## Search syntax

Type plain words to search everywhere — subject, sender, recipients and body:

    invoice acme

Everything below can be combined freely.

| What you type | What it means |
| --- | --- |
| `invoice acme` | both words, anywhere in the message |
| `"purchase order"` | that exact phrase |
| `invoice OR receipt` | either word |
| `invoice -newsletter` | matches `invoice`, excludes `newsletter` |
| `tax*` | words starting with `tax` |
| `from:alice` | from a sender whose name or address contains "alice" |
| `to:bob` | sent to someone matching "bob" (To or Cc) |
| `subject:renewal` | the word appears in the subject |
| `body:contract` | only in the message body |
| `folder:archive` | inside a folder whose name contains "archive" |
| `has:attachment` | messages with attachments |
| `is:unread`, `is:read`, `is:important` | by state |
| `after:2024-01-01`, `before:2025-06` | by date; `2024`, `2024-06` and `2024-06-15` all work |
| `after:today`, `after:yesterday` | recent mail |
| `newer_than:7d`, `older_than:6m` | relative windows (`d`, `w`, `m`, `y`) |
| `sort:newest`, `sort:oldest` | override relevance ordering |

Two details worth knowing:

- **While you are typing**, the last word is treated as a prefix, so `invoi`
  already finds "invoice". Once you type a space after it, it has to match the
  whole word. Quoted words are always matched exactly.
- **Junk Email and Deleted Items are excluded** from results by default. Tick
  *junk & deleted* in the web UI, pass `--all` on the command line, or name the
  folder explicitly (`folder:junk`) to include them.

Ranking is BM25 with the subject weighted highest, then the sender, then
recipients, then the body — so a message *about* your search term beats one that
merely mentions it in a signature.

---

## Commands

| Command | What it does |
| --- | --- |
| `mailsearch setup --client-id ID` | stores the Azure app registration |
| `mailsearch login` | signs in to Outlook |
| `mailsearch sync [--full]` | fetches new mail; `--full` re-reads everything |
| `mailsearch search QUERY [-n 20] [--all]` | searches in the terminal |
| `mailsearch serve [--port 8765]` | runs the web UI (this is the default command) |
| `mailsearch status` | sign-in state, message count, last sync |
| `mailsearch logout` | forgets the Outlook session (keeps the index) |
| `mailsearch reset` | erases the local index (keeps the sign-in) |

Keeping the index fresh is just `mailsearch sync` — worth a cron entry or
scheduled task if you want it automatic. The web UI also has a **Sync** button.

Settings can be overridden per run with `MAILSEARCH_HOME`,
`MAILSEARCH_CLIENT_ID`, `MAILSEARCH_TENANT` and `MAILSEARCH_PORT`.

---

## How it works

```
Outlook ──HTTPS──> Microsoft Graph ──delta sync──> SQLite + FTS5 ──> search UI
   (your mailbox)     (read-only)                  (~/.mailsearch)    (localhost)
```

- **`auth.py`** — OAuth 2.0 device-code flow. You type a code on Microsoft's own
  page; the app never sees your password. It receives a short-lived access token
  and a refresh token, and refreshes silently afterwards. Tokens are cached in
  `~/.mailsearch/tokens.json` at mode `0600`.
- **`graph.py`** — the Microsoft Graph client. Asks for bodies as plain text,
  honours `Retry-After` when Microsoft throttles a large first sync, and retries
  transient failures.
- **`sync.py`** — walks every mail folder and follows Graph's *delta* stream.
  The delta link is saved per folder, so later runs ask only "what changed?" and
  pick up deletions and moves, not just new mail. Mailboxes that do not support
  delta queries fall back to a plain listing automatically.
- **`store.py`** — SQLite with an FTS5 index over subject, sender, recipients
  and body. The body is stored only in the FTS table, so a mailbox is not kept
  twice on disk. Snippets come straight from FTS5.
- **`query.py`** — turns what you type into an FTS5 expression plus SQL filters.
  Every term is quoted before it reaches SQLite, so no query can be malformed
  or injected.
- **`server.py`** — a small HTTP server bound to `127.0.0.1` and the static UI.

### Notes on privacy and safety

- The server listens on the loopback interface only. Because any website can
  also reach `localhost`, every API call must carry an `X-Mailsearch` header
  (which forces a CORS preflight the app never approves), and requests arriving
  with a foreign `Origin` or `Host` are rejected.
- Message bodies are rendered as text, never as HTML, so nothing in an email can
  execute in the UI. The page itself is served under a strict Content-Security
  Policy and loads no external resources.
- Anyone with access to your user account on this computer can read the index.
  It is an unencrypted SQLite file — use `mailsearch reset` to erase it, and
  full-disk encryption if the machine is shared.

### Current limits

- Attachment *contents* are not indexed — only the fact that a message has one.
  `has:attachment` works; searching text inside a PDF does not.
- Search covers mail only: calendar, contacts and Teams messages are not touched.
- Sync is manual (a button or a command); there is no background daemon.

---

## Development

```bash
cd apps/mailsearch
python3 -m pytest tests/ -q
```

112 tests cover the query parser, the index, the OAuth flow, the Graph client,
the sync engine, the HTTP API and the CLI. They run entirely offline — the
network layer is injected, so the tests exercise the real code against a
scripted Microsoft.
