package com.nuvio.tv.core.diagnostics

// Addon configuration and signed media credentials can appear inside URL paths,
// queries or userinfo. Diagnostics must never keep any part of those URLs.
private val diagnosticUrl = Regex("(?i)(?:https?|stremio)://[^\\s<>\"']+")
private val diagnosticBearer = Regex("(?i)\\bBearer\\s+[^\\s,;]+")
private val diagnosticSecret = Regex(
    "(?i)\\b(authorization|cookie|api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|realdebrid|premiumize)\\s*[:=]\\s*[^\\s,;]+",
)

internal fun redactDiagnosticText(text: String): String {
    val withoutUrls = diagnosticUrl.replace(text, "[redacted-url]")
    val withoutBearer = diagnosticBearer.replace(withoutUrls, "Bearer [redacted]")
    return diagnosticSecret.replace(withoutBearer) { "${it.groupValues[1]}=[redacted]" }
}
