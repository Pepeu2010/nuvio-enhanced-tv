package com.nuvio.tv.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** A local mutation journal for the existing replacement-snapshot RPCs, not a new protocol. */
@Serializable
internal data class SnapshotSyncState(
    val schema: Int = 1,
    val revision: Long = 0,
    val baseline: JsonArray = JsonArray(emptyList()),
    val local: JsonArray = JsonArray(emptyList()),
    val pending: Boolean = false,
)

internal data class SnapshotSyncPlan(val revision: Long, val remote: JsonArray, val merged: JsonArray)

internal class SnapshotSyncJournal(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val identityKey: String = "id",
) {
    private val lock = Any()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun load(): SnapshotSyncState? = read()?.let {
        require(it.encodeToByteArray().size <= MAX_BYTES) { "Snapshot journal exceeds storage budget" }
        json.decodeFromString<SnapshotSyncState>(it).also { state ->
            require(state.schema == 1 && state.revision >= 0) { "Unsupported snapshot journal" }
            validateSnapshot(state.baseline, identityKey)
            validateSnapshot(state.local, identityKey)
        }
    }

    private fun persist(state: SnapshotSyncState) {
        val payload = json.encodeToString(SnapshotSyncState.serializer(), state)
        require(payload.encodeToByteArray().size <= MAX_BYTES) { "Snapshot journal exceeds storage budget" }
        write(payload)
    }

    fun recordLocal(previous: JsonArray, next: JsonArray) = synchronized(lock) {
        validateSnapshot(previous, identityKey)
        validateSnapshot(next, identityKey)
        val state = load() ?: SnapshotSyncState(baseline = previous, local = previous)
        require(state.revision < Long.MAX_VALUE) { "Snapshot revision exhausted" }
        persist(state.copy(revision = state.revision + 1, local = next, pending = true))
    }

    fun plan(remote: JsonArray): SnapshotSyncPlan = synchronized(lock) {
        validateSnapshot(remote, identityKey)
        val state = load()
        val merged = if (state?.pending == true) {
            mergeSyncSnapshots(state.baseline, state.local, remote, identityKey)
        } else remote
        SnapshotSyncPlan(state?.revision ?: 0, remote, merged)
    }

    /** Refuse a pull planned before another local edit. The caller must not apply stale UI state. */
    fun commitPull(plan: SnapshotSyncPlan): Boolean = synchronized(lock) {
        val current = load()
        if ((current?.revision ?: 0) != plan.revision) return@synchronized false
        persist(SnapshotSyncState(revision = plan.revision, baseline = plan.remote,
            local = plan.merged, pending = plan.merged != plan.remote))
        true
    }

    fun pending(): Pair<Long, JsonArray>? = synchronized(lock) {
        load()?.takeIf { it.pending }?.let { it.revision to it.local }
    }

    /** An edit arriving during an upload stays pending against the acknowledged server snapshot. */
    fun acknowledge(revision: Long, uploaded: JsonArray) = synchronized(lock) {
        validateSnapshot(uploaded, identityKey)
        val state = load() ?: return@synchronized
        val unchanged = state.revision == revision
        persist(state.copy(baseline = uploaded, local = if (unchanged) uploaded else state.local,
            pending = !unchanged && state.local != uploaded))
    }

    companion object { private const val MAX_BYTES = 2 * 1024 * 1024 }
}

private fun validateSnapshot(items: JsonArray, identityKey: String): Map<String, JsonObject> {
    require(items.size <= 4096) { "Snapshot has too many items" }
    val result = linkedMapOf<String, JsonObject>()
    items.forEach { item ->
        val record = item as? JsonObject ?: error("Snapshot item must be an object")
        val id = (record[identityKey] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Snapshot item has no identity")
        require(id.isNotBlank() && id.length <= 16_384 && result.put(id, record) == null) { "Invalid snapshot identity" }
    }
    return result
}

/** Local edits/deletions win conflicts; unrelated remote edits and unknown fields survive. */
internal fun mergeSyncSnapshots(base: JsonArray, local: JsonArray, remote: JsonArray, identityKey: String = "id"): JsonArray {
    fun mergeValue(before: JsonElement?, here: JsonElement?, there: JsonElement?, depth: Int): JsonElement? {
        require(depth <= 32) { "Snapshot nesting exceeds budget" }
        if (here == before) return there
        if (there == before || here == there) return here
        if (here is JsonObject && there is JsonObject) {
            val prior = before as? JsonObject ?: JsonObject(emptyMap())
            return buildJsonObject {
                (there.keys + here.keys + prior.keys).forEach { key ->
                    mergeValue(prior[key], here[key], there[key], depth + 1)?.let { put(key, it) }
                }
            }
        }
        // Nested folders use their stable IDs. Ordinary arrays remain atomic values.
        if (here is JsonArray && there is JsonArray && (before == null || before is JsonArray)) {
            val prior = before as? JsonArray ?: JsonArray(emptyList())
            val keyed = (prior + here + there).all { it is JsonObject && (it["id"] as? JsonPrimitive)?.isString == true }
            if (keyed) return mergeArrays(prior, here, there, "id", depth + 1, ::mergeValue)
        }
        return here
    }
    return mergeArrays(base, local, remote, identityKey, 0, ::mergeValue)
}

private fun mergeArrays(base: JsonArray, local: JsonArray, remote: JsonArray, identityKey: String, depth: Int,
    merge: (JsonElement?, JsonElement?, JsonElement?, Int) -> JsonElement?): JsonArray {
    val before = validateSnapshot(base, identityKey)
    val here = validateSnapshot(local, identityKey)
    val there = validateSnapshot(remote, identityKey)
    val surviving = before.keys.intersect(here.keys)
    val locallyReordered = here.keys.filter { it in surviving } != before.keys.filter { it in surviving }
    val locallyAdded = here.keys.any { it !in before }
    val order = if (locallyReordered || locallyAdded) here.keys + there.keys else there.keys + here.keys
    return JsonArray((order + before.keys).mapNotNull { key -> merge(before[key], here[key], there[key], depth + 1) })
}
