package com.nuvio.tv.data.local

import kotlinx.serialization.json.*

/** Retain wire fields the current TV model cannot render when a known field is edited. */
internal object CollectionJsonPreserver {
    fun merge(raw: JsonArray, encoded: JsonArray): JsonArray = mergeArray(raw, encoded, "id")

    private fun key(record: JsonObject, identity: String): String? { return when (identity) {
        "id" -> (record["id"] as? JsonPrimitive)?.contentOrNull
        else -> {
            val provider = (record["provider"] as? JsonPrimitive)?.contentOrNull ?: "addon"
            val fields = when (provider.lowercase()) {
                "addon" -> listOf("addonId", "type", "catalogId")
                "tmdb" -> listOf("tmdbSourceType", "tmdbId", "mediaType")
                "trakt" -> listOf("traktListId", "mediaType")
                else -> return null
            }
            JsonArray(listOf(JsonPrimitive(provider.lowercase())) + fields.map { record[it] ?: JsonNull }).toString()
        }
    } }

    private fun mergeArray(raw: JsonArray, encoded: JsonArray, identity: String): JsonArray {
        val previous = raw.mapNotNull { it as? JsonObject }.mapNotNull { value -> key(value, identity)?.let { it to value } }.toMap()
        val merged = encoded.map { value ->
            val record = value as? JsonObject ?: error("Collection wire item must be an object")
            mergeObject(key(record, identity)?.let { previous[it] }, record)
        }
        // Future source providers cannot be removed through a UI that cannot display them.
        val opaqueSources = if (identity == "source") raw.filter { it is JsonObject && key(it, identity) == null } else emptyList()
        return JsonArray(merged + opaqueSources)
    }

    private fun mergeObject(raw: JsonObject?, encoded: JsonObject): JsonObject = buildJsonObject {
        raw?.forEach { (name, value) -> put(name, value) }
        encoded.forEach { (name, value) ->
            val before = raw?.get(name)
            put(name, when {
                value is JsonArray && before is JsonArray && name in setOf("folders", "sources", "catalogSources") ->
                    mergeArray(before, value, if (name == "folders") "id" else "source")
                value is JsonObject && before is JsonObject -> mergeObject(before, value)
                else -> value
            })
        }
    }
}
