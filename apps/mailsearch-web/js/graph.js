// Reading mail from Microsoft Graph. Nothing is stored: every search is
// answered by Microsoft's own index over the mailbox.

import { MODE } from "./query.js";

export const ENDPOINT = "https://graph.microsoft.com/v1.0";
export const SELECT =
  "id,subject,from,toRecipients,receivedDateTime,bodyPreview,hasAttachments,isRead,webLink";

export class GraphError extends Error {
  constructor(message, { status = 0, needsSignIn = false } = {}) {
    super(message);
    this.status = status;
    this.needsSignIn = needsSignIn;
  }
}

/** The URL that answers a parsed query. */
export function searchUrl(query, pageSize = 25) {
  const params = [["$select", SELECT], ["$top", String(pageSize)]];
  if (query.mode === MODE.SEARCH) {
    // $search takes a KQL string wrapped in quotes; quotes inside it (a phrase)
    // are escaped for the OData parser. $orderby is not allowed alongside it —
    // results come back by relevance, which is what a search should do.
    params.push(["$search", `"${query.kql.replace(/"/g, '\\"')}"`]);
  } else if (query.mode === MODE.FILTER) {
    params.push(["$filter", query.filter], ["$orderby", "receivedDateTime desc"]);
  } else {
    params.push(["$orderby", "receivedDateTime desc"]);
  }
  return `${ENDPOINT}/me/messages?${encodeQuery(params)}`;
}

/**
 * Percent-encode a query string, with spaces as %20.
 *
 * Not URLSearchParams: that writes spaces as "+", which OData reads literally
 * inside a $filter value rather than as a space.
 */
function encodeQuery(pairs) {
  // Keys are our own literals ($select, $search, …) and are left as they are,
  // so the URL stays readable; only the values need encoding.
  return pairs.map(([key, value]) => `${key}=${encodeURIComponent(value)}`).join("&");
}

/**
 * @param {() => Promise<string>} token supplies a valid access token
 */
export function client(token, fetchImpl = fetch) {
  async function request(url, { bodyAsText = false } = {}) {
    const headers = { Authorization: `Bearer ${await token()}`, Accept: "application/json" };
    // Ask Outlook to convert HTML bodies server-side, so what arrives is prose.
    if (bodyAsText) headers.Prefer = 'outlook.body-content-type="text"';

    let response;
    try {
      response = await fetchImpl(url, { headers });
    } catch (cause) {
      throw new GraphError("No connection. Check your internet and try again.", { status: 0 });
    }
    if (!response.ok) throw await translate(response);
    return response.json();
  }

  return {
    async search(query, pageSize = 25) {
      return page(await request(searchUrl(query, pageSize), { bodyAsText: true }));
    },
    async more(nextLink) {
      return page(await request(nextLink, { bodyAsText: true }));
    },
    async body(messageId) {
      const url = `${ENDPOINT}/me/messages/${encodeURIComponent(messageId)}` +
        "?$select=body,bodyPreview";
      const payload = await request(url, { bodyAsText: true });
      const content = payload.body?.content || "";
      const isHtml = String(payload.body?.contentType || "").toLowerCase() === "html";
      if (!content) return payload.bodyPreview || "";
      return isHtml || looksLikeHtml(content) ? htmlToText(content) : content;
    },
    async account() {
      const payload = await request(`${ENDPOINT}/me?$select=mail,userPrincipalName,displayName`);
      return payload.mail || payload.userPrincipalName || "";
    },
  };
}

function page(payload) {
  const values = Array.isArray(payload.value) ? payload.value : [];
  return {
    messages: values.filter((item) => item && item.id).map(toMessage),
    nextLink: payload["@odata.nextLink"] || "",
  };
}

export function toMessage(json) {
  const from = json.from?.emailAddress || {};
  return {
    id: json.id,
    subject: json.subject || "",
    fromName: from.name || "",
    fromAddress: from.address || "",
    toRecipients: addressList(json.toRecipients),
    receivedAt: json.receivedDateTime || "",
    preview: String(json.bodyPreview || "").replace(/\s+/g, " ").trim(),
    hasAttachments: Boolean(json.hasAttachments),
    isRead: json.isRead !== false,
    webLink: json.webLink || "",
  };
}

function addressList(entries) {
  if (!Array.isArray(entries)) return "";
  return entries
    .map((entry) => {
      const address = entry?.emailAddress || {};
      const name = address.name || "";
      const mail = address.address || "";
      if (name && mail && name.toLowerCase() !== mail.toLowerCase()) return `${name} <${mail}>`;
      return name || mail;
    })
    .filter(Boolean)
    .join(", ");
}

async function translate(response) {
  let payload = {};
  try {
    payload = await response.json();
  } catch {
    /* an error body is not always JSON */
  }
  const message = payload?.error?.message || "";
  if (response.status === 401 || response.status === 403) {
    return new GraphError(
      "Outlook refused the request. Sign in again, and check the app registration " +
      "has the Mail.Read permission.",
      { status: response.status, needsSignIn: true }
    );
  }
  if (response.status === 429) {
    return new GraphError("Outlook is rate-limiting requests. Try again in a moment.",
      { status: 429 });
  }
  if (response.status >= 500) {
    return new GraphError("Outlook is having trouble right now. Try again.",
      { status: response.status });
  }
  return new GraphError(message || `Search failed (HTTP ${response.status}).`,
    { status: response.status });
}

/* -- html -------------------------------------------------------------- */
const HTML_HINT = /<\s*(?:html|body|div|p|br|table|span|a\s|img\s|font|style)[^>]*>/gi;

export function looksLikeHtml(text) {
  if (!text || !text.includes("<")) return false;
  return (String(text).match(HTML_HINT) || []).length >= 2;
}

/**
 * Flatten an HTML body into readable text.
 *
 * Deliberately not via innerHTML: this never builds DOM from mail, so nothing
 * in a message can run or load anything.
 */
export function htmlToText(html) {
  return String(html)
    .replace(/<\s*(script|style|head)[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gi, " ")
    .replace(/<\s*\/?\s*(p|div|br|tr|li|table|blockquote|h[1-6]|ul|ol|pre|section|article)[^>]*>/gi, "\n")
    .replace(/<[^>]+>/g, "")
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&(#39|apos);/gi, "'")
    .replace(/&mdash;/gi, "—")
    .replace(/&ndash;/gi, "–")
    .replace(/&hellip;/gi, "…")
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)))
    .replace(/[ \t ]+/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .split("\n")
    .map((line) => line.trim())
    .join("\n")
    .trim();
}
