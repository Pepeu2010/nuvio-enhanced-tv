package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.AddonSyncOwner
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AddonSyncService @Inject constructor(
    postgrest: Postgrest,
    authManager: AuthManager,
    private val addonPreferences: AddonPreferences,
    private val profileManager: ProfileManager,
    syncClientIdentity: SyncClientIdentity
) {
    private val mutex = Mutex()
    internal var remote: AddonSyncRemote = AddonRpcRemote(postgrest, authManager, syncClientIdentity)

    internal fun captureSyncOwner(): AddonSyncOwner? = addonPreferences.captureSyncOwner()

    private suspend fun requireCurrent(owner: AddonSyncOwner) {
        currentCoroutineContext().ensureActive()
        if (!addonPreferences.isCurrent(owner) || profileManager.activeProfileId.value != owner.activeProfileId) {
            throw CancellationException("Addon sync owner changed")
        }
    }

    private suspend fun reconcile(owner: AddonSyncOwner): List<String> = mutex.withLock {
        requireCurrent(owner)
        val fetched = remote.pull(owner.profileId) { addonPreferences.isCurrent(owner) }
        requireCurrent(owner)
        val applied = addonPreferences.reconcileRemote(owner, fetched)
        applied.upload?.let { (revision, snapshot) ->
            requireCurrent(owner)
            remote.push(owner.profileId, snapshot) { addonPreferences.isCurrent(owner) }
            requireCurrent(owner)
            addonPreferences.acknowledgeUpload(owner, revision, snapshot)
        }
        applied.snapshot.map { it.jsonObject["url"]!!.jsonPrimitive.content }
    }

    /** Local uploads first pull and merge, so independent remote edits are retained. */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        val owner = captureSyncOwner() ?: return@withContext Result.failure(IllegalStateException("Addons require an account"))
        pushForOwner(owner)
    }

    internal suspend fun pushForOwner(owner: AddonSyncOwner): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Secondary profiles sharing the primary addons may read, never upload.
            if (owner.mayUpload) reconcile(owner)
            else requireCurrent(owner)
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "Addon upload deferred: ${e::class.simpleName}")
            Result.failure(e)
        }
    }

    /** Applies all metadata atomically; the repository's existing flows publish the result. */
    suspend fun getRemoteAddonUrls(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val owner = captureSyncOwner() ?: error("Addons require an account")
            Result.success(reconcile(owner))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "Addon reconciliation deferred: ${e::class.simpleName}")
            Result.failure(e)
        }
    }

    private companion object { const val TAG = "AddonSyncService" }
}
