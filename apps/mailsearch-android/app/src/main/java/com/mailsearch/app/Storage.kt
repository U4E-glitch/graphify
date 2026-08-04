package com.mailsearch.app

import android.content.Context
import android.content.SharedPreferences
import com.mailsearch.app.auth.Tokens

/**
 * Everything the app remembers between launches: the Azure client id and the
 * Outlook tokens.
 *
 * These live in the app's private storage, which on an unrooted phone no other
 * app can read. They are not additionally encrypted — see the README.
 */
class Storage(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mailsearch", Context.MODE_PRIVATE)

    var clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value.trim()).apply()

    var tenant: String
        get() = prefs.getString(KEY_TENANT, "common").orEmpty().ifEmpty { "common" }
        set(value) = prefs.edit().putString(KEY_TENANT, value.trim().ifEmpty { "common" }).apply()

    val isConfigured: Boolean get() = clientId.isNotEmpty()

    fun loadTokens(): Tokens? {
        val access = prefs.getString(KEY_ACCESS, "").orEmpty()
        val refresh = prefs.getString(KEY_REFRESH, "").orEmpty()
        if (access.isEmpty() && refresh.isEmpty()) return null
        return Tokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = prefs.getLong(KEY_EXPIRES, 0L),
            account = prefs.getString(KEY_ACCOUNT, "").orEmpty(),
        )
    }

    fun saveTokens(tokens: Tokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_EXPIRES, tokens.expiresAt)
            .putString(KEY_ACCOUNT, tokens.account)
            .apply()
    }

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES).remove(KEY_ACCOUNT)
            .apply()
    }

    /** Recent searches, newest first — a convenience, not a mail cache. */
    var recentSearches: List<String>
        get() = prefs.getString(KEY_RECENT, "").orEmpty()
            .split("\n").filter { it.isNotBlank() }
        set(value) = prefs.edit()
            .putString(KEY_RECENT, value.distinct().take(MAX_RECENT).joinToString("\n"))
            .apply()

    fun rememberSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        recentSearches = listOf(trimmed) + recentSearches.filterNot { it.equals(trimmed, true) }
    }

    private companion object {
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_TENANT = "tenant"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_ACCOUNT = "account"
        const val KEY_RECENT = "recent_searches"
        const val MAX_RECENT = 8
    }
}
