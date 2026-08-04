package com.mailsearch.app.auth

import org.json.JSONObject

/** What Microsoft handed back, and when it stops being usable. */
data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val account: String = "",
) {
    /**
     * Treated as stale five minutes early, so a request that starts just under
     * the wire does not expire halfway through.
     */
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotEmpty() && now < expiresAt - SKEW_MS

    companion object {
        const val SKEW_MS = 5 * 60 * 1000L

        fun fromJson(
            payload: JSONObject,
            previous: Tokens? = null,
            now: Long = System.currentTimeMillis(),
        ): Tokens {
            val lifetimeSeconds = payload.optLong("expires_in", 3600L)
            return Tokens(
                accessToken = payload.optString("access_token", ""),
                // A refresh response may leave the refresh token out, which
                // means "carry on using the one you have".
                refreshToken = payload.optString("refresh_token").ifEmpty {
                    previous?.refreshToken.orEmpty()
                },
                expiresAt = now + lifetimeSeconds * 1000L,
                account = previous?.account.orEmpty(),
            )
        }
    }
}

/** Where sign-in currently stands, as far as the UI is concerned. */
sealed interface SignInState {
    data object NeedsSetup : SignInState          // no client id yet
    data object SignedOut : SignInState
    data object InProgress : SignInState
    data class SignedIn(val account: String) : SignInState
    data class Failed(val message: String) : SignInState
}

/** Sign-in could not be completed or resumed; the user has to sign in again. */
class AuthException(message: String, val needsSignIn: Boolean = false) : Exception(message)
