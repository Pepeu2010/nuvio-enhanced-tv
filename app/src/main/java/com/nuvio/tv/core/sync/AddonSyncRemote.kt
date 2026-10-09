package com.nuvio.tv.core.sync

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.remote.supabase.SupabaseAddon
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal interface AddonSyncRemote {
    suspend fun pull(profileId: Int, stillCurrent: () -> Boolean): JsonArray
    suspend fun push(profileId: Int, snapshot: JsonArray, stillCurrent: () -> Boolean)
}

/** Reuse the official addons table and sync_push_addons RPC without new backend fields. */
internal class AddonRpcRemote(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val syncClientIdentity: SyncClientIdentity,
) : AddonSyncRemote {
    private suspend fun <T> retry(stillCurrent: () -> Boolean, block: suspend () -> T): T {
        if (!stillCurrent()) throw CancellationException("Addon RPC owner changed")
        return try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            if (!stillCurrent()) throw CancellationException("Addon retry owner changed")
            block()
        }
    }

    override suspend fun pull(profileId: Int, stillCurrent: () -> Boolean): JsonArray {
        val userId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
            ?: error("Unable to resolve addon sync owner")
        return retry(stillCurrent) {
            val rows = postgrest.from("addons").select { filter {
                eq("user_id", userId); eq("profile_id", profileId)
            } }.decodeList<SupabaseAddon>()
            check(rows.all { it.profileId == profileId && it.userId == userId }) { "Addon response owner mismatch" }
            buildJsonArray { rows.sortedBy { it.sortOrder }.forEach { row ->
                add(buildJsonObject {
                    put("url", row.url); put("name", row.name.orEmpty())
                    put("enabled", row.enabled); put("sort_order", row.sortOrder)
                })
            } }
        }
    }

    override suspend fun push(profileId: Int, snapshot: JsonArray, stillCurrent: () -> Boolean) {
        retry(stillCurrent) {
            postgrest.rpc("sync_push_addons", buildJsonObject {
                put("p_profile_id", profileId); put("p_addons", snapshot)
                putSyncOriginClientId(syncClientIdentity)
            })
        }
    }
}
