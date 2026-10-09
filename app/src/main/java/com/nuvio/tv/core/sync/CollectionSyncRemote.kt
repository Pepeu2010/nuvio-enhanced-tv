package com.nuvio.tv.core.sync

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.remote.supabase.SupabaseCollectionBlob
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal interface CollectionSyncRemote {
    suspend fun pull(profileId: Int, stillCurrent: () -> Boolean): SupabaseCollectionBlob?
    suspend fun push(profileId: Int, snapshot: JsonArray, stillCurrent: () -> Boolean)
}

internal class CollectionRpcRemote(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val syncClientIdentity: SyncClientIdentity,
) : CollectionSyncRemote {
    private suspend fun <T> retry(stillCurrent: () -> Boolean, block: suspend () -> T): T {
        if (!stillCurrent()) throw CancellationException("Collection RPC owner changed")
        return try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            if (!stillCurrent()) throw CancellationException("Collection retry owner changed")
            block()
        }
    }

    override suspend fun pull(profileId: Int, stillCurrent: () -> Boolean): SupabaseCollectionBlob? = retry(stillCurrent) {
        postgrest.rpc("sync_pull_collections", buildJsonObject { put("p_profile_id", profileId) })
            .decodeList<SupabaseCollectionBlob>().firstOrNull()
    }

    override suspend fun push(profileId: Int, snapshot: JsonArray, stillCurrent: () -> Boolean) {
        retry(stillCurrent) {
            postgrest.rpc("sync_push_collections", buildJsonObject {
                put("p_profile_id", profileId)
                put("p_collections_json", snapshot)
                putSyncOriginClientId(syncClientIdentity)
            })
        }
    }
}
