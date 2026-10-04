package com.nuvio.tv.core.diagnostics

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

class DiagnosticRedactionTest {
    @Test
    fun removesCredentialsFromUrlUserinfoPathAndQuery() {
        val input = "GET https://user:password@addon.test/private-token/manifest.json?key=query-secret failed (401)"
        assertEquals("GET [redacted-url] failed (401)", redactDiagnosticText(input))
        assertFalse(redactDiagnosticText(input).contains("private-token"))
    }

    @Test
    fun handlesMultipleMixedCaseAndStremioUrls() {
        assertEquals("[redacted-url] [redacted-url]", redactDiagnosticText("HTTPS://a.test/secret stremio://b.test/secret"))
    }

    @Test
    fun removesBearerAndNamedCredentialsWhileKeepingStatus() {
        assertEquals("Bearer [redacted] api_key=[redacted] status=403", redactDiagnosticText("Bearer token-value api_key=another-secret status=403"))
    }

    @Test
    fun redactionIsIdempotentAndRetainsNonSensitiveStatus() {
        val safe = redactDiagnosticText("http://192.168.1.10:8080/private/manifest.json?token=secret")
        assertEquals(safe, redactDiagnosticText(safe))
        assertEquals("HTTP 503 timeout", redactDiagnosticText("HTTP 503 timeout"))
    }
}
