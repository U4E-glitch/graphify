// The screens: setup, sign-in, search, message. No framework.

import * as auth from "./auth.js";
import { GraphError, client } from "./graph.js";
import { MODE, parse } from "./query.js";

const DEBOUNCE_MS = 450;
const PAGE_SIZE = 25;

const el = {};
for (const id of [
  "setup", "setup-form", "client-id", "tenant", "setup-error",
  "redirect-uri", "copy-redirect",
  "signin", "signin-btn", "signin-error", "signin-account", "edit-setup",
  "search", "q", "clear", "summary", "notes", "results", "empty", "more", "spinner",
  "message", "msg-subject", "msg-meta", "msg-body", "msg-back", "msg-open",
  "account", "menu-btn", "menu", "recent", "recent-list",
]) el[id] = document.getElementById(id);

let settings = auth.loadSettings();
let graph = client(() => auth.accessToken(settings));

const state = {
  query: "",
  terms: [],
  results: [],
  nextLink: "",
  searching: false,
  opened: null,
  seq: 0,
  recent: readRecent(),
};

/* -- helpers ----------------------------------------------------------- */
const show = (node, visible) => { if (node) node.hidden = !visible; };

function screen(name) {
  for (const key of ["setup", "signin", "search", "message"]) show(el[key], key === name);
  show(el.account, name === "search");
  show(el["menu-btn"], name === "search");
  show(el.menu, false);
}

function text(node, value) { node.textContent = value == null ? "" : String(value); }

/**
 * Build a text node with the searched-for words marked.
 *
 * Mail text is only ever added as text nodes — never parsed as HTML — so a
 * message cannot inject anything into this page.
 */
function highlighted(value, terms) {
  const fragment = document.createDocumentFragment();
  const source = String(value || "");
  const wanted = terms.filter((term) => term && term.length >= 2).map((t) => t.toLowerCase());
  if (!wanted.length) {
    fragment.append(document.createTextNode(source));
    return fragment;
  }

  const lowered = source.toLowerCase();
  let cursor = 0;
  while (cursor < source.length) {
    let at = -1;
    let length = 0;
    for (const term of wanted) {
      const found = lowered.indexOf(term, cursor);
      if (found !== -1 && (at === -1 || found < at)) {
        at = found;
        length = term.length;
      }
    }
    if (at === -1) {
      fragment.append(document.createTextNode(source.slice(cursor)));
      break;
    }
    if (at > cursor) fragment.append(document.createTextNode(source.slice(cursor, at)));
    const mark = document.createElement("mark");
    mark.textContent = source.slice(at, at + length);
    fragment.append(mark);
    cursor = at + length;
  }
  return fragment;
}

function shortDate(iso) {
  if (!iso) return "";
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) return iso;
  const now = new Date();
  if (when.toDateString() === now.toDateString()) {
    return when.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }
  const options = when.getFullYear() === now.getFullYear()
    ? { day: "numeric", month: "short" }
    : { day: "numeric", month: "short", year: "2-digit" };
  return when.toLocaleDateString([], options);
}

/* -- routing ----------------------------------------------------------- */
async function start() {
  const redirect = auth.pendingRedirect();
  if (redirect) {
    auth.clearRedirect();
    screen("signin");
    el["signin-btn"].disabled = true;
    text(el["signin-error"], "Finishing sign-in…");
    show(el["signin-error"], true);
    try {
      await auth.completeSignIn(settings, redirect);
      const address = await graph.account().catch(() => "");
      if (address) auth.rememberAccount(address);
      openSearch();
      return;
    } catch (error) {
      if (error.tenantFix) {
        settings = { ...settings, tenant: error.tenantFix };
        auth.saveSettings(settings);
      }
      el["signin-btn"].disabled = false;
      text(el["signin-error"], error.message);
      show(el["signin-error"], true);
      return;
    }
  }

  if (!settings.clientId) return openSetup();
  if (!auth.storedTokens()) return openSignIn();
  openSearch();
}

function openSetup() {
  // Registering the wrong address is the most common way to get stuck, so show
  // the one this page will actually send.
  text(el["redirect-uri"], auth.redirectUri());
  el["client-id"].value = settings.clientId;
  el.tenant.value = settings.tenant;
  show(el["setup-error"], false);
  screen("setup");
}

function openSignIn(message = "") {
  text(el["signin-account"], auth.storedTokens()?.account || "");
  text(el["signin-error"], message);
  show(el["signin-error"], Boolean(message));
  el["signin-btn"].disabled = false;
  screen("signin");
}

function openSearch() {
  text(el.account, auth.storedTokens()?.account || "");
  screen("search");
  renderRecent();
  el.q.focus({ preventScroll: true });
}

/* -- searching --------------------------------------------------------- */
let debounce = null;

function onType() {
  const value = el.q.value;
  show(el.clear, value.length > 0);
  clearTimeout(debounce);
  if (!value.trim()) {
    state.results = [];
    state.query = "";
    el.results.replaceChildren();
    show(el.more, false);
    show(el.spinner, false);
    text(el.summary, "");
    el.notes.replaceChildren();
    renderRecent();
    return;
  }
  // Each search is a request to Microsoft, so wait for a pause in typing.
  debounce = setTimeout(() => runSearch(value), DEBOUNCE_MS);
}

function searchNow() {
  clearTimeout(debounce);
  if (el.q.value.trim()) runSearch(el.q.value);
  el.q.blur();  // let the on-screen keyboard get out of the way
}

async function runSearch(raw) {
  const parsed = parse(raw);
  state.query = raw;
  state.terms = parsed.terms;
  renderNotes(parsed.notes);
  if (parsed.mode === MODE.EMPTY) return;

  const mine = ++state.seq;
  state.searching = true;
  show(el.spinner, true);
  show(el.recent, false);
  show(el.empty, false);

  const started = performance.now();
  try {
    const found = await graph.search(parsed, PAGE_SIZE);
    if (mine !== state.seq) return;  // a newer keystroke already won
    state.results = found.messages;
    state.nextLink = found.nextLink;
    rememberSearch(raw);
    renderResults(true, performance.now() - started);
  } catch (error) {
    if (mine !== state.seq) return;
    failed(error);
  } finally {
    if (mine === state.seq) {
      state.searching = false;
      show(el.spinner, false);
    }
  }
}

async function loadMore() {
  if (!state.nextLink) return;
  el.more.disabled = true;
  text(el.more, "Loading…");
  try {
    const found = await graph.more(state.nextLink);
    const seen = new Set(state.results.map((row) => row.id));
    state.results = state.results.concat(found.messages.filter((row) => !seen.has(row.id)));
    state.nextLink = found.nextLink;
    renderResults(false);
  } catch (error) {
    failed(error);
  } finally {
    el.more.disabled = false;
  }
}

function failed(error) {
  el.results.replaceChildren();
  show(el.more, false);
  text(el.summary, "");
  show(el.empty, true);
  text(el.empty, error.message);
  if ((error instanceof GraphError || error instanceof auth.AuthError) && error.needsSignIn) {
    auth.signOut();
    openSignIn(error.message);
  }
}

/* -- rendering --------------------------------------------------------- */
function renderResults(replace, tookMs) {
  if (replace) el.results.replaceChildren();
  const from = replace ? 0 : el.results.children.length;
  for (const row of state.results.slice(from)) el.results.append(resultRow(row));

  const count = state.results.length;
  if (replace) {
    text(el.summary, count
      ? `${count}${state.nextLink ? "+" : ""} ${count === 1 ? "match" : "matches"}` +
        (tookMs ? ` · ${Math.round(tookMs)} ms` : "")
      : "");
  }
  show(el.empty, count === 0);
  if (count === 0) text(el.empty, "No mail matches that.");
  show(el.more, Boolean(state.nextLink));
  text(el.more, "Show more results");
}

function resultRow(row) {
  const item = document.createElement("button");
  item.type = "button";
  item.className = "hit" + (row.isRead ? "" : " unread");

  const top = document.createElement("div");
  top.className = "hit-top";
  const subject = document.createElement("span");
  subject.className = "hit-subject";
  subject.append(highlighted(row.subject || "(no subject)", state.terms));
  const date = document.createElement("span");
  date.className = "hit-date";
  text(date, shortDate(row.receivedAt));
  top.append(subject, date);

  const meta = document.createElement("div");
  meta.className = "hit-meta";
  const sender = document.createElement("span");
  text(sender, row.fromName || row.fromAddress || "(unknown sender)");
  meta.append(sender);
  if (row.hasAttachments) {
    const clip = document.createElement("span");
    text(clip, "📎");
    meta.append(clip);
  }

  item.append(top, meta);
  if (row.preview) {
    const preview = document.createElement("div");
    preview.className = "hit-preview";
    preview.append(highlighted(row.preview, state.terms));
    item.append(preview);
  }
  item.addEventListener("click", () => openMessage(row));
  return item;
}

function renderNotes(notes) {
  el.notes.replaceChildren();
  for (const note of notes || []) {
    const line = document.createElement("div");
    line.className = "note";
    text(line, note);
    el.notes.append(line);
  }
}

/* -- one message ------------------------------------------------------- */
async function openMessage(row) {
  state.opened = row;
  text(el["msg-subject"], row.subject || "(no subject)");
  el["msg-meta"].replaceChildren(
    metaLine("From", [row.fromName, row.fromAddress].filter(Boolean).join(" · ")),
    metaLine("To", row.toRecipients),
    metaLine("Date", row.receivedAt ? new Date(row.receivedAt).toLocaleString() : ""),
  );
  el["msg-open"].href = row.webLink || "#";
  show(el["msg-open"], Boolean(row.webLink));
  el["msg-body"].replaceChildren(document.createTextNode(row.preview || "Loading…"));
  screen("message");
  history.pushState({ message: row.id }, "");

  try {
    const body = await graph.body(row.id);
    if (state.opened?.id !== row.id) return;
    el["msg-body"].replaceChildren(
      highlighted(body || "(this message has no text)", state.terms)
    );
  } catch (error) {
    if (state.opened?.id === row.id) {
      el["msg-body"].replaceChildren(document.createTextNode(error.message));
    }
  }
}

function metaLine(label, value) {
  const line = document.createElement("div");
  if (!value) { line.hidden = true; return line; }
  const strong = document.createElement("b");
  text(strong, `${label}: `);
  line.append(strong, document.createTextNode(value));
  return line;
}

function closeMessage() {
  state.opened = null;
  screen("search");
}

/* -- recent searches --------------------------------------------------- */
function readRecent() {
  try {
    return JSON.parse(localStorage.getItem("mailsearch.recent") || "[]").slice(0, 8);
  } catch {
    return [];
  }
}

function rememberSearch(query) {
  const trimmed = query.trim();
  if (trimmed.length < 2) return;
  state.recent = [trimmed, ...state.recent.filter((item) => item !== trimmed)].slice(0, 8);
  try {
    localStorage.setItem("mailsearch.recent", JSON.stringify(state.recent));
  } catch { /* ignore */ }
}

function renderRecent() {
  el["recent-list"].replaceChildren();
  for (const entry of state.recent) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "recent-item";
    text(button, entry);
    button.addEventListener("click", () => {
      el.q.value = entry;
      show(el.clear, true);
      searchNow();
    });
    el["recent-list"].append(button);
  }
  show(el.recent, state.recent.length > 0 && !state.query);
}

/* -- wiring ------------------------------------------------------------ */
el["setup-form"].addEventListener("submit", (event) => {
  event.preventDefault();
  const clientId = el["client-id"].value.trim();
  if (clientId.length < 10) {
    text(el["setup-error"], "That does not look like an Application (client) ID.");
    show(el["setup-error"], true);
    return;
  }
  settings = { clientId, tenant: el.tenant.value.trim() || auth.DEFAULT_TENANT };
  auth.saveSettings(settings);
  graph = client(() => auth.accessToken(settings));
  openSignIn();
});

el["signin-btn"].addEventListener("click", async () => {
  el["signin-btn"].disabled = true;
  try {
    await auth.beginSignIn(settings);
  } catch (error) {
    el["signin-btn"].disabled = false;
    text(el["signin-error"], error.message);
    show(el["signin-error"], true);
  }
});

el["copy-redirect"].addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(auth.redirectUri());
    text(el["copy-redirect"], "Copied");
    setTimeout(() => text(el["copy-redirect"], "Copy"), 1500);
  } catch {
    // Clipboard access can be refused; the address is on screen to read anyway.
    getSelection()?.selectAllChildren(el["redirect-uri"]);
  }
});

el["edit-setup"].addEventListener("click", openSetup);
el.q.addEventListener("input", onType);
el.q.addEventListener("keydown", (event) => { if (event.key === "Enter") searchNow(); });
el.clear.addEventListener("click", () => {
  el.q.value = "";
  onType();
  el.q.focus();
});
el.more.addEventListener("click", loadMore);
el["msg-back"].addEventListener("click", () => history.back());
el["menu-btn"].addEventListener("click", (event) => {
  event.stopPropagation();
  show(el.menu, el.menu.hidden);
});
document.addEventListener("click", () => show(el.menu, false));
el.menu.addEventListener("click", (event) => {
  const action = event.target?.dataset?.action;
  if (action === "signout") {
    auth.signOut();
    openSignIn();
  } else if (action === "settings") {
    openSetup();
  }
});

// The phone's back gesture should leave a message, not the app.
window.addEventListener("popstate", () => {
  if (state.opened) closeMessage();
});

start();
