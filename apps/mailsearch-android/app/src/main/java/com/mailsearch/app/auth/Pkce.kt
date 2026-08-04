package com.mailsearch.app.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636), the part of the sign-in that protects a public app.
 *
 * The app invents a secret (the verifier), sends only its SHA-256 hash (the
 * challenge) when opening the browser, and reveals the secret when redeeming
 * the code. Another app that intercepted the redirect would hold a code it
 * cannot exchange, because it never saw the verifier.
 */
data class Pkce(val verifier: String, val challenge: String) {
    companion object {
        private const val VERIFIER_BYTES = 64  // 86 base64url characters

        fun generate(random: SecureRandom = SecureRandom()): Pkce {
            val bytes = ByteArray(VERIFIER_BYTES).also(random::nextBytes)
            val verifier = base64Url(bytes)
            return Pkce(verifier, challengeFor(verifier))
        }

        /** S256: base64url(sha256(verifier)), unpadded. */
        fun challengeFor(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return base64Url(digest)
        }

        fun randomState(random: SecureRandom = SecureRandom()): String =
            base64Url(ByteArray(24).also(random::nextBytes))

        // java.util.Base64 rather than android.util.Base64: it exists from API
        // 26 (the app's minimum) and works unchanged in the unit tests.
        private fun base64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
