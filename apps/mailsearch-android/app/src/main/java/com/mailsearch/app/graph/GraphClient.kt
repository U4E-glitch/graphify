package com.mailsearch.app.graph

import com.mailsearch.app.Net
import com.mailsearch.app.search.GraphQuery
import com.mailsearch.app.search.QueryMode
import org.json.JSONArray
import org.json.JSONObject

/** One message, as much of it as a result list needs. */
data class Message(
    val id: String,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val toRecipients: String,
    val receivedAt: String,
    val preview: String,
    val hasAttachments: Boolean,
    val isRead: Boolean,
    val webLink: String,
    val body: String = "",
) {
    val sender: String get() = fromName.ifEmpty { fromAddress }.ifEmpty { "(unknown sender)" }
    val displaySubject: String get() = subject.ifEmpty { "(no subject)" }
}

data class SearchResults(
    val messages: List<Message>,
    val nextLink: String = "",
) {
    val hasMore: Boolean get() = nextLink.isNotEmpty()
}

/** Microsoft Graph answered with something the app cannot use. */
class GraphException(message: String, val status: Int = 0, val needsSignIn: Boolean = false) :
    Exception(message)

/**
 * Reads mail from Microsoft Graph. Nothing is stored: each search is answered
 * by Microsoft's own index over the mailbox.
 */
class GraphClient(private val accessToken: () -> String) {

    fun search(query: GraphQuery, pageSize: Int = 25): SearchResults =
        fetchList(urlFor(query, pageSize))

    fun page(nextLink: String): SearchResults = fetchList(nextLink)

    /** The full text of one message, fetched only when it is opened. */
    fun body(messageId: String): String {
        val url = "$ENDPOINT/me/messages/${Net.encode(messageId)}?\$select=body,bodyPreview"
        val payload = request(url, bodyAsText = true)
        val body = payload.optJSONObject("body")
        val content = body?.optString("content").orEmpty()
        val isHtml = body?.optString("contentType").orEmpty().equals("html", ignoreCase = true)
        return when {
            content.isEmpty() -> payload.optString("bodyPreview")
            isHtml || looksLikeHtml(content) -> Html.toText(content)
            else -> content
        }
    }

    fun accountAddress(): String {
        val payload = request("$ENDPOINT/me?\$select=mail,userPrincipalName,displayName")
        return payload.optString("mail").ifEmpty { payload.optString("userPrincipalName") }
    }

    internal fun urlFor(query: GraphQuery, pageSize: Int): String = buildString {
        append(ENDPOINT).append("/me/messages?\$select=").append(Net.encode(SELECT))
        append("&\$top=").append(pageSize)
        when (query.mode) {
            QueryMode.SEARCH -> {
                // $search takes a KQL string wrapped in quotes; quotes inside it
                // (a phrase) have to be escaped for the OData parser.
                val value = "\"" + query.kql.replace("\"", "\\\"") + "\""
                append("&\$search=").append(Net.encode(value))
                // Graph rejects $orderby together with $search — results come
                // back by relevance, which is what a search should do anyway.
            }
            QueryMode.FILTER -> {
                append("&\$filter=").append(Net.encode(query.filter))
                append("&\$orderby=").append(Net.encode("receivedDateTime desc"))
            }
            QueryMode.EMPTY -> {
                append("&\$orderby=").append(Net.encode("receivedDateTime desc"))
            }
        }
    }

    private fun fetchList(url: String): SearchResults {
        val payload = request(url, bodyAsText = true)
        val values = payload.optJSONArray("value") ?: JSONArray()
        val messages = (0 until values.length()).mapNotNull { index ->
            values.optJSONObject(index)?.let(::parseMessage)
        }
        return SearchResults(messages, payload.optString("@odata.nextLink"))
    }

    private fun request(url: String, bodyAsText: Boolean = false): JSONObject {
        val headers = buildMap {
            put("Authorization", "Bearer ${accessToken()}")
            // Ask Outlook to convert HTML bodies server-side, so previews are
            // prose rather than markup.
            if (bodyAsText) put("Prefer", "outlook.body-content-type=\"text\"")
        }
        val response = Net.get(url, headers)
        if (!response.ok) throw translate(response.status, response.body)
        return runCatching { JSONObject(response.body) }.getOrElse { JSONObject() }
    }

    private fun translate(status: Int, body: String): GraphException {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val code = error?.optString("code").orEmpty()
        val message = error?.optString("message").orEmpty()
        return when (status) {
            401, 403 -> GraphException(
                "Outlook refused the request. Sign in again, and check the app " +
                    "registration has the Mail.Read permission.",
                status, needsSignIn = true,
            )
            429 -> GraphException("Outlook is rate-limiting requests. Try again in a moment.", status)
            in 500..599 -> GraphException("Outlook is having trouble right now. Try again.", status)
            else -> GraphException(
                message.ifEmpty { "Search failed (HTTP $status)." }
                    .let { if (code.isNotEmpty()) "$it" else it },
                status,
            )
        }
    }

    companion object {
        const val ENDPOINT = "https://graph.microsoft.com/v1.0"
        const val SELECT =
            "id,subject,from,toRecipients,receivedDateTime,bodyPreview,hasAttachments,isRead,webLink"

        fun parseMessage(json: JSONObject): Message {
            val from = json.optJSONObject("from")?.optJSONObject("emailAddress")
            return Message(
                id = json.optString("id"),
                subject = json.optString("subject"),
                fromName = from?.optString("name").orEmpty(),
                fromAddress = from?.optString("address").orEmpty(),
                toRecipients = addressList(json.optJSONArray("toRecipients")),
                receivedAt = json.optString("receivedDateTime"),
                preview = json.optString("bodyPreview").replace(Regex("\\s+"), " ").trim(),
                hasAttachments = json.optBoolean("hasAttachments", false),
                isRead = json.optBoolean("isRead", true),
                webLink = json.optString("webLink"),
            )
        }

        private fun addressList(entries: JSONArray?): String {
            if (entries == null) return ""
            return (0 until entries.length()).mapNotNull { index ->
                val address = entries.optJSONObject(index)?.optJSONObject("emailAddress")
                val name = address?.optString("name").orEmpty()
                val mail = address?.optString("address").orEmpty()
                when {
                    name.isNotEmpty() && mail.isNotEmpty() && !name.equals(mail, true) -> "$name <$mail>"
                    else -> name.ifEmpty { mail }.ifEmpty { null }
                }
            }.joinToString(", ")
        }

        internal fun looksLikeHtml(text: String): Boolean =
            text.contains('<') &&
                Regex("""<\s*(?:html|body|div|p|br|table|span|a\s|img\s|font|style)[^>]*>""",
                    RegexOption.IGNORE_CASE).findAll(text).take(2).count() >= 2
    }
}

/** Turns an HTML mail body into something readable in a text view. */
object Html {
    private val SCRIPT_OR_STYLE =
        Regex("""<\s*(script|style|head)[^>]*>.*?<\s*/\s*\1\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BLOCK = Regex(
        """<\s*/?\s*(p|div|br|tr|li|table|blockquote|h[1-6]|ul|ol|pre|section|article)[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val TAG = Regex("""<[^>]+>""")

    fun toText(html: String): String = html
        .replace(SCRIPT_OR_STYLE, " ")
        .replace(BLOCK, "\n")
        .replace(TAG, "")
        .let(::decodeEntities)
        .replace(Regex("""[ \t ]+"""), " ")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .lines().joinToString("\n") { it.trim() }
        .trim()

    private fun decodeEntities(text: String): String {
        var result = text
        for ((entity, replacement) in ENTITIES) result = result.replace(entity, replacement)
        return Regex("&#(\\d+);").replace(result) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
    }

    private val ENTITIES = listOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&mdash;" to "—",
        "&ndash;" to "–", "&hellip;" to "…", "&euro;" to "€", "&pound;" to "£",
    )
}
