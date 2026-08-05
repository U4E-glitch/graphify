import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";

// The auth module reads browser globals at call time, so a minimal stand-in is
// enough to exercise the real logic in node.
class MemoryStorage {
  constructor() { this.data = new Map(); }
  getItem(key) { return this.data.has(key) ? this.data.get(key) : null; }
  setItem(key, value) { this.data.set(key, String(value)); }
  removeItem(key) { this.data.delete(key); }
}

globalThis.localStorage = new MemoryStorage();
globalThis.sessionStorage = new MemoryStorage();
globalThis.location = { origin: "https://example.github.io", pathname: "/mail/", search: "" };
globalThis.btoa = (text) => Buffer.from(text, "binary").toString("base64");

const auth = await import("../js/auth.js");

beforeEach(() => {
  globalThis.localStorage = new MemoryStorage();
  globalThis.sessionStorage = new MemoryStorage();
});

/* -- PKCE -------------------------------------------------------------- */
test("challenge matches the worked example in RFC 7636", async () => {
  const verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
  assert.equal(await auth.challengeFor(verifier), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
});

test("generated verifiers are long, url-safe and never repeat", async () => {
  const first = auth.randomString();
  const second = auth.randomString();
  assert.notEqual(first, second);
  assert.ok(first.length >= 43 && first.length <= 128);
  assert.match(first, /^[A-Za-z0-9\-._~]+$/);
});

/* -- the authorization URL --------------------------------------------- */
test("the sign-in url carries everything Microsoft needs", async () => {
  const verifier = auth.randomString();
  const challenge = await auth.challengeFor(verifier);
  const url = auth.authorizeUrl(
    { clientId: "client-abc", tenant: "common" },
    { challenge, state: "state-1" }
  );
  const parsed = new URL(url);

  assert.equal(parsed.origin + parsed.pathname,
    "https://login.microsoftonline.com/common/oauth2/v2.0/authorize");
  assert.equal(parsed.searchParams.get("client_id"), "client-abc");
  assert.equal(parsed.searchParams.get("response_type"), "code");
  assert.equal(parsed.searchParams.get("redirect_uri"), "https://example.github.io/mail/");
  assert.equal(parsed.searchParams.get("code_challenge"), challenge);
  assert.equal(parsed.searchParams.get("code_challenge_method"), "S256");
  assert.ok(parsed.searchParams.get("scope").includes("Mail.Read"));
  assert.ok(parsed.searchParams.get("scope").includes("offline_access"));
  // The verifier is the secret half; it must never leave the device here.
  assert.ok(!url.includes(verifier));
});

test("the redirect uri is the page's own address, with no query attached", () => {
  globalThis.location = { origin: "https://example.github.io", pathname: "/mail/", search: "?code=x" };
  assert.equal(auth.redirectUri(), "https://example.github.io/mail/");
});

/* -- reading the redirect ---------------------------------------------- */
test("a redirect carrying a code is recognised", () => {
  const pending = auth.pendingRedirect("?code=abc&state=xyz");
  assert.equal(pending.code, "abc");
  assert.equal(pending.state, "xyz");
});

test("a page load with no redirect is not mistaken for one", () => {
  assert.equal(auth.pendingRedirect(""), null);
  assert.equal(auth.pendingRedirect("?other=1"), null);
});

/* -- the audience mismatch --------------------------------------------- */
const AUDIENCE_ERROR =
  "AADSTS500200: The request is not valid for the application's 'userAudience' " +
  "configuration. In order to use /common/ endpoint, the application must not be " +
  "configured with 'Consumer' as the user audience.";

test("a personal-accounts-only registration is redirected to the consumers endpoint", () => {
  assert.equal(auth.tenantFixFor(AUDIENCE_ERROR, "common"), "consumers");
});

test("a tenant the user chose deliberately is left alone", () => {
  assert.equal(auth.tenantFixFor(AUDIENCE_ERROR, "consumers"), "");
  assert.equal(auth.tenantFixFor(AUDIENCE_ERROR, "contoso.onmicrosoft.com"), "");
});

test("unrelated sign-in failures do not change the tenant", () => {
  assert.equal(auth.tenantFixFor("AADSTS50011: redirect mismatch", "common"), "");
  assert.equal(auth.tenantFixFor("", "common"), "");
});

/* -- completing sign-in ------------------------------------------------ */
function fakeTokenEndpoint(reply) {
  const calls = [];
  return {
    calls,
    fetch: async (url, options) => {
      calls.push({ url, body: new URLSearchParams(options.body) });
      const { status = 200, body = {} } = reply;
      return { ok: status >= 200 && status < 300, status, json: async () => body };
    },
  };
}

test("a code is exchanged for tokens and stored", async () => {
  const settings = { clientId: "c", tenant: "common" };
  sessionStorage.setItem("mailsearch.verifier", "the-verifier");
  sessionStorage.setItem("mailsearch.state", "st");
  const transport = fakeTokenEndpoint({
    body: { access_token: "at-1", refresh_token: "rt-1", expires_in: 3600 },
  });

  const tokens = await auth.completeSignIn(
    settings, { code: "the-code", state: "st", error: null, description: "" }, transport.fetch
  );

  assert.equal(tokens.accessToken, "at-1");
  assert.equal(transport.calls[0].body.get("grant_type"), "authorization_code");
  assert.equal(transport.calls[0].body.get("code_verifier"), "the-verifier");
  assert.equal(auth.storedTokens().refreshToken, "rt-1");
  // The one-time secrets must not linger after use.
  assert.equal(sessionStorage.getItem("mailsearch.verifier"), null);
});

test("a redirect whose state does not match is refused", async () => {
  sessionStorage.setItem("mailsearch.verifier", "v");
  sessionStorage.setItem("mailsearch.state", "expected");
  await assert.rejects(
    () => auth.completeSignIn(
      { clientId: "c", tenant: "common" },
      { code: "c", state: "forged", error: null, description: "" },
      fakeTokenEndpoint({}).fetch
    ),
    /could not be verified/
  );
});

test("the audience error arrives as a tenant fix, not a raw complaint", async () => {
  await assert.rejects(
    () => auth.completeSignIn(
      { clientId: "c", tenant: "common" },
      { code: null, state: null, error: "invalid_request", description: AUDIENCE_ERROR },
      fakeTokenEndpoint({}).fetch
    ),
    (error) => error.tenantFix === "consumers" && /personal Microsoft accounts only/.test(error.message)
  );
});

test("a redirect URI registered as Web rather than SPA is explained", async () => {
  sessionStorage.setItem("mailsearch.verifier", "v");
  sessionStorage.setItem("mailsearch.state", "st");
  const transport = fakeTokenEndpoint({
    status: 400,
    body: {
      error: "invalid_request",
      error_description: "AADSTS9002326: Cross-origin token redemption is permitted only " +
        "for the 'Single-Page Application' client-type.",
    },
  });
  await assert.rejects(
    () => auth.completeSignIn(
      { clientId: "c", tenant: "common" },
      { code: "c", state: "st", error: null, description: "" },
      transport.fetch
    ),
    /Single-page application/
  );
});

/* -- tokens ------------------------------------------------------------ */
test("a fresh token is reused without calling Microsoft", async () => {
  auth.storeTokens({ accessToken: "still-good", refreshToken: "rt", expiresAt: Date.now() + 3.6e6 });
  const transport = fakeTokenEndpoint({ status: 500 });
  assert.equal(await auth.accessToken({ clientId: "c", tenant: "common" }, transport.fetch),
    "still-good");
  assert.equal(transport.calls.length, 0);
});

test("an expired token is refreshed silently", async () => {
  auth.storeTokens({ accessToken: "stale", refreshToken: "rt-1", expiresAt: Date.now() - 1000 });
  const transport = fakeTokenEndpoint({
    body: { access_token: "at-2", refresh_token: "rt-2", expires_in: 3600 },
  });
  assert.equal(await auth.accessToken({ clientId: "c", tenant: "common" }, transport.fetch), "at-2");
  assert.equal(transport.calls[0].body.get("grant_type"), "refresh_token");
  assert.equal(auth.storedTokens().refreshToken, "rt-2");
});

test("a refresh response without a new refresh token keeps the old one", async () => {
  auth.storeTokens({ accessToken: "stale", refreshToken: "rt-1", expiresAt: 0, account: "me@x" });
  const transport = fakeTokenEndpoint({ body: { access_token: "at-2", expires_in: 3600 } });
  await auth.accessToken({ clientId: "c", tenant: "common" }, transport.fetch);
  assert.equal(auth.storedTokens().refreshToken, "rt-1");
  assert.equal(auth.storedTokens().account, "me@x");
});

test("a revoked session is cleared and asks for a new sign-in", async () => {
  auth.storeTokens({ accessToken: "stale", refreshToken: "revoked", expiresAt: 0 });
  const transport = fakeTokenEndpoint({
    status: 400,
    body: { error: "invalid_grant", error_description: "AADSTS700082: expired" },
  });
  await assert.rejects(
    () => auth.accessToken({ clientId: "c", tenant: "common" }, transport.fetch),
    (error) => error.needsSignIn === true
  );
  assert.equal(auth.storedTokens(), null);
});

test("parallel searches share one refresh", async () => {
  auth.storeTokens({ accessToken: "stale", refreshToken: "rt-1", expiresAt: 0 });
  const transport = fakeTokenEndpoint({ body: { access_token: "at-2", expires_in: 3600 } });
  const settings = { clientId: "c", tenant: "common" };
  const results = await Promise.all([
    auth.accessToken(settings, transport.fetch),
    auth.accessToken(settings, transport.fetch),
    auth.accessToken(settings, transport.fetch),
  ]);
  assert.deepEqual(results, ["at-2", "at-2", "at-2"]);
  assert.equal(transport.calls.length, 1);
});

test("a token is treated as stale before it actually expires", () => {
  const now = 1_000_000_000;
  assert.ok(auth.isFresh({ accessToken: "at", expiresAt: now + 40 * 60_000 }, now));
  // Inside the safety margin: refresh rather than start a doomed request.
  assert.ok(!auth.isFresh({ accessToken: "at", expiresAt: now + 60_000 }, now));
  assert.ok(!auth.isFresh({ accessToken: "", expiresAt: now + 40 * 60_000 }, now));
});

test("signing out forgets the tokens", () => {
  auth.storeTokens({ accessToken: "a", refreshToken: "b", expiresAt: 0 });
  auth.signOut();
  assert.equal(auth.storedTokens(), null);
});

test("settings round-trip, with a default tenant", () => {
  auth.saveSettings({ clientId: "  abc-123  ", tenant: "" });
  assert.deepEqual(auth.loadSettings(), { clientId: "abc-123", tenant: "common" });
});
