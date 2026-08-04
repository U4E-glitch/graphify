package com.mailsearch.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An HTTP response with the body already read. */
data class HttpResponse(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

/** The request never produced a response: no signal, DNS failure, timeout. */
class NetworkUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The whole HTTP layer.
 *
 * Small enough to read in one sitting, which matters more here than the
 * features of a full client: the app makes three kinds of request and every
 * one of them is a plain GET or form POST.
 */
object Net {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 45_000

    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        send("GET", url, headers, null)

    fun postForm(url: String, fields: Map<String, String>): HttpResponse {
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return send(
            "POST",
            url,
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body.toByteArray(Charsets.UTF_8),
        )
    }

    private fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        payload: ByteArray?,
    ): HttpResponse {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw NetworkUnavailable("Could not reach ${hostOf(url)}.", error)
        }
        return try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (payload != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            // A 4xx/5xx body arrives on the error stream, and it is exactly where
            // Microsoft puts the reason the request failed.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResponse(status, text)
        } catch (error: IOException) {
            throw NetworkUnavailable("No connection to ${hostOf(url)}.", error)
        } finally {
            connection.disconnect()
        }
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrDefault("the server")
}
