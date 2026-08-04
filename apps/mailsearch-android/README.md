# Mail Search for Android

Search your entire Outlook mailbox from your phone.

Type a word or two and Outlook searches every message you have ever received —
subject, sender, body, attachment names — and sends back the matches. **Nothing
is downloaded to the phone.** There is no sync, no mailbox copy, no gigabyte of
storage: Microsoft already indexes your mail, and this app asks that index the
questions you type.

Related: [`../mailsearch`](../mailsearch) is the desktop version, which does the
opposite — it downloads your mail once and indexes it locally, so searching is
instant and works offline.

| | Phone app (this one) | Desktop app |
| --- | --- | --- |
| Where the search runs | Microsoft's servers | your own computer |
| Mail stored on the device | none | the whole mailbox |
| Needs a connection | yes | only to fetch new mail |
| Speed | a moment per search | milliseconds |
| Coverage | whole mailbox, always current | whatever was last synced |

---

## Installing

1. Copy `mailsearch.apk` to your phone (email it to yourself, or use a cable or
   a cloud drive).
2. Tap the file. Android will warn that it comes from an unknown source — allow
   it for the app you are installing from, since this APK was not distributed
   through the Play Store.
3. Open **Mail Search**.

The APK is signed with a development key. That is enough to install and use it,
but it means a version built later on a different machine will not update over
it — uninstall first, or build with your own key (see *Building* below).

## Connecting your mailbox

The app needs an Azure app registration, exactly like the desktop version. **If
you already registered one for the desktop app, reuse it — you only need to add
one redirect address.**

1. Go to <https://portal.azure.com> → **Microsoft Entra ID** → **App
   registrations** → **New registration**.
   - **Name**: anything, e.g. `Mail Search`
   - **Supported account types**: *Accounts in any organizational directory and
     personal Microsoft accounts*
2. Open the registration's **Authentication** page:
   - **Add a platform** → **Mobile and desktop applications** → **Custom
     redirect URI** → enter exactly:

     ```
     mailsearch://auth
     ```
   - Under **Advanced settings**, set **Allow public client flows** to **Yes**.
3. **API permissions** → **Add a permission** → **Microsoft Graph** →
   **Delegated permissions** → add **Mail.Read**, **User.Read** and
   **offline_access**.
4. Copy the **Application (client) ID** from the Overview page.
5. Open the app, paste that ID into the setup screen, then tap **Sign in with
   Outlook**.

Signing in opens your normal browser on Microsoft's own page — the app never
sees your password. Afterwards it holds a refresh token so it does not ask
again.

## Searching

Plain words search everywhere:

    invoice acme

| What you type | What it means |
| --- | --- |
| `"purchase order"` | that exact phrase |
| `invoice OR receipt` | either word |
| `invoice -newsletter` | matches invoice, excludes newsletter |
| `tax*` | words starting with "tax" |
| `from:alice` | from a particular sender |
| `to:bob`, `cc:sam` | by recipient |
| `subject:renewal` | in the subject line |
| `body:contract` | in the message body |
| `attachment:budget` | by attachment file name |
| `has:attachment` | only mail with attachments |
| `is:unread`, `is:read` | by read state |
| `after:2024-01-01`, `before:2025-06` | by date (`2024`, `2024-06`, `2024-06-15`) |
| `newer_than:7d`, `older_than:6m` | relative windows (`d`, `w`, `m`, `y`) |

Two limits come from Outlook's own search, not from this app, and the app says
so on screen when you hit them:

- **Read state cannot be combined with words.** `is:unread` on its own works
  and sorts newest first; `invoice is:unread` searches for "invoice" across all
  mail. Microsoft's search index has no read/unread property.
- **Results come back by relevance, not by date**, whenever you search for
  words. Sorting is only available when the query is filters alone.

Searching one named folder is not supported yet — every search covers the whole
mailbox.

## What it does with your data

- Read-only: the app asks for `Mail.Read`, so it cannot send, delete or change
  anything in your mailbox.
- No mail is stored on the phone. Results live in memory while the app is open
  and are gone when it closes. Only your recent *search words* are remembered,
  so they can be offered again.
- The access and refresh tokens are kept in the app's private storage, which no
  other app can read on an unrooted phone. They are not additionally encrypted;
  someone with your unlocked phone could use the app as you.
- The app talks to exactly two hosts: `login.microsoftonline.com` to sign in and
  `graph.microsoft.com` to search. Nothing else, no analytics.
- **Sign out** discards the tokens.

## How it is built

```
you type ──> QueryParser ──> KQL or OData ──> Microsoft Graph ──> results
                                                (your mailbox, on Microsoft's servers)
```

- `search/Query.kt` — turns what you type into a query Outlook understands, and
  chooses between the two ways of asking: `$search` (KQL, relevance-ranked,
  whole mailbox) when there are words to find, or `$filter` (OData, sortable)
  when the query is only constraints like unread or has-an-attachment.
- `auth/` — OAuth 2.0 authorization code flow with PKCE. Sign-in opens a Chrome
  Custom Tab, so credentials are typed into Microsoft's page in the real
  browser. PKCE is what makes that safe without a client secret: the app sends
  only a hash when it starts, and proves it holds the original when it redeems
  the code.
- `graph/GraphClient.kt` — the two Graph calls: search, and fetch one body.
  HTML bodies are asked for as plain text and flattened if they arrive as markup
  anyway.
- `MailSearchViewModel.kt` — app state, debounced searching (each keystroke
  would otherwise be a network request), token refresh.
- `ui/Screens.kt` — Jetpack Compose: setup, sign-in, results, message.

No third-party networking or auth library: `HttpURLConnection` and about 150
lines of OAuth, so the whole path a token takes is readable in one sitting.

## Building

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testDebugUnitTest   # 36 unit tests, no device needed
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

To sign with your own key instead of the debug key:

```bash
keytool -genkeypair -v -keystore mailsearch.jks -alias mailsearch \
        -keyalg RSA -keysize 2048 -validity 10000
export MAILSEARCH_KEYSTORE=$PWD/mailsearch.jks
export MAILSEARCH_KEYSTORE_PASSWORD=... MAILSEARCH_KEY_ALIAS=mailsearch
./gradlew :app:assembleRelease
```

Requirements: JDK 17+, Android SDK with platform 35 and build-tools 35. Minimum
supported phone is Android 8.0.

### Tested how far

The unit tests cover the query translation, the URL construction, the Graph
response parsing, the HTML flattening, PKCE and the token lifecycle — they run
on a plain JVM with no device or emulator. The UI and the live Graph calls have
**not** been exercised against a real device or a real mailbox; if something
misbehaves on your phone, that is where to look first.
