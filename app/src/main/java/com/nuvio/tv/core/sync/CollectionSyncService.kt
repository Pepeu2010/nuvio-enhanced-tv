package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.CollectionSyncOwner
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectionSyncService @Inject constructor(
    postgrest: Postgrest,
    authManager: AuthManager,
    private val collectionsDataStore: CollectionsDataStore,
    private val profileManager: ProfileManager,
    syncClientIdentity: SyncClientIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile var isSyncingFromRemote: Boolean = false
    private var pushJob: Job? = null
    internal var remote: CollectionSyncRemote = CollectionRpcRemote(postgrest, authManager, syncClientIdentity)

    private suspend fun requireCurrent(owner: CollectionSyncOwner) {
        currentCoroutineContext().ensureActive()
        if (!collectionsDataStore.isCurrent(owner) || profileManager.activeProfileId.value != owner.profileId) {
            throw CancellationException("Collection sync owner changed")
        }
    }

    private suspend fun reconcile(owner: CollectionSyncOwner): Boolean = mutex.withLock {
        requireCurrent(owner)
        val blob = remote.pull(owner.profileId) { collectionsDataStore.isCurrent(owner) }
        requireCurrent(owner)
        check(blob == null || blob.profileId == owner.profileId) { "Collection response profile mismatch" }
        val snapshot = when (val payload = blob?.collectionsJson) {
            null, JsonNull -> JsonArray(emptyList())
            is JsonArray -> payload
            else -> error("Invalid remote collections JSON")
        }
        isSyncingFromRemote = true
        val applied = try { collectionsDataStore.reconcileRemote(owner, snapshot, blob != null) }
            finally { isSyncingFromRemote = false }
        applied.upload?.let { (revision, uploaded) ->
            requireCurrent(owner)
            remote.push(owner.profileId, uploaded) { collectionsDataStore.isCurrent(owner) }
            requireCurrent(owner)
            collectionsDataStore.acknowledgeUpload(owner, revision, uploaded)
        }
        applied.changed
    }

    /** Pull, merge pending edits and upload through the existing official snapshot RPCs. */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val owner = collectionsDataStore.captureSyncOwner() ?: error("Collections require an account")
            reconcile(owner)
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "Collection upload deferred: ${e::class.simpleName}")
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val owner = collectionsDataStore.captureSyncOwner() ?: error("Collections require an account")
            Result.success(reconcile(owner))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "Collection reconciliation deferred: ${e::class.simpleName}")
            Result.failure(e)
        }
    }

    fun triggerPush() {
        if (isSyncingFromRemote) return
        val owner = collectionsDataStore.captureSyncOwner() ?: return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(500)
            try { reconcile(owner) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "Collection upload deferred: ${e::class.simpleName}") }
        }
    }

    private companion object { const val TAG = "CollectionSyncService" }
}
