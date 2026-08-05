# Mail Search for iPhone (and any phone)

Search your entire Outlook mailbox from your phone, as a Home Screen app.

This is the same idea as [the Android app](../mailsearch-android) built as a web
app instead, because **an iOS app cannot be built without a Mac**: Xcode only
runs on macOS, and installing a self-built app on an iPhone means re-signing it
every seven days. A web app added to the Home Screen has none of that: Safari
gives it an icon, opens it full-screen with no browser bars, and it updates
itself when the page changes.

Like the Android app, it stores **no mail**. You type words, Microsoft searches
your mailbox on its own servers, and only the matches come back. No computer
needs to be switched on.

---

## What you need

- An Azure app registration (free — see below), the same one the Android app
  uses, with one address added.
- Somewhere to put four static files. They have to be served over **https**,
  because that is what Microsoft will redirect back to. GitHub Pages is free and
  the files are already in your repository.

---

## 1. Publish the page

### GitHub Pages (no extra account)

In your repository: **Settings → Pages**.

- **Source**: *Deploy from a branch*
- **Branch**: `claude/email-search-outlook-dk0vks`, folder `/ (root)`
- **Save**

After a minute the app is at:

```
https://<your-github-username>.github.io/graphify/apps/mailsearch-web/
```

Note that this publishes the repository's files as a website. That is fine for a
public repository — everything in it is already visible — but if the repository
is private, use one of the alternatives below instead.

Once `apps/mailsearch-web/` reaches your default branch, the workflow in
`.github/workflows/mailsearch-web-pages.yml` can publish only this folder
instead, at `https://<username>.github.io/graphify/`. Switch **Source** to
*GitHub Actions* and run it from the Actions tab.

### Or any static host

Cloudflare Pages, Netlify, Vercel — all have a free tier and accept a dragged-in
folder. Upload the contents of `apps/mailsearch-web/`. Whatever address you get,
that is your redirect URI in step 2.

### Or your own computer, to try it first

```bash
cd apps/mailsearch-web
python3 -m http.server 8080
```

Then open <http://localhost:8080/>. `localhost` is exempt from the https rule, so
sign-in works — but only on that computer.

---

## 2. Register the address in Azure

Open your app registration → **Authentication**:

- **Add a platform** → **Single-page application**
- **Redirect URI**: the address from step 1, exactly — including the trailing
  slash

The app shows you the exact string to paste on its own setup screen, with a Copy
button, so you do not have to guess.

**Single-page application, not Web.** This is the one choice that matters. A
browser can only redeem its code if the URI is registered under that platform;
registered as "Web", Microsoft refuses with a cross-origin error. The app
recognises that particular refusal and tells you which platform to move it to.

Also confirm, as for the other apps:

- **API permissions** → Microsoft Graph → delegated **Mail.Read**,
  **User.Read**, **offline_access**
- **Supported account types** decides the **Tenant** box in the app: pick *any
  organizational directory and personal Microsoft accounts* to leave it as
  `common`; if your registration is personal accounts only, use `consumers`. If
  you get it wrong the app corrects itself and asks you to tap sign-in again.

---

## 3. Add it to your Home Screen

On the iPhone:

1. Open the address in **Safari** (it has to be Safari — Chrome on iOS cannot
   install web apps).
2. Tap **Share** → **Add to Home Screen** → **Add**.
3. Open it from the Home Screen. Full screen, its own icon, no address bar.
4. Paste your Application (client) ID, tap **Save**, then **Sign in with
   Outlook**.

Sign-in opens Microsoft's own page, so your password is never typed into
anything this app draws.

On Android the same page works in Chrome via **Install app** / **Add to Home
screen**, if you prefer it to the APK.

---

## Searching

Plain words search everywhere. Everything else combines freely:

| What you type | What it means |
| --- | --- |
| `invoice acme` | both words |
| `"purchase order"` | that exact phrase |
| `invoice OR receipt` | either word |
| `invoice -newsletter` | matches invoice, excludes newsletter |
| `tax*` | words starting with "tax" |
| `from:alice`, `to:bob`, `cc:sam` | by person |
| `subject:renewal`, `body:contract` | where to look |
| `attachment:budget` | by attachment file name |
| `has:attachment` | only mail with attachments |
| `is:unread`, `is:read` | by read state |
| `after:2024-01-01`, `before:2025-06` | by date (`2024`, `2024-06` also work) |
| `newer_than:7d`, `older_than:6m` | relative windows |

Two limits come from Outlook's search rather than this app, and it says so on
screen when you meet them: read state cannot be combined with words, and results
come back by relevance rather than by date whenever you search for words.

---

## What it does with your data

- **No mail is stored.** Results live in memory while the app is open. What
  persists is your client ID, your recent *search words*, and the sign-in tokens.
- Tokens live in the browser's `localStorage`, readable only by this page's own
  address. The page loads no third-party scripts, no analytics, no fonts — the
  only two hosts it ever contacts are `login.microsoftonline.com` and
  `graph.microsoft.com`.
- Access is **read-only** (`Mail.Read`): it cannot send, delete or change
  anything.
- Message bodies are inserted as text, never parsed as HTML, so nothing in an
  email can run in the page.
- **Sign out** discards the tokens.

One difference from the Android app worth knowing: Microsoft gives browser apps
short-lived refresh tokens (about 24 hours), so expect to tap sign-in roughly
once a day. A native app gets a long-lived one. That is Microsoft's rule for
single-page apps, not a shortcut taken here.

---

## How it is built

```
you type ─► query.js ─► KQL or OData ─► graph.microsoft.com ─► results
                                          (your mailbox, on Microsoft's servers)
```

Four hand-written files, no framework, no build step, no dependencies:

- `js/query.js` — turns what you type into a query Outlook understands, and
  chooses between `$search` (KQL, relevance-ranked) for words and `$filter`
  (OData, sortable) for constraint-only queries. A port of the Android app's
  parser; the tests assert both agree.
- `js/auth.js` — OAuth 2.0 authorization code flow with PKCE, by hand. The page
  holds no secret, which is exactly why PKCE is needed: it sends only a hash
  when it redirects, and proves it holds the original when redeeming the code.
  It also refuses a redirect whose `state` does not match the one it sent.
- `js/graph.js` — the two Graph calls, and flattening HTML bodies to text.
- `js/app.js` — the four screens, debounced searching, back-gesture handling.

## Development

```bash
cd apps/mailsearch-web
npm test          # or: node --test 'tests/*.test.js'
```

55 tests cover the query translation, the URL building, the Graph response
parsing, the HTML flattening, PKCE (against the RFC 7636 vector), the state
check, token refresh and expiry. They run in node with no browser.

### Tested how far

The whole flow was driven in a real browser at iPhone viewport size with
Microsoft intercepted: setup, sign-in redirect and code exchange, search,
opening a message, paging, sign-out, and a reload to confirm the session
persists. What has **not** been exercised is Safari on a real iPhone, and a real
Microsoft account — the parts most likely to surprise are Safari-specific
behaviour and the exact wording Azure returns if the registration is set up
differently.
