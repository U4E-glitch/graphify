package com.mailsearch.app.search

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Translates what a person types into a query Outlook's servers can run.
 *
 * Nothing is downloaded to search: Microsoft indexes the mailbox already, so
 * the app sends the query and receives only the matches. That index is reached
 * two different ways, and picking the right one is this file's job:
 *
 *  - **Search** (KQL in `$search`) whenever there are words to look for. It
 *    covers the whole mailbox and ranks by relevance, but cannot sort by date
 *    or filter on read state.
 *  - **Filter** (OData `$filter`) when the query is only constraints — unread,
 *    has an attachment, a date range. That path *can* sort, so results come
 *    back newest first.
 */
enum class QueryMode { SEARCH, FILTER, EMPTY }

data class GraphQuery(
    val mode: QueryMode,
    val kql: String = "",
    val filter: String = "",
    /** Words to highlight in the results. */
    val terms: List<String> = emptyList(),
    /** Things the query asked for that Outlook's search cannot express. */
    val notes: List<String> = emptyList(),
)

object QueryParser {
    // from:alice | subject:"end of year" | "quoted phrase" | bare-word
    private val TOKEN = Regex(
        """(-)?(?:([A-Za-z_]+):(?:"([^"]*)"|([^\s"]*))|"([^"]*)"|(\S+))"""
    )

    private val TEXT_FIELDS = mapOf(
        "from" to "from", "sender" to "from",
        "to" to "to", "recipient" to "to", "recipients" to "to",
        "cc" to "cc",
        "subject" to "subject", "title" to "subject",
        "body" to "body", "content" to "body",
        "participants" to "participants",
        "attachment" to "attachment", "filename" to "attachment",
    )

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun parse(text: String, today: LocalDate = LocalDate.now()): GraphQuery {
        if (text.isBlank()) return GraphQuery(QueryMode.EMPTY)

        val positives = mutableListOf<String>()
        val negatives = mutableListOf<String>()
        val terms = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val filters = mutableListOf<String>()
        var freeText = false
        var pendingOr = false
        // Read state exists as an OData filter but has no KQL equivalent, so
        // whether it survives depends on which path the query ends up taking.
        val readState = BooleanArray(1)

        for (match in TOKEN.findAll(text)) {
            val negated = match.groupValues[1] == "-"
            val field = match.groupValues[2]
            val quotedValue = match.groups[3] != null
            val fieldValue = if (quotedValue) match.groupValues[3] else match.groupValues[4]
            val phrase = match.groups[5]?.value
            val word = match.groupValues[6]

            if (word.equals("OR", ignoreCase = true) && !negated) {
                pendingOr = true
                continue
            }

            var expression: String? = null
            when {
                field.isNotEmpty() -> {
                    val handled =
                        fieldExpression(field, fieldValue, negated, filters, notes, readState, today)
                    expression = handled
                    if (handled != null && TEXT_FIELDS.containsKey(field.lowercase())) {
                        terms += fieldValue.split(" ").filter { it.isNotBlank() }
                        freeText = true
                    }
                }
                phrase != null -> {
                    if (phrase.isNotBlank()) {
                        expression = quote(phrase)
                        terms += phrase.split(" ").filter { it.isNotBlank() }
                        freeText = true
                    }
                }
                word.isNotBlank() -> {
                    expression = kqlTerm(word)
                    terms += word.trimEnd('*')
                    freeText = true
                }
            }

            if (expression == null) {
                pendingOr = false
                continue
            }
            when {
                negated -> { negatives += expression; pendingOr = false }
                pendingOr && positives.isNotEmpty() -> {
                    positives[positives.lastIndex] = "(${positives.last()} OR $expression)"
                    pendingOr = false
                }
                else -> positives += expression
            }
        }

        val kql = buildString {
            append(positives.joinToString(" AND "))
            for (negative in negatives) {
                if (isNotEmpty()) append(" ")
                append("NOT ").append(negative)
            }
        }.trim()

        // Nothing to look *for* — only constraints like unread, attachments or a
        // date range. Those are expressible as an OData filter, which unlike a
        // search can also be sorted, so results come back newest first.
        if (!freeText && filters.isNotEmpty()) {
            return GraphQuery(
                QueryMode.FILTER,
                filter = filters.joinToString(" and "),
                terms = terms,
                notes = notes,
            )
        }
        if (kql.isEmpty()) {
            return GraphQuery(QueryMode.EMPTY, terms = terms, notes = notes)
        }
        // Graph will not accept a filter and a search on the same request, so
        // anything that only exists as a filter is dropped here — say so rather
        // than quietly returning wider results than were asked for.
        if (readState[0]) {
            notes += "Outlook's search can't narrow to read or unread mail when you " +
                "also search for words — showing all matches."
        }
        return GraphQuery(QueryMode.SEARCH, kql = kql, terms = terms, notes = notes)
    }

    private fun fieldExpression(
        rawField: String,
        value: String,
        negated: Boolean,
        filters: MutableList<String>,
        notes: MutableList<String>,
        readState: BooleanArray,
        today: LocalDate,
    ): String? {
        val field = rawField.lowercase()
        val trimmed = value.trim()

        TEXT_FIELDS[field]?.let { property ->
            if (trimmed.isEmpty()) return null
            return "$property:${quoteIfNeeded(trimmed)}"
        }

        when (field) {
            "has" -> {
                if (trimmed.lowercase() in setOf("attachment", "attachments", "file", "files")) {
                    filters += "hasAttachments eq ${if (negated) "false" else "true"}"
                    return "hasAttachment:${if (negated) "false" else "true"}"
                }
                return null
            }
            "is" -> {
                when (trimmed.lowercase()) {
                    "unread" -> {
                        filters += "isRead eq ${if (negated) "true" else "false"}"
                        readState[0] = true
                        return null
                    }
                    "read" -> {
                        filters += "isRead eq ${if (negated) "false" else "true"}"
                        readState[0] = true
                        return null
                    }
                    "important", "high" -> {
                        filters += "importance eq 'high'"
                        return "importance:high"
                    }
                }
                return null
            }
            "after", "since", "newer" -> {
                val date = readDate(trimmed, today)
                if (date == null) { notes += "Could not read the date in \"$rawField:$value\"."; return null }
                filters += "receivedDateTime ge ${date}T00:00:00Z"
                return "received>=${date.format(DATE_FORMAT)}"
            }
            "before", "until", "older" -> {
                val date = readDate(trimmed, today)
                if (date == null) { notes += "Could not read the date in \"$rawField:$value\"."; return null }
                filters += "receivedDateTime le ${date}T23:59:59Z"
                return "received<=${date.format(DATE_FORMAT)}"
            }
            "newer_than", "last" -> {
                val days = relativeDays(trimmed)
                if (days == null) return null
                val date = today.minusDays(days.toLong())
                filters += "receivedDateTime ge ${date}T00:00:00Z"
                return "received>=${date.format(DATE_FORMAT)}"
            }
            "older_than" -> {
                val days = relativeDays(trimmed) ?: return null
                val date = today.minusDays(days.toLong())
                filters += "receivedDateTime le ${date}T23:59:59Z"
                return "received<=${date.format(DATE_FORMAT)}"
            }
            "folder", "in" -> {
                notes += "Searching one folder isn't supported yet — searching the whole mailbox."
                return null
            }
            "sort" -> {
                notes += "Outlook returns the best matches first; sorting isn't available " +
                    "while searching for words."
                return null
            }
        }
        // Not a field we know: treat "ticket:1234" as ordinary text.
        return quote("$rawField:$value")
    }

    private fun kqlTerm(word: String): String {
        val core = word.trimEnd('*')
        if (core.isEmpty()) return ""
        // KQL prefix matching is a trailing *, which only works unquoted.
        return if (word.endsWith("*") && core.all { it.isLetterOrDigit() }) "$core*" else quoteIfNeeded(core)
    }

    /** Quote anything that isn't a plain word, so KQL never reads it as syntax. */
    private fun quoteIfNeeded(value: String): String =
        if (value.all { it.isLetterOrDigit() || it == '.' || it == '@' || it == '-' || it == '_' }) {
            value
        } else {
            quote(value)
        }

    // KQL has no escape for a quote inside a phrase, so drop them rather than
    // send a query Microsoft would reject.
    private fun quote(value: String): String = "\"" + value.replace("\"", " ").trim() + "\""

    private fun relativeDays(value: String): Int? {
        val match = Regex("""(\d+)\s*([dwmy])?""").matchEntire(value.trim().lowercase()) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        return amount * when (match.groupValues[2].ifEmpty { "d" }) {
            "w" -> 7
            "m" -> 30
            "y" -> 365
            else -> 1
        }
    }

    private fun readDate(value: String, today: LocalDate): LocalDate? {
        val text = value.trim().lowercase()
        return when {
            text == "today" -> today
            text == "yesterday" -> today.minusDays(1)
            Regex("""\d+\s*[dwmy]""").matches(text) ->
                relativeDays(text)?.let { today.minusDays(it.toLong()) }
            else -> {
                val normalized = text.replace('/', '-').replace('.', '-')
                runCatching { LocalDate.parse(normalized) }.getOrNull()
                    ?: runCatching { LocalDate.parse("$normalized-01") }.getOrNull()
                    ?: runCatching { LocalDate.parse("$normalized-01-01") }.getOrNull()
            }
        }
    }
}
