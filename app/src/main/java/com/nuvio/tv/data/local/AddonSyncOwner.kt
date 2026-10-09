package com.nuvio.tv.data.local

import kotlinx.serialization.json.*

internal data class AddonSyncOwner(
    val userId: String, val activeProfileId: Int, val profileId: Int,
    val backendUrl: String, val mayUpload: Boolean,
)
internal data class AddonSyncApplication(val snapshot: JsonArray, val upload: Pair<Long, JsonArray>?)

/** Keep configuration query strings byte-for-byte, including case-sensitive tokens. */
internal fun canonicalAddonUrl(url: String): String {
    val trimmed = url.trim()
    val queryStart = trimmed.indexOf('?')
    val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
    val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
    return (if (path.endsWith("/manifest.json", ignoreCase = true)) path.dropLast(14) else path).trimEnd('/') + query
}

/** Only the existing official addon RPC fields are sent; array order is authoritative. */
internal fun normalizedAddonSnapshot(items: JsonArray): JsonArray {
    require(items.size <= 4096) { "Too many addons" }
    val seen = hashSetOf<String>()
    return JsonArray(items.mapIndexed { index, element ->
        val item = element as? JsonObject ?: error("Invalid addon record")
        val rawUrl = (item["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Invalid addon URL")
        val url = canonicalAddonUrl(rawUrl)
        require(url.isNotBlank() && url.length <= 16_384 && seen.add(url)) { "Invalid addon identity" }
        val name = when (val value = item["name"]) {
            null, JsonNull -> ""
            is JsonPrimitive -> value.takeIf { it.isString }?.content ?: error("Invalid addon name")
            else -> error("Invalid addon name")
        }
        val enabled = when (val value = item["enabled"]) {
            null -> true
            is JsonPrimitive -> value.takeIf { !it.isString }?.booleanOrNull ?: error("Invalid addon enabled state")
            else -> error("Invalid addon enabled state")
        }
        buildJsonObject { put("url", url); put("name", name); put("enabled", enabled); put("sort_order", index) }
    })
}
