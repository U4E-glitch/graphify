import assert from "node:assert/strict";
import { test } from "node:test";

import { MODE, parse } from "../js/query.js";

// A fixed "today" so date arithmetic is not clock-dependent.
const TODAY = new Date(2026, 4, 10); // 10 May 2026, local time

const q = (text) => parse(text, TODAY);

test("plain words are combined with AND", () => {
  const query = q("invoice acme");
  assert.equal(query.mode, MODE.SEARCH);
  assert.equal(query.kql, "invoice AND acme");
  assert.deepEqual(query.terms, ["invoice", "acme"]);
});

test("a quoted phrase stays a phrase", () => {
  assert.equal(q('"purchase order"').kql, '"purchase order"');
});

test("punctuation is quoted so KQL never reads it as syntax", () => {
  assert.equal(q('"re: your order"').kql, '"re: your order"');
  assert.equal(q("a+b").kql, '"a+b"');
});

test("an unbalanced quote cannot break out into KQL syntax", () => {
  for (const text of ['"say " hello"', 'a"b', '"', '"""']) {
    const { kql } = q(text);
    assert.equal((kql.match(/"/g) || []).length % 2, 0, `unbalanced: ${kql}`);
  }
});

test("exclusion becomes NOT", () => {
  assert.equal(q("invoice -newsletter").kql, "invoice NOT newsletter");
});

test("OR groups the neighbouring words", () => {
  assert.equal(q("invoice OR receipt").kql, "(invoice OR receipt)");
});

test("sender and subject map to KQL properties", () => {
  assert.equal(q("from:alice subject:renewal").kql, "from:alice AND subject:renewal");
  assert.equal(q("to:bob").kql, "to:bob");
  assert.equal(q('from:"alice adams"').kql, 'from:"alice adams"');
});

test("a trailing star is a prefix search", () => {
  assert.equal(q("tax* return").kql, "tax* AND return");
});

test("attachments are expressed in both dialects", () => {
  const withWords = q("invoice has:attachment");
  assert.equal(withWords.mode, MODE.SEARCH);
  assert.ok(withWords.kql.includes("hasAttachment:true"));

  // Nothing to search for, so the sortable filter path is used instead.
  const filterOnly = q("has:attachment");
  assert.equal(filterOnly.mode, MODE.FILTER);
  assert.equal(filterOnly.filter, "hasAttachments eq true");
});

test("unread on its own uses the filter path", () => {
  const query = q("is:unread");
  assert.equal(query.mode, MODE.FILTER);
  assert.equal(query.filter, "isRead eq false");
  assert.deepEqual(query.notes, []);
});

test("unread alongside words explains that it cannot be applied", () => {
  const query = q("invoice is:unread");
  assert.equal(query.mode, MODE.SEARCH);
  assert.equal(query.kql, "invoice");
  assert.ok(query.notes.some((note) => note.includes("unread")));
});

test("dates translate to both KQL and OData", () => {
  assert.ok(q("invoice after:2026-01-01").kql.includes("received>=2026-01-01"));
  const filter = q("after:2026-01-01");
  assert.equal(filter.mode, MODE.FILTER);
  assert.equal(filter.filter, "receivedDateTime ge 2026-01-01T00:00:00Z");
});

test("partial dates and words are understood", () => {
  assert.equal(q("after:2026-03").filter, "receivedDateTime ge 2026-03-01T00:00:00Z");
  assert.equal(q("after:2026").filter, "receivedDateTime ge 2026-01-01T00:00:00Z");
  assert.equal(q("after:today").filter, "receivedDateTime ge 2026-05-10T00:00:00Z");
  assert.equal(q("after:yesterday").filter, "receivedDateTime ge 2026-05-09T00:00:00Z");
  assert.equal(q("newer_than:7d").filter, "receivedDateTime ge 2026-05-03T00:00:00Z");
  assert.equal(q("before:2026-05-01").filter, "receivedDateTime le 2026-05-01T23:59:59Z");
});

test("an unreadable date is reported rather than dropped in silence", () => {
  assert.ok(q("after:sometime").notes.some((note) => note.includes("Could not read the date")));
});

test("unsupported narrowing is explained", () => {
  assert.ok(q("invoice folder:archive").notes.some((note) => note.includes("folder")));
  assert.ok(q("invoice sort:newest").notes.some((note) => note.includes("best matches")));
});

test("an unknown field is searched as ordinary text", () => {
  assert.equal(q("ticket:1234").kql, '"ticket:1234"');
});

test("an empty query asks for nothing", () => {
  assert.equal(q("   ").mode, MODE.EMPTY);
  assert.equal(q("").mode, MODE.EMPTY);
});

test("a realistic combined query", () => {
  const query = q('from:contoso "wire transfer" -draft after:2026-01-01');
  assert.equal(query.mode, MODE.SEARCH);
  assert.equal(
    query.kql,
    'from:contoso AND "wire transfer" AND received>=2026-01-01 NOT draft'
  );
});

test("the same query gives the same answer as the Android app would", () => {
  // Both ports must agree, or the two apps would behave differently.
  assert.equal(q("invoice acme").kql, "invoice AND acme");
  assert.equal(q("from:alice").kql, "from:alice");
  assert.equal(q("tax*").kql, "tax*");
});
