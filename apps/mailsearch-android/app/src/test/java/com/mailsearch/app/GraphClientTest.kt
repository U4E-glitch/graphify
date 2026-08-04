package com.mailsearch.app

import com.mailsearch.app.graph.GraphClient
import com.mailsearch.app.graph.Html
import com.mailsearch.app.search.QueryParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.time.LocalDate

class GraphClientTest {
    private val client = GraphClient { "test-token" }
    private val today = LocalDate.of(2026, 5, 10)

    private fun urlFor(text: String): String =
        client.urlFor(QueryParser.parse(text, today), 25)

    private fun decoded(url: String) = URLDecoder.decode(url, "UTF-8")

    @Test
    fun `a word search uses the search endpoint`() {
        val url = decoded(urlFor("invoice"))
        assertTrue(url.contains("/me/messages"))
        assertTrue(url.contains("\$search=\"invoice\""))
        // Graph rejects $orderby together with $search.
        assertFalse(url.contains("\$orderby"))
    }

    @Test
    fun `a phrase keeps its quotes escaped for the OData parser`() {
        // The value Microsoft must receive is:  $search="\"purchase order\""
        val expected = "\$search=\"\\\"purchase order\\\"\""
        assertTrue(decoded(urlFor("\"purchase order\"")).contains(expected))
    }

    @Test
    fun `a filter-only query sorts newest first`() {
        val url = decoded(urlFor("is:unread"))
        assertTrue(url.contains("\$filter=isRead eq false"))
        assertTrue(url.contains("\$orderby=receivedDateTime desc"))
        assertFalse(url.contains("\$search"))
    }

    @Test
    fun `the requested fields are asked for explicitly`() {
        val url = decoded(urlFor("invoice"))
        assertTrue(url.contains("bodyPreview"))
        assertTrue(url.contains("receivedDateTime"))
        assertTrue(url.contains("\$top=25"))
    }

    @Test
    fun `spaces are percent-encoded, never left raw`() {
        val raw = urlFor("from:alice invoice")
        assertFalse(raw.contains(" "))
        assertTrue(raw.contains("%20"))
    }

    // -- parsing ----------------------------------------------------------
    private val sample = JSONObject(
        """
        {
          "id": "AAMk123",
          "subject": "Invoice 2026-041",
          "from": {"emailAddress": {"name": "Contoso Billing", "address": "billing@contoso.com"}},
          "toRecipients": [
            {"emailAddress": {"name": "Me", "address": "me@example.com"}},
            {"emailAddress": {"name": "", "address": "cc@example.com"}}
          ],
          "receivedDateTime": "2026-05-01T09:00:00Z",
          "bodyPreview": "Your   invoice\nis attached",
          "hasAttachments": true,
          "isRead": false,
          "webLink": "https://outlook.office.com/mail/AAMk123"
        }
        """.trimIndent()
    )

    @Test
    fun `a graph message becomes a usable result row`() {
        val message = GraphClient.parseMessage(sample)
        assertEquals("AAMk123", message.id)
        assertEquals("Invoice 2026-041", message.subject)
        assertEquals("Contoso Billing", message.fromName)
        assertEquals("billing@contoso.com", message.fromAddress)
        assertEquals("Me <me@example.com>, cc@example.com", message.toRecipients)
        assertEquals("Your invoice is attached", message.preview)
        assertTrue(message.hasAttachments)
        assertFalse(message.isRead)
    }

    @Test
    fun `missing fields do not throw`() {
        val message = GraphClient.parseMessage(JSONObject("""{"id": "x"}"""))
        assertEquals("x", message.id)
        assertEquals("", message.fromAddress)
        assertEquals("(no subject)", message.displaySubject)
        assertEquals("(unknown sender)", message.sender)
    }

    // -- html -------------------------------------------------------------
    @Test
    fun `html bodies are reduced to readable text`() {
        val html = """
            <html><head><style>p{color:red}</style></head><body>
            <p>Hello <b>Bob</b>,</p><p>The invoice is attached.</p>
            <script>alert(1)</script></body></html>
        """.trimIndent()
        val text = Html.toText(html)
        assertTrue(text.contains("Hello Bob,"))
        assertTrue(text.contains("The invoice is attached."))
        assertFalse(text.contains("color:red"))
        assertFalse(text.contains("alert(1)"))
        assertFalse(text.contains("<"))
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("Tom & Jerry — 5 > 3", Html.toText("Tom &amp; Jerry &mdash; 5 &gt; 3"))
    }

    @Test
    fun `markup wearing a text content type is still detected`() {
        assertTrue(GraphClient.looksLikeHtml("<div><p>Hi there</p></div>"))
        assertFalse(GraphClient.looksLikeHtml("Costs < 500 EUR and 5 > 3"))
        assertFalse(GraphClient.looksLikeHtml("plain text"))
    }
}
