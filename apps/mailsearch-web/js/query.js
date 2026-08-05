// Turns what a person types into a query Outlook's own servers can run.
//
// Nothing is downloaded to search: Microsoft already indexes the mailbox, so
// the app sends a query and receives only the matches. There are two ways to
// ask, and choosing between them is this file's job:
//
//   SEARCH  — KQL in $search, whenever there are words to look for. Covers the
//             whole mailbox and ranks by relevance, but cannot sort by date or
//             filter on read state.
//   FILTER  — OData $filter, when the query is only constraints (unread, has an
//             attachment, a date range). That one can sort, so results come
//             back newest first.

export const MODE = { SEARCH: "search", FILTER: "filter", EMPTY: "empty" };

// from:alice | subject:"end of year" | "quoted phrase" | bare-word
const TOKEN = /(-)?(?:([A-Za-z_]+):(?:"([^"]*)"|([^\s"]*))|"([^"]*)"|(\S+))/g;

const TEXT_FIELDS = {
  from: "from", sender: "from",
  to: "to", recipient: "to", recipients: "to",
  cc: "cc",
  subject: "subject", title: "subject",
  body: "body", content: "body",
  participants: "participants",
  attachment: "attachment", filename: "attachment",
};

const RELATIVE_UNITS = { d: 1, w: 7, m: 30, y: 365 };

/**
 * @param {string} text what the user typed
 * @param {Date} [today] fixed "now", so tests are not time-dependent
 */
export function parse(text, today = new Date()) {
  if (!text || !text.trim()) return { mode: MODE.EMPTY, kql: "", filter: "", terms: [], notes: [] };

  const positives = [];
  const negatives = [];
  const terms = [];
  const notes = [];
  const filters = [];
  let freeText = false;
  let pendingOr = false;
  let readStateAsked = false;

  TOKEN.lastIndex = 0;
  let match;
  while ((match = TOKEN.exec(text)) !== null) {
    const [, neg, field, quotedValue, bareValue, phrase, word] = match;
    const negated = neg === "-";

    if (word && word.toUpperCase() === "OR" && !negated) {
      pendingOr = true;
      continue;
    }

    let expression = "";
    if (field !== undefined) {
      const value = (quotedValue !== undefined ? quotedValue : bareValue || "").trim();
      const handled = fieldExpression(field, value, negated, filters, notes, today);
      expression = handled.expression;
      if (handled.readState) readStateAsked = true;
      if (handled.isText && value) {
        terms.push(...value.split(/\s+/).filter(Boolean));
        freeText = true;
      }
    } else if (phrase !== undefined) {
      if (phrase.trim()) {
        expression = quote(phrase);
        terms.push(...phrase.split(/\s+/).filter(Boolean));
        freeText = true;
      }
    } else if (word) {
      expression = kqlTerm(word);
      terms.push(word.replace(/\*+$/, ""));
      freeText = true;
    }

    if (!expression) {
      pendingOr = false;
      continue;
    }
    if (negated) {
      negatives.push(expression);
      pendingOr = false;
    } else if (pendingOr && positives.length) {
      positives[positives.length - 1] = `(${positives[positives.length - 1]} OR ${expression})`;
      pendingOr = false;
    } else {
      positives.push(expression);
    }
  }

  let kql = positives.join(" AND ");
  for (const negative of negatives) kql = kql ? `${kql} NOT ${negative}` : `NOT ${negative}`;
  kql = kql.trim();

  // Nothing to look *for* — only constraints. Those are expressible as a
  // filter, which unlike a search can be sorted.
  if (!freeText && filters.length) {
    return { mode: MODE.FILTER, kql: "", filter: filters.join(" and "), terms, notes };
  }
  if (!kql) return { mode: MODE.EMPTY, kql: "", filter: "", terms, notes };

  // Graph refuses a filter and a search on one request, so anything that only
  // exists as a filter is dropped — say so rather than quietly widening.
  if (readStateAsked) {
    notes.push(
      "Outlook's search can't narrow to read or unread mail when you also " +
      "search for words — showing all matches."
    );
  }
  return { mode: MODE.SEARCH, kql, filter: "", terms, notes };
}

function fieldExpression(rawField, value, negated, filters, notes, today) {
  const field = rawField.toLowerCase();
  const none = { expression: "", isText: false, readState: false };

  const property = TEXT_FIELDS[field];
  if (property) {
    if (!value) return none;
    return { expression: `${property}:${quoteIfNeeded(value)}`, isText: true, readState: false };
  }

  switch (field) {
    case "has": {
      if (["attachment", "attachments", "file", "files"].includes(value.toLowerCase())) {
        filters.push(`hasAttachments eq ${negated ? "false" : "true"}`);
        return { ...none, expression: `hasAttachment:${negated ? "false" : "true"}` };
      }
      return none;
    }
    case "is": {
      const state = value.toLowerCase();
      if (state === "unread") {
        filters.push(`isRead eq ${negated ? "true" : "false"}`);
        return { ...none, readState: true };
      }
      if (state === "read") {
        filters.push(`isRead eq ${negated ? "false" : "true"}`);
        return { ...none, readState: true };
      }
      if (state === "important" || state === "high") {
        filters.push("importance eq 'high'");
        return { ...none, expression: "importance:high" };
      }
      return none;
    }
    case "after":
    case "since":
    case "newer": {
      const date = readDate(value, today);
      if (!date) {
        notes.push(`Could not read the date in "${rawField}:${value}".`);
        return none;
      }
      filters.push(`receivedDateTime ge ${isoDay(date)}T00:00:00Z`);
      return { ...none, expression: `received>=${isoDay(date)}` };
    }
    case "before":
    case "until":
    case "older": {
      const date = readDate(value, today);
      if (!date) {
        notes.push(`Could not read the date in "${rawField}:${value}".`);
        return none;
      }
      filters.push(`receivedDateTime le ${isoDay(date)}T23:59:59Z`);
      return { ...none, expression: `received<=${isoDay(date)}` };
    }
    case "newer_than":
    case "last": {
      const days = relativeDays(value);
      if (days === null) return none;
      const date = shiftDays(today, -days);
      filters.push(`receivedDateTime ge ${isoDay(date)}T00:00:00Z`);
      return { ...none, expression: `received>=${isoDay(date)}` };
    }
    case "older_than": {
      const days = relativeDays(value);
      if (days === null) return none;
      const date = shiftDays(today, -days);
      filters.push(`receivedDateTime le ${isoDay(date)}T23:59:59Z`);
      return { ...none, expression: `received<=${isoDay(date)}` };
    }
    case "folder":
    case "in":
      notes.push("Searching one folder isn't supported yet — searching the whole mailbox.");
      return none;
    case "sort":
      notes.push(
        "Outlook returns the best matches first; sorting isn't available while " +
        "searching for words."
      );
      return none;
    default:
      // Not a field we know: treat "ticket:1234" as ordinary text.
      return { expression: quote(`${rawField}:${value}`), isText: true, readState: false };
  }
}

function kqlTerm(word) {
  const core = word.replace(/\*+$/, "");
  if (!core) return "";
  // KQL prefix matching is a trailing *, which only works unquoted.
  if (word.endsWith("*") && /^[\p{L}\p{N}]+$/u.test(core)) return `${core}*`;
  return quoteIfNeeded(core);
}

/** Quote anything that is not a plain word, so KQL never reads it as syntax. */
function quoteIfNeeded(value) {
  return /^[\p{L}\p{N}._@-]+$/u.test(value) ? value : quote(value);
}

// KQL has no escape for a quote inside a phrase, so drop them rather than send
// a query Microsoft would reject.
function quote(value) {
  return `"${value.replace(/"/g, " ").trim()}"`;
}

function relativeDays(value) {
  const match = /^(\d+)\s*([dwmy])?$/.exec(String(value).trim().toLowerCase());
  if (!match) return null;
  return Number(match[1]) * RELATIVE_UNITS[match[2] || "d"];
}

function readDate(value, today) {
  const text = String(value).trim().toLowerCase();
  if (!text) return null;
  if (text === "today") return startOfDay(today);
  if (text === "yesterday") return shiftDays(today, -1);
  if (/^\d+\s*[dwmy]$/.test(text)) {
    const days = relativeDays(text);
    return days === null ? null : shiftDays(today, -days);
  }

  const normalized = text.replace(/[/.]/g, "-");
  let parts = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(normalized);
  if (parts) return makeDate(+parts[1], +parts[2], +parts[3]);
  parts = /^(\d{4})-(\d{1,2})$/.exec(normalized);
  if (parts) return makeDate(+parts[1], +parts[2], 1);
  parts = /^(\d{4})$/.exec(normalized);
  if (parts) return makeDate(+parts[1], 1, 1);
  return null;
}

function makeDate(year, month, day) {
  const date = new Date(year, month - 1, day);
  return Number.isNaN(date.getTime()) ? null : date;
}

function startOfDay(date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function shiftDays(date, days) {
  const shifted = startOfDay(date);
  shifted.setDate(shifted.getDate() + days);
  return shifted;
}

/** Local calendar date as YYYY-MM-DD (not UTC, which would shift the day). */
function isoDay(date) {
  const pad = (value) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
