package com.nuvio.tv.core.diagnostics

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.nuvio.tv.core.logging.rawForLog
import com.nuvio.tv.core.logging.urlForLog
import com.nuvio.tv.core.logging.bodySnippetForLog
import com.nuvio.tv.core.logging.diagnosticSummary
import com.nuvio.tv.core.auth.diagnostics.authDiagnosticFilteredBody
import com.nuvio.tv.core.auth.diagnostics.authDiagnosticFilteredHeaders
import com.nuvio.tv.core.auth.diagnostics.authNetworkErrorFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.SocketTimeoutException
import com.nuvio.tv.core.auth.diagnostics.AuthDiagnosticsSession
import com.nuvio.tv.data.repository.AuthDiagnosticReportRepository
import com.nuvio.tv.data.repository.AuthDiagnosticReportQueue
import com.nuvio.tv.data.remote.dto.AuthDiagnosticReportRequestDto
import com.nuvio.tv.domain.model.ServerConfiguration
import com.nuvio.tv.domain.model.ServerCapabilities
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest

class DiagnosticRedactionTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun legacyQueueIsMigratedBeforeRetryAndNewWritesCannotStoreSecrets() = runTest {
        val report = fixtureReport()
        val legacy = report.copy(
            environment = report.environment.copy(supabaseUrl = "https://nuvio.test/private-path"),
            rawLogs = listOf("code=ABC-123 nonce=private-nonce"),
            exceptions = report.exceptions.map { it.copy(message = "ABC-123", stackTrace = "private-nonce", causeChain = listOf("bare-secret")) },
            timeline = report.timeline.map { event -> event.copy(
                url = "https://nuvio.test/link?code=ABC-123",
                request = event.request?.copy(body = """{"nonce":"private-nonce"}""", headers = mapOf("X-Unknown" to "bare-secret")),
                response = event.response?.copy(body = "ABC-123"),
                exception = event.exception?.copy(message = "ABC-123", stackTrace = "bare-secret"),
                network = event.network?.copy(message = "private-nonce"),
                detail = event.detail?.mapValues { "bare-secret" },
            ) },
        )
        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(AuthDiagnosticReportRequestDto::class.java)
        val file = directory.newFile("legacy-auth.jsonl")
        file.writeText(adapter.toJson(legacy) + "\nmalformed ABC-123\n")
        assertTrue(file.readText().contains("ABC-123"))
        val queue = AuthDiagnosticReportQueue(file, moshi, maxReports = 2)
        val migrated = queue.read()
        assertEquals(1, migrated.size)
        assertEquals(legacy.flow.attemptId, migrated.single().flow.attemptId)
        assertEquals(401, migrated.single().terminal.httpStatus)
        assertEquals("timeout", migrated.single().terminal.networkErrorFamily)
        for (secret in listOf("ABC-123", "private-nonce", "bare-secret", "private-path")) {
            assertFalse(file.readText().contains(secret))
        }
        assertEquals(migrated, queue.read())
        queue.enqueue(legacy)
        queue.enqueue(legacy)
        assertEquals(2, queue.read().size)
        assertFalse(file.readText().contains("ABC-123"))
        queue.write(emptyList())
        assertFalse(file.exists())
    }

    @Test
    fun queuedOrUploadedReportContainsNoSyntheticLoginSecrets() = runTest {
        val report = fixtureReport()
        val payload = Moshi.Builder().build().adapter(AuthDiagnosticReportRequestDto::class.java).toJson(report)
        for (secret in listOf("ABC-123", "private-nonce", "bare-secret", "private-path")) {
            assertFalse("Diagnostic report leaked synthetic value", payload.contains(secret))
        }
        assertEquals(401, report.terminal.httpStatus)
        assertEquals("timeout", report.terminal.networkErrorFamily)
        assertEquals("java.lang.IllegalStateException", report.exceptions.single().className)
    }

    private suspend fun fixtureReport(): AuthDiagnosticReportRequestDto {
        val report = slot<AuthDiagnosticReportRequestDto>()
        val repository = mockk<AuthDiagnosticReportRepository>()
        coEvery { repository.submit(capture(report)) } returns Result.success("test-report")
        val session = AuthDiagnosticsSession(
            repository = repository,
            flowType = "synthetic-test",
            serverConfiguration = ServerConfiguration(
                backendUrl = "https://nuvio.test/private-path",
                publishableKey = "unused-test-key",
                capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
                isCustom = false,
                tvLoginWebBaseUrl = "https://nuvio.test/link?code=ABC-123",
            ),
        )
        session.recordRequest("start", "POST", "https://nuvio.test/link?nonce=private-nonce", mapOf("X-Unknown" to "bare-secret"), """{"nonce":"private-nonce"}""")
        session.recordResponse("start", "POST", "https://nuvio.test/link?code=ABC-123", 401, false, emptyMap(), "ABC-123", "application/json")
        session.recordNetwork("start", "failed", message = "ABC-123")
        session.recordState("starting", mapOf("unknown" to "bare-secret"))
        session.finishFailure("synthetic-failure", "start", 401, IllegalStateException("ABC-123", SocketTimeoutException("private-nonce")))
        coVerify(exactly = 1) { repository.submit(any()) }
        return report.captured
    }

    @Test
    fun loginScalarsUrlsAndBareBodiesAreNeverPrinted() {
        val code = "ABC-123"
        assertEquals("[redacted]", code.rawForLog())
        assertEquals("[redacted-url]", "https://nuvio.test/link?code=$code".urlForLog())
        assertEquals("[redacted-body]", code.bodySnippetForLog())
        assertEquals("[redacted-body]", "unlabelled-token".bodySnippetForLog())
        assertEquals("", code.bodySnippetForLog(-1))
        assertEquals("(null)", (null as String?).rawForLog())
    }

    @Test
    fun summariesKeepExceptionTypesWithoutMessagesOrCauseSecrets() {
        val error = IllegalStateException("ABC-123", SocketTimeoutException("nonce=private-nonce"))
        assertEquals("IllegalStateException <- SocketTimeoutException", error.diagnosticSummary())
        assertEquals("timeout", authNetworkErrorFamily(error))
    }

    @Test
    fun authReportFiltersNestedCodesUnknownStringsAndMalformedResponses() {
        val body = """{"device_code":123456,"nonce":"private-nonce","items":[{"unknown":"bare-secret"}],"expires_in":120,"ready":true}"""
        val filtered = authDiagnosticFilteredBody(body)!!
        assertFalse(filtered.contains("123456"))
        assertFalse(filtered.contains("private-nonce"))
        assertFalse(filtered.contains("bare-secret"))
        val json = Json.parseToJsonElement(filtered).jsonObject
        assertEquals("120", json.getValue("expires_in").jsonPrimitive.content)
        assertEquals("true", json.getValue("ready").jsonPrimitive.content)
        assertEquals("[excluded-credential]", authDiagnosticFilteredBody("not-json ABC-123"))
        assertEquals("[excluded-credential]", authDiagnosticFilteredBody("\"bare-secret\""))
        assertEquals("[excluded-credential]", authDiagnosticFilteredBody("ABC-123"))
        assertEquals("[excluded-credential]", authDiagnosticFilteredBody("123456"))
        assertFalse(authDiagnosticFilteredBody("""{"unknown":bare-secret}""")!!.contains("bare-secret"))
        assertEquals(filtered, authDiagnosticFilteredBody(filtered))
    }

    @Test
    fun reportHeadersKeepMediaTypeAndDropUnknownAuthenticationValues() {
        val filtered = authDiagnosticFilteredHeaders(mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer private-token",
            "X-Device-Nonce" to "private-nonce",
            "X-Unknown" to "bare-secret",
            "Location" to "https://nuvio.test/link?code=ABC-123",
        ))
        assertEquals("application/json", filtered["Content-Type"])
        assertFalse(filtered.values.any { it.contains("private") || it.contains("secret") || it.contains("ABC-123") })
    }

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
