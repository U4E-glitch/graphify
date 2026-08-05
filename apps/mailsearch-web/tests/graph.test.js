import assert from "node:assert/strict";
import { test } from "node:test";

import { GraphError, client, htmlToText, looksLikeHtml, searchUrl, toMessage } from "../js/graph.js";
import { parse } from "../js/query.js";

const TODAY = new Date(2026, 4, 10);
const urlFor = (text) => searchUrl(parse(text, TODAY));
const decoded = (url) => decodeURIComponent(url);

/* -- request building -------------------------------------------------- */
test("a word search uses the search endpoint", () => {
  const url = decoded(urlFor("invoice"));
  assert.ok(url.includes("/me/messages"));
  assert.ok(url.includes('$search="invoice"'));
  // Graph rejects $orderby together with $search.
  assert.ok(!url.includes("$orderby"));
});

test("a phrase keeps its quotes escaped for the OData parser", () => {
  assert.ok(decoded(urlFor('"purchase order"')).includes('$search="\\"purchase order\\""'));
});

test("a filter-only query sorts newest first", () => {
  const url = decoded(urlFor("is:unread"));
  assert.ok(url.includes("$filter=isRead eq false"));
  assert.ok(url.includes("$orderby=receivedDateTime desc"));
  assert.ok(!url.includes("$search"));
});

test("the requested fields are asked for explicitly", () => {
  const url = decoded(urlFor("invoice"));
  assert.ok(url.includes("bodyPreview"));
  assert.ok(url.includes("receivedDateTime"));
  assert.ok(url.includes("$top=25"));
});

test("nothing is left unencoded in the query string", () => {
  const raw = urlFor("from:alice invoice");
  assert.ok(!raw.includes(" "));
  assert.ok(raw.includes("%20") || raw.includes("+"));
});

/* -- parsing ----------------------------------------------------------- */
const SAMPLE = {
  id: "AAMk123",
  subject: "Invoice 2026-041",
  from: { emailAddress: { name: "Contoso Billing", address: "billing@contoso.com" } },
  toRecipients: [
    { emailAddress: { name: "Me", address: "me@example.com" } },
    { emailAddress: { name: "", address: "cc@example.com" } },
  ],
  receivedDateTime: "2026-05-01T09:00:00Z",
  bodyPreview: "Your   invoice\nis attached",
  hasAttachments: true,
  isRead: false,
  webLink: "https://outlook.office.com/mail/AAMk123",
};

test("a graph message becomes a usable result row", () => {
  const message = toMessage(SAMPLE);
  assert.equal(message.id, "AAMk123");
  assert.equal(message.fromName, "Contoso Billing");
  assert.equal(message.toRecipients, "Me <me@example.com>, cc@example.com");
  assert.equal(message.preview, "Your invoice is attached");
  assert.equal(message.hasAttachments, true);
  assert.equal(message.isRead, false);
});

test("missing fields do not throw", () => {
  const message = toMessage({ id: "x" });
  assert.equal(message.id, "x");
  assert.equal(message.fromAddress, "");
  assert.equal(message.isRead, true);
});

/* -- html -------------------------------------------------------------- */
test("html bodies are reduced to readable text", () => {
  const html = "<html><head><style>p{color:red}</style></head><body>" +
    "<p>Hello <b>Bob</b>,</p><p>The invoice is attached.</p>" +
    "<script>alert(1)</script></body></html>";
  const text = htmlToText(html);
  assert.ok(text.includes("Hello Bob,"));
  assert.ok(text.includes("The invoice is attached."));
  assert.ok(!text.includes("color:red"));
  assert.ok(!text.includes("alert(1)"));
  assert.ok(!text.includes("<"));
});

test("entities are decoded", () => {
  assert.equal(htmlToText("Tom &amp; Jerry &mdash; 5 &gt; 3"), "Tom & Jerry — 5 > 3");
});

test("markup wearing a text content type is still detected", () => {
  assert.ok(looksLikeHtml("<div><p>Hi there</p></div>"));
  assert.ok(!looksLikeHtml("Costs < 500 EUR and 5 > 3"));
  assert.ok(!looksLikeHtml("plain text"));
});

/* -- the client against a scripted Microsoft ---------------------------- */
function fakeFetch(routes) {
  const calls = [];
  return {
    calls,
    fetch: async (url, options = {}) => {
      calls.push({ url, options });
      for (const [fragment, reply] of routes) {
        if (url.includes(fragment)) {
          const { status = 200, body = {} } = reply;
          return {
            ok: status >= 200 && status < 300,
            status,
            json: async () => body,
          };
        }
      }
      throw new Error(`no route for ${url}`);
    },
  };
}

test("a search returns rows and the link to the next page", async () => {
  const transport = fakeFetch([
    ["/me/messages?", {
      body: { value: [SAMPLE], "@odata.nextLink": "https://graph/next-page" },
    }],
  ]);
  const graph = client(async () => "token-1", transport.fetch);
  const found = await graph.search(parse("invoice", TODAY));

  assert.equal(found.messages.length, 1);
  assert.equal(found.nextLink, "https://graph/next-page");
  assert.equal(transport.calls[0].options.headers.Authorization, "Bearer token-1");
  assert.ok(transport.calls[0].options.headers.Prefer.includes("text"));
});

test("a body is fetched only when a message is opened", async () => {
  const transport = fakeFetch([
    ["/me/messages/", { body: { body: { contentType: "html", content: "<p>Hi <b>there</b></p>" } } }],
  ]);
  const graph = client(async () => "t", transport.fetch);
  assert.equal(await graph.body("AAMk123"), "Hi there");
});

test("a rejected token asks for a new sign-in", async () => {
  const transport = fakeFetch([
    ["/me/messages?", { status: 401, body: { error: { code: "InvalidAuthenticationToken" } } }],
  ]);
  const graph = client(async () => "t", transport.fetch);
  await assert.rejects(
    () => graph.search(parse("invoice", TODAY)),
    (error) => error instanceof GraphError && error.needsSignIn === true
  );
});

test("throttling and outages say something a person can act on", async () => {
  for (const [status, expected] of [[429, "rate-limiting"], [503, "trouble"]]) {
    const transport = fakeFetch([["/me/messages?", { status, body: {} }]]);
    const graph = client(async () => "t", transport.fetch);
    await assert.rejects(
      () => graph.search(parse("invoice", TODAY)),
      (error) => error.message.includes(expected)
    );
  }
});

test("a dropped connection is not reported as a search failure", async () => {
  const graph = client(async () => "t", async () => { throw new TypeError("Failed to fetch"); });
  await assert.rejects(
    () => graph.search(parse("invoice", TODAY)),
    (error) => error instanceof GraphError && error.message.includes("No connection")
  );
});
