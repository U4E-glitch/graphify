package com.mailsearch.app

import com.mailsearch.app.auth.AuthClient
import com.mailsearch.app.auth.Pkce
import com.mailsearch.app.auth.Tokens
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class AuthTest {

    // -- PKCE -------------------------------------------------------------
    @Test
    fun `challenge matches the worked example in RFC 7636`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeFor(verifier))
    }

    @Test
    fun `generated verifiers are long, url-safe and never repeat`() {
        val first = Pkce.generate()
        val second = Pkce.generate()
        assertNotEquals(first.verifier, second.verifier)
        assertTrue(first.verifier.length in 43..128)
        assertTrue(first.verifier.all { it.isLetterOrDigit() || it in "-._~" })
        assertEquals(Pkce.challengeFor(first.verifier), first.challenge)
    }

    // -- authorization URL ------------------------------------------------
    @Test
    fun `the sign-in url carries everything Microsoft needs`() {
        val pkce = Pkce.generate()
        val url = AuthClient("client-abc").authorizationUrl(pkce, "state-1")
        val decoded = URLDecoder.decode(url, "UTF-8")

        assertTrue(decoded.startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?"))
        assertTrue(decoded.contains("client_id=client-abc"))
        assertTrue(decoded.contains("response_type=code"))
        assertTrue(decoded.contains("redirect_uri=mailsearch://auth"))
        assertTrue(decoded.contains("code_challenge=${pkce.challenge}"))
        assertTrue(decoded.contains("code_challenge_method=S256"))
        assertTrue(decoded.contains("state=state-1"))
        assertTrue(decoded.contains("Mail.Read"))
        assertTrue(decoded.contains("offline_access"))
        // The verifier is the secret half; it must never leave the device here.
        assertFalse(decoded.contains(pkce.verifier))
    }

    @Test
    fun `a single-tenant registration is honoured`() {
        val url = AuthClient("c", tenant = "contoso.onmicrosoft.com").authorizationUrl(Pkce.generate(), "s")
        assertTrue(url.contains("/contoso.onmicrosoft.com/oauth2/v2.0/authorize"))
    }

    // -- tokens -----------------------------------------------------------
    @Test
    fun `a token response is read into a session`() {
        val json = JSONObject("""{"access_token":"at","refresh_token":"rt","expires_in":3600}""")
        val tokens = Tokens.fromJson(json, now = 1_000_000L)
        assertEquals("at", tokens.accessToken)
        assertEquals("rt", tokens.refreshToken)
        assertEquals(1_000_000L + 3_600_000L, tokens.expiresAt)
    }

    @Test
    fun `a refresh without a new refresh token keeps the old one`() {
        val existing = Tokens("old", "rt-1", 0L, account = "me@example.com")
        val refreshed = Tokens.fromJson(JSONObject("""{"access_token":"at-2","expires_in":3600}"""), existing)
        assertEquals("rt-1", refreshed.refreshToken)
        assertEquals("me@example.com", refreshed.account)
    }

    @Test
    fun `a token is treated as stale before it actually expires`() {
        val now = 1_000_000L
        assertTrue(Tokens("at", "rt", now + 40 * 60_000).isFresh(now))
        // Inside the safety margin: refresh rather than start a doomed request.
        assertFalse(Tokens("at", "rt", now + 60_000).isFresh(now))
        assertFalse(Tokens("", "rt", now + 40 * 60_000).isFresh(now))
    }
}
