package com.mailsearch.app.auth

import com.mailsearch.app.Net
import org.json.JSONObject

/**
 * OAuth 2.0 authorization code flow with PKCE against Microsoft identity
 * platform.
 *
 * The password is typed on Microsoft's own page in the phone's browser; this
 * app only ever handles the resulting code. There is no client secret, which
 * is exactly why PKCE is required.
 */
class AuthClient(
    private val clientId: String,
    private val tenant: String = DEFAULT_TENANT,
    private val redirectUri: String = DEFAULT_REDIRECT,
    private val authority: String = DEFAULT_AUTHORITY,
) {
    private val authorizeUrl get() = "$authority/$tenant/oauth2/v2.0/authorize"
    private val tokenUrl get() = "$authority/$tenant/oauth2/v2.0/token"

    /** The page to open in the browser to start signing in. */
    fun authorizationUrl(pkce: Pkce, state: String): String = buildString {
        append(authorizeUrl)
        append("?client_id=").append(Net.encode(clientId))
        append("&response_type=code")
        append("&redirect_uri=").append(Net.encode(redirectUri))
        append("&response_mode=query")
        append("&scope=").append(Net.encode(SCOPES))
        append("&code_challenge=").append(Net.encode(pkce.challenge))
        append("&code_challenge_method=S256")
        append("&state=").append(Net.encode(state))
        // Show the account picker rather than silently reusing whichever
        // account the browser is already signed in to.
        append("&prompt=select_account")
    }

    /** Trade the code from the redirect for tokens. */
    fun redeemCode(code: String, verifier: String): Tokens = exchange(
        mapOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to verifier,
        ),
        previous = null,
    )

    fun refresh(tokens: Tokens): Tokens = exchange(
        mapOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to tokens.refreshToken,
            "scope" to SCOPES,
        ),
        previous = tokens,
    )

    private fun exchange(fields: Map<String, String>, previous: Tokens?): Tokens {
        val response = Net.postForm(tokenUrl, fields)
        val payload = runCatching { JSONObject(response.body) }.getOrElse { JSONObject() }
        if (!response.ok) {
            val code = payload.optString("error")
            val message = payload.optString("error_description")
                .lineSequence().firstOrNull()?.trim().orEmpty()
                .ifEmpty { "Sign-in failed (HTTP ${response.status})." }
            // These four mean the stored grant is dead; anything else may be a
            // passing server-side problem worth retrying.
            val fatal = code in setOf(
                "invalid_grant", "invalid_client", "unauthorized_client", "interaction_required",
            )
            throw AuthException(friendly(code, message), needsSignIn = fatal)
        }
        val tokens = Tokens.fromJson(payload, previous)
        if (tokens.accessToken.isEmpty()) {
            throw AuthException("Microsoft returned a sign-in response with no access token.")
        }
        return tokens
    }

    private fun friendly(code: String, message: String): String = when {
        code == "invalid_client" || message.contains("AADSTS7000218") ->
            "Microsoft rejected the app registration. In the Azure portal, open " +
                "Authentication and set \"Allow public client flows\" to Yes."
        message.contains("AADSTS50011") ->
            "The redirect address is not registered. Add $redirectUri to the app " +
                "registration under Authentication → Mobile and desktop applications."
        code == "invalid_grant" -> "The Outlook sign-in has expired. Please sign in again."
        else -> message
    }

    companion object {
        /**
         * Microsoft's answer when the registration accepts personal accounts
         * only but the app asked at the /common/ endpoint.
         *
         * "common" is for registrations that take both personal and work
         * accounts; a personal-only registration has to be asked at
         * /consumers/. Recognising this means the app can correct itself
         * instead of showing the raw complaint.
         */
        fun tenantFixFor(description: String, currentTenant: String): String {
            if (currentTenant != DEFAULT_TENANT) return ""
            val text = description.lowercase()
            val audienceProblem = "useraudience" in text ||
                ("consumer" in text && "common" in text)
            return if (audienceProblem) TENANT_CONSUMERS else ""
        }

        const val DEFAULT_AUTHORITY = "https://login.microsoftonline.com"
        const val DEFAULT_TENANT = "common"
        /** The endpoint for registrations that accept personal accounts only. */
        const val TENANT_CONSUMERS = "consumers"
        const val DEFAULT_REDIRECT = "mailsearch://auth"

        /** Read-only mail access, plus the refresh token that avoids re-asking. */
        const val SCOPES = "offline_access User.Read Mail.Read"
    }
}
