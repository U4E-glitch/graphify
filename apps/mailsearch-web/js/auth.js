// Signing in to Microsoft from a browser: OAuth 2.0 authorization code flow
// with PKCE.
//
// There is no secret to protect here — the page is public static files — which
// is exactly why PKCE exists. The page invents a secret, sends only its hash
// when it redirects to Microsoft, and proves it holds the original when it
// redeems the code. Anyone who intercepted the redirect would hold a code they
// cannot exchange.
//
// The redirect URI has to be registered in Azure as a **Single-page
// application** platform. That is what makes Microsoft return the CORS headers
// a browser needs on the token endpoint.

export const DEFAULT_AUTHORITY = "https://login.microsoftonline.com";
export const DEFAULT_TENANT = "common";
export const TENANT_CONSUMERS = "consumers";
export const SCOPES = "offline_access User.Read Mail.Read";

const KEY_TOKENS = "mailsearch.tokens";
const KEY_VERIFIER = "mailsearch.verifier";
const KEY_STATE = "mailsearch.state";
const KEY_SETTINGS = "mailsearch.settings";

// Refresh five minutes early, so a request started just under the wire does not
// expire halfway through.
const EXPIRY_SKEW_MS = 5 * 60 * 1000;

/* -- settings ---------------------------------------------------------- */
export function loadSettings() {
  const stored = read(localStorage, KEY_SETTINGS) || {};
  return {
    clientId: stored.clientId || "",
    tenant: stored.tenant || DEFAULT_TENANT,
  };
}

export function saveSettings(settings) {
  write(localStorage, KEY_SETTINGS, {
    clientId: (settings.clientId || "").trim(),
    tenant: (settings.tenant || DEFAULT_TENANT).trim() || DEFAULT_TENANT,
  });
}

/* -- PKCE -------------------------------------------------------------- */
export function randomString(bytes = 64) {
  const raw = new Uint8Array(bytes);
  crypto.getRandomValues(raw);
  return base64Url(raw);
}

export async function challengeFor(verifier) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

function base64Url(bytes) {
  let text = "";
  for (const byte of bytes) text += String.fromCharCode(byte);
  return btoa(text).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/* -- the flow ---------------------------------------------------------- */
export function redirectUri() {
  // The page's own address, without query or fragment — this exact string is
  // what has to be registered in Azure.
  return location.origin + location.pathname;
}

export function authorizeUrl({ clientId, tenant }, { challenge, state }) {
  const params = new URLSearchParams({
    client_id: clientId,
    response_type: "code",
    redirect_uri: redirectUri(),
    response_mode: "query",
    scope: SCOPES,
    code_challenge: challenge,
    code_challenge_method: "S256",
    state,
    prompt: "select_account",
  });
  return `${DEFAULT_AUTHORITY}/${tenant}/oauth2/v2.0/authorize?${params}`;
}

/** Start signing in: remember the secret, then hand over to Microsoft. */
export async function beginSignIn(settings) {
  const verifier = randomString();
  const state = randomString(24);
  sessionStorage.setItem(KEY_VERIFIER, verifier);
  sessionStorage.setItem(KEY_STATE, state);
  const challenge = await challengeFor(verifier);
  location.assign(authorizeUrl(settings, { challenge, state }));
}

/** What came back in the URL after Microsoft redirected here, if anything. */
export function pendingRedirect(search = location.search) {
  const params = new URLSearchParams(search);
  const code = params.get("code");
  const error = params.get("error");
  if (!code && !error) return null;
  return {
    code,
    state: params.get("state"),
    error,
    description: params.get("error_description") || "",
  };
}

/** Take the redirect's query string out of the address bar. */
export function clearRedirect() {
  history.replaceState({}, document.title, redirectUri());
}

export class AuthError extends Error {
  constructor(message, { needsSignIn = false, tenantFix = "" } = {}) {
    super(message);
    this.needsSignIn = needsSignIn;
    this.tenantFix = tenantFix;
  }
}

/**
 * Microsoft's answer when the registration accepts personal accounts only but
 * the app asked at /common/. Such a registration has to be asked at
 * /consumers/, so the app can correct itself instead of relaying the complaint.
 */
export function tenantFixFor(description, currentTenant) {
  if (currentTenant !== DEFAULT_TENANT) return "";
  const text = String(description || "").toLowerCase();
  const audienceProblem = text.includes("useraudience") ||
    (text.includes("consumer") && text.includes("common"));
  return audienceProblem ? TENANT_CONSUMERS : "";
}

/** Finish signing in: swap the code for tokens. */
export async function completeSignIn(settings, redirect, fetchImpl = fetch) {
  const verifier = sessionStorage.getItem(KEY_VERIFIER);
  const expectedState = sessionStorage.getItem(KEY_STATE);
  sessionStorage.removeItem(KEY_VERIFIER);
  sessionStorage.removeItem(KEY_STATE);

  if (redirect.error) {
    const fix = tenantFixFor(redirect.description, settings.tenant);
    if (fix) {
      throw new AuthError(
        "Your app registration is for personal Microsoft accounts only. Adjusted " +
        "the setting to match — tap Sign in with Outlook again.",
        { tenantFix: fix }
      );
    }
    const first = String(redirect.description).split("\n")[0].trim();
    throw new AuthError(
      first || (redirect.error === "access_denied" ? "Sign-in was cancelled." : redirect.error)
    );
  }
  if (!redirect.code || !verifier) {
    throw new AuthError("Sign-in did not complete. Try again.");
  }
  if (redirect.state !== expectedState) {
    // This redirect did not come from the request this page started.
    throw new AuthError("Sign-in could not be verified. Try again.");
  }

  const tokens = await exchange(settings, {
    grant_type: "authorization_code",
    client_id: settings.clientId,
    code: redirect.code,
    redirect_uri: redirectUri(),
    code_verifier: verifier,
  }, null, fetchImpl);
  storeTokens(tokens);
  return tokens;
}

async function exchange(settings, fields, previous, fetchImpl) {
  const response = await fetchImpl(
    `${DEFAULT_AUTHORITY}/${settings.tenant}/oauth2/v2.0/token`,
    {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(fields).toString(),
    }
  );
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const code = payload.error || "";
    const description = String(payload.error_description || "").split("\n")[0].trim();
    const fatal = ["invalid_grant", "invalid_client", "unauthorized_client", "interaction_required"]
      .includes(code);
    throw new AuthError(friendly(code, description, response.status), { needsSignIn: fatal });
  }
  if (!payload.access_token) {
    throw new AuthError("Microsoft returned a sign-in response with no access token.");
  }
  return {
    accessToken: payload.access_token,
    refreshToken: payload.refresh_token || (previous ? previous.refreshToken : ""),
    expiresAt: Date.now() + (Number(payload.expires_in) || 3600) * 1000,
    account: previous ? previous.account : "",
  };
}

function friendly(code, description, status) {
  if (code === "invalid_client" || description.includes("AADSTS7000218")) {
    return "Microsoft rejected the app registration. In the Azure portal, open " +
      "Authentication and set \"Allow public client flows\" to Yes.";
  }
  if (description.includes("AADSTS50011")) {
    return `The redirect address is not registered. Add ${redirectUri()} to the app ` +
      "registration under Authentication → Single-page application.";
  }
  if (description.includes("AADSTS9002326") || description.includes("cross-origin")) {
    return `The redirect address is registered under the wrong platform. In Azure, ` +
      `${redirectUri()} has to sit under **Single-page application**, not "Web".`;
  }
  if (code === "invalid_grant") return "The Outlook sign-in has expired. Please sign in again.";
  return description || `Sign-in failed (HTTP ${status}).`;
}

/* -- tokens ------------------------------------------------------------ */
export function storedTokens() {
  const stored = read(localStorage, KEY_TOKENS);
  if (!stored || (!stored.accessToken && !stored.refreshToken)) return null;
  return stored;
}

export function storeTokens(tokens) {
  write(localStorage, KEY_TOKENS, tokens);
}

export function signOut() {
  localStorage.removeItem(KEY_TOKENS);
}

export function isFresh(tokens, now = Date.now()) {
  return Boolean(tokens && tokens.accessToken && now < tokens.expiresAt - EXPIRY_SKEW_MS);
}

/**
 * A usable access token, refreshed if needed.
 *
 * Concurrent callers share one refresh, so a burst of searches cannot start
 * several at once.
 */
let refreshing = null;

export async function accessToken(settings, fetchImpl = fetch) {
  const tokens = storedTokens();
  if (!tokens) throw new AuthError("Not signed in.", { needsSignIn: true });
  if (isFresh(tokens)) return tokens.accessToken;
  if (!tokens.refreshToken) {
    throw new AuthError("The Outlook sign-in expired. Please sign in again.", { needsSignIn: true });
  }
  if (!refreshing) {
    refreshing = exchange(settings, {
      grant_type: "refresh_token",
      client_id: settings.clientId,
      refresh_token: tokens.refreshToken,
      scope: SCOPES,
    }, tokens, fetchImpl)
      .then((fresh) => {
        storeTokens(fresh);
        return fresh.accessToken;
      })
      .catch((error) => {
        if (error instanceof AuthError && error.needsSignIn) signOut();
        throw error;
      })
      .finally(() => { refreshing = null; });
  }
  return refreshing;
}

export function rememberAccount(address) {
  const tokens = storedTokens();
  if (tokens && address && tokens.account !== address) {
    storeTokens({ ...tokens, account: address });
  }
}

/* -- storage helpers --------------------------------------------------- */
function read(store, key) {
  try {
    const raw = store.getItem(key);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;  // private browsing, or someone edited it by hand
  }
}

function write(store, key, value) {
  try {
    store.setItem(key, JSON.stringify(value));
  } catch {
    /* storage full or blocked; the session simply will not persist */
  }
}
