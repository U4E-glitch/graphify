package com.mailsearch.app

import com.mailsearch.app.search.QueryMode
import com.mailsearch.app.search.QueryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class QueryParserTest {
    private val today = LocalDate.of(2026, 5, 10)

    private fun parse(text: String) = QueryParser.parse(text, today)

    @Test
    fun `plain words are combined with AND`() {
        val query = parse("invoice acme")
        assertEquals(QueryMode.SEARCH, query.mode)
        assertEquals("invoice AND acme", query.kql)
        assertEquals(listOf("invoice", "acme"), query.terms)
    }

    @Test
    fun `a quoted phrase stays a phrase`() {
        assertEquals("\"purchase order\"", parse("\"purchase order\"").kql)
    }

    @Test
    fun `punctuation is quoted so KQL never reads it as syntax`() {
        assertEquals("\"re: your order\"", parse("\"re: your order\"").kql)
        assertEquals("\"a+b\"", parse("a+b").kql)
    }

    @Test
    fun `an unbalanced quote cannot break out into KQL syntax`() {
        // Whatever the user typed, every quote in the result has to be a
        // delimiter this parser put there — never a stray one from the input.
        for (text in listOf("\"say \" hello\"", "a\"b", "\"", "\"\"\"")) {
            val kql = parse(text).kql
            assertEquals("unbalanced quotes in: $kql", 0, kql.count { it == '"' } % 2)
        }
    }

    @Test
    fun `exclusion becomes NOT`() {
        assertEquals("invoice NOT newsletter", parse("invoice -newsletter").kql)
    }

    @Test
    fun `OR groups the neighbouring words`() {
        assertEquals("(invoice OR receipt)", parse("invoice OR receipt").kql)
    }

    @Test
    fun `sender and subject map to KQL properties`() {
        assertEquals("from:alice AND subject:renewal", parse("from:alice subject:renewal").kql)
        assertEquals("to:bob", parse("to:bob").kql)
    }

    @Test
    fun `a multi-word field value is quoted`() {
        assertEquals("from:\"alice adams\"", parse("from:\"alice adams\"").kql)
    }

    @Test
    fun `a trailing star is a prefix search`() {
        assertEquals("tax* AND return", parse("tax* return").kql)
    }

    @Test
    fun `attachments are expressed in both dialects`() {
        val withWords = parse("invoice has:attachment")
        assertEquals(QueryMode.SEARCH, withWords.mode)
        assertTrue(withWords.kql.contains("hasAttachment:true"))

        // Nothing to search for, so the filter path is used and can sort by date.
        val filterOnly = parse("has:attachment")
        assertEquals(QueryMode.FILTER, filterOnly.mode)
        assertEquals("hasAttachments eq true", filterOnly.filter)
    }

    @Test
    fun `unread on its own uses the filter path`() {
        val query = parse("is:unread")
        assertEquals(QueryMode.FILTER, query.mode)
        assertEquals("isRead eq false", query.filter)
    }

    @Test
    fun `unread alongside words explains that it cannot be applied`() {
        val query = parse("invoice is:unread")
        assertEquals(QueryMode.SEARCH, query.mode)
        assertEquals("invoice", query.kql)
        assertTrue(query.notes.any { it.contains("unread") })
    }

    @Test
    fun `dates translate to both KQL and OData`() {
        val search = parse("invoice after:2026-01-01")
        assertTrue(search.kql.contains("received>=2026-01-01"))

        val filter = parse("after:2026-01-01")
        assertEquals(QueryMode.FILTER, filter.mode)
        assertEquals("receivedDateTime ge 2026-01-01T00:00:00Z", filter.filter)
    }

    @Test
    fun `partial dates and words are understood`() {
        assertEquals("receivedDateTime ge 2026-03-01T00:00:00Z", parse("after:2026-03").filter)
        assertEquals("receivedDateTime ge 2026-01-01T00:00:00Z", parse("after:2026").filter)
        assertEquals("receivedDateTime ge 2026-05-10T00:00:00Z", parse("after:today").filter)
        assertEquals("receivedDateTime ge 2026-05-09T00:00:00Z", parse("after:yesterday").filter)
        assertEquals("receivedDateTime ge 2026-05-03T00:00:00Z", parse("newer_than:7d").filter)
    }

    @Test
    fun `an unreadable date is reported rather than dropped in silence`() {
        val query = parse("after:sometime")
        assertTrue(query.notes.any { it.contains("Could not read the date") })
    }

    @Test
    fun `unsupported narrowing is explained`() {
        assertTrue(parse("invoice folder:archive").notes.any { it.contains("folder") })
        assertTrue(parse("invoice sort:newest").notes.any { it.contains("best matches") })
    }

    @Test
    fun `an unknown field is searched as ordinary text`() {
        assertEquals("\"ticket:1234\"", parse("ticket:1234").kql)
    }

    @Test
    fun `an empty query asks for nothing`() {
        assertEquals(QueryMode.EMPTY, parse("   ").mode)
    }

    @Test
    fun `a realistic combined query`() {
        val query = parse("from:contoso \"wire transfer\" -draft after:2026-01-01")
        assertEquals(QueryMode.SEARCH, query.mode)
        assertEquals(
            "from:contoso AND \"wire transfer\" AND received>=2026-01-01 NOT draft",
            query.kql,
        )
    }
}
