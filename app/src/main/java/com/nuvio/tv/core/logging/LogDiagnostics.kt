package com.nuvio.tv.core.logging

fun String?.rawForLog(): String =
    if (this == null) "(null)" else "[redacted]"

fun String?.urlForLog(): String {
    return if (this == null) "(null)" else "[redacted-url]"
}

fun String?.bodySnippetForLog(maxLength: Int = Int.MAX_VALUE): String {
    if (this == null) return "(null)"
    // Bodies and error messages may contain bare login codes or unlabelled tokens.
    // Keep status/timing in separate diagnostic fields, never a raw body snippet.
    return "[redacted-body]".take(maxLength.coerceAtLeast(0))
}

fun Throwable?.diagnosticSummary(): String {
    if (this == null) return "(null)"
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && parts.size < 6) {
        val name = current.javaClass.simpleName.ifBlank { current.javaClass.name }
        parts.add(name)
        current = current.cause
    }
    return parts.joinToString(" <- ")
}
