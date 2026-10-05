package com.nuvio.tv.core.auth.diagnostics

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.diagnostics.redactDiagnosticText
import com.nuvio.tv.core.logging.diagnosticSummary
import com.nuvio.tv.core.logging.urlForLog
import com.nuvio.tv.data.remote.dto.AuthDiagnosticAppDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticDeviceDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticEnvironmentDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticEventDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticExceptionDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticFlowDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticNetworkDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticReportRequestDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticRequestDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticResponseDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticTerminalDto
import com.nuvio.tv.data.repository.AuthDiagnosticReportRepository
import com.nuvio.tv.domain.model.ServerConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

private const val TAG = "AuthDiagnostics"
private const val EXCLUDED_CREDENTIAL = "[excluded-credential]"
private val credentialKeyRegex = Regex("(authorization|cookie|token|secret|password|apikey|api_key|accesskey|session|refresh|bearer|nonce|code|verification|redirect|location|email|user.?id)", RegexOption.IGNORE_CASE)
private val authDiagnosticsJson = Json { ignoreUnknownKeys = true }

class AuthDiagnosticsSession(
    private val repository: AuthDiagnosticReportRepository,
    private val flowType: String,
    private val qrTraceId: Long? = null,
    private val serverConfiguration: ServerConfiguration,
    private val attemptId: String = UUID.randomUUID().toString()
) {
    private val startedAtMs = System.currentTimeMillis()
    private val startedElapsedMs = SystemClock.elapsedRealtime()
    private val lock = Any()
    private val events = mutableListOf<AuthDiagnosticEventDto>()
    private val rawLogs = mutableListOf<String>()
    private var finished = false

    fun recordRequest(endpoint: String, method: String, url: String, headers: Map<String, String>, body: String?) {
        append(
            AuthDiagnosticEventDto(
                type = "request",
                timeMs = System.currentTimeMillis(),
                elapsedMs = elapsedMs(),
                endpoint = endpoint,
                method = method,
                url = url.urlForLog(),
                request = AuthDiagnosticRequestDto(
                    headers = authDiagnosticFilteredHeaders(headers),
                    body = authDiagnosticFilteredBody(body)
                )
            )
        )
    }

    fun recordResponse(endpoint: String, method: String, url: String, statusCode: Int?, isSuccessful: Boolean?, headers: Map<String, String>, body: String?, contentType: String?) {
        append(
            AuthDiagnosticEventDto(
                type = "response",
                timeMs = System.currentTimeMillis(),
                elapsedMs = elapsedMs(),
                endpoint = endpoint,
                method = method,
                url = url.urlForLog(),
                response = AuthDiagnosticResponseDto(
                    statusCode = statusCode,
                    isSuccessful = isSuccessful,
                    headers = authDiagnosticFilteredHeaders(headers),
                    body = authDiagnosticFilteredBody(body),
                    bodyBytes = body?.toByteArray()?.size,
                    contentType = contentType
                )
            )
        )
    }

    fun recordNetwork(endpoint: String, phase: String, host: String? = null, inetAddresses: List<String> = emptyList(), proxy: String? = null, protocol: String? = null, durationMs: Long? = null, message: String? = null) {
        append(
            AuthDiagnosticEventDto(
                type = "network",
                timeMs = System.currentTimeMillis(),
                elapsedMs = elapsedMs(),
                endpoint = endpoint,
                network = AuthDiagnosticNetworkDto(
                    phase = phase,
                    host = host,
                    inetAddresses = inetAddresses,
                    proxy = proxy,
                    protocol = protocol,
                    durationMs = durationMs,
                    message = message?.let { EXCLUDED_CREDENTIAL }
                )
            )
        )
    }

    fun recordException(endpoint: String?, error: Throwable) {
        append(
            AuthDiagnosticEventDto(
                type = "exception",
                timeMs = System.currentTimeMillis(),
                elapsedMs = elapsedMs(),
                endpoint = endpoint,
                exception = error.toAuthDiagnosticExceptionDto()
            )
        )
    }

    fun recordState(name: String, detail: Map<String, String?> = emptyMap()) {
        append(
            AuthDiagnosticEventDto(
                type = "state",
                timeMs = System.currentTimeMillis(),
                elapsedMs = elapsedMs(),
                detail = mapOf("name" to name) + authDiagnosticFilteredDetail(detail)
            )
        )
    }

    suspend fun finishSuccess(reason: String = "completed"): Result<String> =
        finishTerminal(status = "success", reason = reason, failingEndpoint = null, httpStatus = null, error = null)

    suspend fun finishFailure(reason: String, failingEndpoint: String?, httpStatus: Int? = null, error: Throwable? = null): Result<String> =
        finishTerminal(status = "failed", reason = reason, failingEndpoint = failingEndpoint, httpStatus = httpStatus, error = error)

    suspend fun finishTerminal(status: String, reason: String, failingEndpoint: String?, httpStatus: Int? = null, error: Throwable? = null): Result<String> {
        val payload = synchronized(lock) {
            if (finished) return@synchronized null
            error?.let {
                appendLocked(
                    AuthDiagnosticEventDto(
                        type = "exception",
                        timeMs = System.currentTimeMillis(),
                        elapsedMs = elapsedMs(),
                        endpoint = failingEndpoint,
                        exception = it.toAuthDiagnosticExceptionDto()
                    )
                )
            }
            val terminal = AuthDiagnosticTerminalDto(
                status = status,
                reason = reason,
                failingEndpoint = failingEndpoint,
                httpStatus = httpStatus,
                networkErrorFamily = authNetworkErrorFamily(error)
            )
            appendLocked(
                AuthDiagnosticEventDto(
                    type = "terminal",
                    timeMs = System.currentTimeMillis(),
                    elapsedMs = elapsedMs(),
                    endpoint = failingEndpoint,
                    detail = mapOf(
                        "status" to terminal.status,
                        "reason" to terminal.reason,
                        "failingEndpoint" to (terminal.failingEndpoint ?: ""),
                        "httpStatus" to (terminal.httpStatus?.toString() ?: ""),
                        "networkErrorFamily" to (terminal.networkErrorFamily ?: "")
                    )
                )
            )
            finished = true
            AuthDiagnosticReportRequestDto(
                schemaVersion = 1,
                startedAtMs = startedAtMs,
                endedAtMs = System.currentTimeMillis(),
                elapsedMs = elapsedMs(),
                app = AuthDiagnosticAppDto(
                    applicationId = BuildConfig.APPLICATION_ID,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE.toLong(),
                    debugBuild = BuildConfig.IS_DEBUG_BUILD
                ),
                device = AuthDiagnosticDeviceDto(
                    manufacturer = Build.MANUFACTURER.orEmpty(),
                    brand = Build.BRAND.orEmpty(),
                    model = Build.MODEL.orEmpty(),
                    product = Build.PRODUCT.orEmpty(),
                    androidRelease = Build.VERSION.RELEASE.orEmpty(),
                    sdkInt = Build.VERSION.SDK_INT,
                    supportedAbis = Build.SUPPORTED_ABIS.orEmpty().toList()
                ),
                environment = AuthDiagnosticEnvironmentDto(
                    supabaseUrl = serverConfiguration.backendUrl.urlForLog(),
                    supabaseHost = serverConfiguration.backendUrl.hostOrNull(),
                    tvLoginWebBaseUrl = serverConfiguration.tvLoginWebBaseUrl.urlForLog(),
                    tvLoginHost = serverConfiguration.tvLoginWebBaseUrl.orEmpty().hostOrNull(),
                    tvLoginWebHost = serverConfiguration.tvLoginWebBaseUrl.orEmpty().hostOrNull(),
                    reportsBaseUrlConfigured = BuildConfig.PLAYBACK_REPORTS_BASE_URL.isNotBlank()
                ),
                flow = AuthDiagnosticFlowDto(
                    type = flowType,
                    attemptId = attemptId,
                    qrTraceId = qrTraceId
                ),
                terminal = terminal,
                timeline = events.toList(),
                exceptions = events.mapNotNull { it.exception },
                rawLogs = rawLogs.toList()
            )
        }
        if (payload == null) return Result.failure(IllegalStateException("Auth diagnostics session already finished"))
        if (serverConfiguration.isCustom) {
            Log.d(TAG, "attempt=$attemptId flow=$flowType upload=disabled")
            return Result.success("disabled")
        }
        val result = repository.submit(payload)
        result.fold(
            onSuccess = { reportId -> Log.d(TAG, "attempt=$attemptId flow=$flowType upload=success reportId=$reportId") },
            onFailure = { uploadError -> Log.w(TAG, "attempt=$attemptId flow=$flowType upload=queued error=${uploadError.diagnosticSummary()}") }
        )
        return result
    }

    fun isFinished(): Boolean = synchronized(lock) { finished }

    private fun append(event: AuthDiagnosticEventDto) {
        synchronized(lock) {
            if (finished && event.type != "terminal") return
            appendLocked(event)
        }
    }

    private fun appendLocked(event: AuthDiagnosticEventDto) {
        val line = event.toLogLine()
        events += event
        rawLogs += "${event.timeMs} $line"
        if (event.exception != null) Log.e(TAG, line) else Log.d(TAG, line)
    }

    private fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedElapsedMs

    private fun AuthDiagnosticEventDto.toLogLine(): String {
        val parts = mutableListOf(
            "attempt=$attemptId",
            "flow=$flowType",
            "type=$type",
            "elapsedMs=$elapsedMs"
        )
        endpoint?.let { parts += "endpoint=$it" }
        method?.let { parts += "method=$it" }
        response?.statusCode?.let { parts += "http=$it" }
        network?.phase?.let { parts += "phase=$it" }
        network?.durationMs?.let { parts += "durationMs=$it" }
        exception?.let { parts += "exception=${it.className}: ${it.message.orEmpty()}" }
        detail?.get("name")?.let { parts += "state=$it" }
        return parts.joinToString(" ")
    }
}

class AuthDiagnosticEventListenerFactory(
    private val session: AuthDiagnosticsSession,
    private val endpoint: String
) : EventListener.Factory {
    override fun create(call: Call): EventListener = AuthDiagnosticEventListener(session, endpoint)
}

private class AuthDiagnosticEventListener(
    private val session: AuthDiagnosticsSession,
    private val endpoint: String
) : EventListener() {
    private val callStartMs = SystemClock.elapsedRealtime()
    private var dnsStartMs: Long? = null
    private var connectStartMs: Long? = null
    private var tlsStartMs: Long? = null
    private var requestHeadersStartMs: Long? = null
    private var responseHeadersStartMs: Long? = null

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartMs = SystemClock.elapsedRealtime()
        session.recordNetwork(endpoint = endpoint, phase = "dnsStart", host = domainName)
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        session.recordNetwork(
            endpoint = endpoint,
            phase = "dnsEnd",
            host = domainName,
            inetAddresses = inetAddressList.map { it.hostAddress.orEmpty() },
            durationMs = dnsStartMs.durationSinceNow()
        )
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStartMs = SystemClock.elapsedRealtime()
        session.recordNetwork(
            endpoint = endpoint,
            phase = "connectStart",
            host = inetSocketAddress.hostString,
            proxy = proxy.toString()
        )
    }

    override fun secureConnectStart(call: Call) {
        tlsStartMs = SystemClock.elapsedRealtime()
        session.recordNetwork(endpoint = endpoint, phase = "secureConnectStart")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        session.recordNetwork(
            endpoint = endpoint,
            phase = "secureConnectEnd",
            protocol = handshake?.tlsVersion?.javaName,
            durationMs = tlsStartMs.durationSinceNow(),
            message = handshake?.cipherSuite?.javaName
        )
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        session.recordNetwork(
            endpoint = endpoint,
            phase = "connectEnd",
            host = inetSocketAddress.hostString,
            proxy = proxy.toString(),
            protocol = protocol?.toString(),
            durationMs = connectStartMs.durationSinceNow()
        )
    }

    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        session.recordNetwork(
            endpoint = endpoint,
            phase = "connectFailed",
            host = inetSocketAddress.hostString,
            proxy = proxy.toString(),
            protocol = protocol?.toString(),
            durationMs = connectStartMs.durationSinceNow(),
            message = ioe.diagnosticSummary()
        )
    }

    override fun requestHeadersStart(call: Call) {
        requestHeadersStartMs = SystemClock.elapsedRealtime()
        session.recordNetwork(endpoint = endpoint, phase = "requestHeadersStart")
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        session.recordNetwork(
            endpoint = endpoint,
            phase = "requestHeadersEnd",
            host = request.url.host,
            durationMs = requestHeadersStartMs.durationSinceNow()
        )
    }

    override fun responseHeadersStart(call: Call) {
        responseHeadersStartMs = SystemClock.elapsedRealtime()
        session.recordNetwork(endpoint = endpoint, phase = "responseHeadersStart")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        session.recordNetwork(
            endpoint = endpoint,
            phase = "responseHeadersEnd",
            host = response.request.url.host,
            protocol = response.protocol.toString(),
            durationMs = responseHeadersStartMs.durationSinceNow(),
            message = response.code.toString()
        )
    }

    override fun callEnd(call: Call) {
        session.recordNetwork(endpoint = endpoint, phase = "callEnd", durationMs = callStartMs.durationSinceNow())
    }

    override fun callFailed(call: Call, ioe: IOException) {
        session.recordNetwork(
            endpoint = endpoint,
            phase = "callFailed",
            durationMs = callStartMs.durationSinceNow(),
            message = ioe.diagnosticSummary()
        )
    }
}

fun authDiagnosticFilteredHeaders(headers: Map<String, String>): Map<String, String> =
    headers.mapValues { (key, value) ->
        if (key.lowercase() in setOf("content-type", "content-length", "accept")) redactDiagnosticText(value)
        else EXCLUDED_CREDENTIAL
    }

fun authDiagnosticFilteredBody(body: String?): String? {
    if (body == null) return null
    if (body.isBlank()) return body
    if (body == EXCLUDED_CREDENTIAL) return body
    val parsed = runCatching { authDiagnosticsJson.parseToJsonElement(body) }.getOrNull()
    if (parsed is JsonObject || parsed is JsonArray) return filterJsonElement(parsed).toString()
    return EXCLUDED_CREDENTIAL
}

// Apply at the persistence/upload boundary as well, including reports queued
// by an older build. Legacy stack traces and raw logs cannot be safely parsed.
internal fun AuthDiagnosticReportRequestDto.redactedForDiagnostics(): AuthDiagnosticReportRequestDto = copy(
    environment = environment.copy(
        supabaseUrl = environment.supabaseUrl.urlForLog(),
        tvLoginWebBaseUrl = environment.tvLoginWebBaseUrl.urlForLog(),
    ),
    terminal = terminal.copy(reason = EXCLUDED_CREDENTIAL),
    timeline = timeline.map { event ->
        event.copy(
            url = event.url?.urlForLog(),
            request = event.request?.let { request -> request.copy(
                headers = authDiagnosticFilteredHeaders(request.headers),
                body = authDiagnosticFilteredBody(request.body),
            ) },
            response = event.response?.let { response -> response.copy(
                headers = authDiagnosticFilteredHeaders(response.headers),
                body = authDiagnosticFilteredBody(response.body),
            ) },
            network = event.network?.copy(message = EXCLUDED_CREDENTIAL, proxy = null),
            detail = event.detail?.mapValues { EXCLUDED_CREDENTIAL },
            exception = event.exception?.withoutLegacyMessages(),
        )
    },
    exceptions = exceptions.map { it.withoutLegacyMessages() },
    rawLogs = rawLogs.map { EXCLUDED_CREDENTIAL },
)

private fun AuthDiagnosticExceptionDto.withoutLegacyMessages(): AuthDiagnosticExceptionDto = copy(
    message = EXCLUDED_CREDENTIAL,
    causeChain = emptyList(),
    stackTrace = EXCLUDED_CREDENTIAL,
)

fun authNetworkErrorFamily(error: Throwable?): String? {
    if (error == null) return null
    if (error.causeChain().any { it.startsWith("com.nuvio.tv.core.auth.AuthHttpException:") }) return null
    return when {
        error.hasCause<UnknownHostException>() -> "dns"
        error.hasCause<SocketTimeoutException>() -> "timeout"
        error.hasCause<SSLException>() -> "tls"
        error.hasCause<ConnectException>() -> "connect"
        error.hasCause<NoRouteToHostException>() -> "route"
        error.hasCause<IOException>() -> "io"
        else -> error.javaClass.simpleName.takeIf { it.isNotBlank() }?.lowercase()
    }
}

private fun authDiagnosticFilteredDetail(detail: Map<String, String?>): Map<String, String> =
    detail.mapNotNull { (key, value) ->
        if (value == null) null else key to EXCLUDED_CREDENTIAL
    }.toMap()

private fun filterJsonElement(element: JsonElement, key: String? = null): JsonElement {
    if (key?.isCredentialKey() == true) return JsonPrimitive(EXCLUDED_CREDENTIAL)
    return when (element) {
        is JsonObject -> JsonObject(element.mapValues { (childKey, childValue) -> filterJsonElement(childValue, childKey) })
        is JsonArray -> JsonArray(element.map { child -> filterJsonElement(child) })
        is JsonPrimitive -> if (!element.isString && (
            element == JsonNull || element.booleanOrNull != null || element.doubleOrNull?.isFinite() == true
        )) element else JsonPrimitive(EXCLUDED_CREDENTIAL)
        else -> element
    }
}

private fun String.isCredentialKey(): Boolean = credentialKeyRegex.containsMatchIn(this)

private fun Long?.durationSinceNow(): Long? = this?.let { SystemClock.elapsedRealtime() - it }

private fun String.hostOrNull(): String? =
    runCatching { Uri.parse(this).host }.getOrNull()?.takeIf { it.isNotBlank() }

private fun Throwable.toAuthDiagnosticExceptionDto(): AuthDiagnosticExceptionDto =
    AuthDiagnosticExceptionDto(
        className = javaClass.name,
        message = EXCLUDED_CREDENTIAL,
        causeChain = causeChain(),
        stackTrace = stackTraceString()
    )

private fun Throwable.causeChain(): List<String> {
    val chain = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && chain.size < 6) {
        chain += "${current.javaClass.name}:"
        current = current.cause
    }
    return chain
}

private fun Throwable.stackTraceString(): String {
    return stackTrace.joinToString("\n") { "\tat $it" }
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return true
        current = current.cause
    }
    return false
}
