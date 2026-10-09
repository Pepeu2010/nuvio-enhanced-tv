package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.SnapshotSyncJournal
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.security.MessageDigest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledAddonPreferences(
    val urls: List<String>, val names: Map<String, String>, val enabledStates: Map<String, Boolean>,
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AddonPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val authManager: AuthManager,
    private val serverConfiguration: ServerConfiguration
) {
    companion object {
        private const val FEATURE = "addon_preferences"
    }

    private fun effectiveProfileId(): Int {
        val active = profileManager.activeProfile
        return if (active != null && active.usesPrimaryAddons) 1 else profileManager.activeProfileId.value
    }

    private fun store(profileId: Int = effectiveProfileId()) =
        factory.get(profileId, FEATURE)

    private val effectiveProfileIdFlow: Flow<Int> = combine(
        profileManager.activeProfileId,
        profileManager.profiles
    ) { activeProfileId, profiles ->
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
        if (activeProfile?.usesPrimaryAddons == true) 1 else activeProfileId
    }.distinctUntilChanged()

    private val gson = Gson()
    private val orderedUrlsKey = stringPreferencesKey("installed_addon_urls_ordered")
    private val legacyUrlsKey = stringSetPreferencesKey("installed_addon_urls")
    private val userSetNamesKey = stringPreferencesKey("addon_user_set_names")
    private val addonEnabledStatesKey = stringPreferencesKey("installed_addon_enabled_states")
    private fun canonicalizeUrl(url: String): String = canonicalAddonUrl(url)

    /** A single emission prevents mixed URL/name/enabled revisions in manifest consumers. */
    val installedSettings: Flow<InstalledAddonPreferences> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            InstalledAddonPreferences(getCurrentList(prefs),
                prefs[userSetNamesKey]?.let(::parseNameMap).orEmpty(), getCurrentEnabledStates(prefs))
        }
    }.distinctUntilChanged()

    val installedAddonUrls: Flow<List<String>> = installedSettings.map { it.urls }.distinctUntilChanged()

    val addonEnabledStates: Flow<Map<String, Boolean>> = installedSettings.map { it.enabledStates }.distinctUntilChanged()

    suspend fun ensureMigrated() {
        val ds = store()
        val prefs = ds.data.first()
        if (prefs[orderedUrlsKey] == null) {
            val legacySet = prefs[legacyUrlsKey] ?: getDefaultAddons()
            ds.edit { preferences ->
                preferences[orderedUrlsKey] = gson.toJson(legacySet.toList())
                preferences.remove(legacyUrlsKey)
            }
        }
    }

    internal fun captureSyncOwner(): AddonSyncOwner? {
        val account = (authManager.authState.value as? AuthState.FullAccount)?.userId ?: return null
        val active = profileManager.activeProfile
        val readOnly = active != null && !active.isPrimary && active.usesPrimaryAddons
        return AddonSyncOwner(account, profileManager.activeProfileId.value, effectiveProfileId(),
            serverConfiguration.backendUrl, !readOnly)
    }

    internal fun isCurrent(owner: AddonSyncOwner): Boolean = captureSyncOwner() == owner

    private fun requireCurrent(owner: AddonSyncOwner) {
        if (!isCurrent(owner)) throw CancellationException("Addon sync owner changed")
    }

    private fun journal(prefs: MutablePreferences, owner: AddonSyncOwner): SnapshotSyncJournal {
        val encoded = JsonArray(listOf(JsonPrimitive(owner.backendUrl), JsonPrimitive(owner.userId), JsonPrimitive(owner.profileId)))
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        val key = stringPreferencesKey("sync_journal_v1_$digest")
        return SnapshotSyncJournal({ prefs[key] }, { prefs[key] = it }, identityKey = "url")
    }

    private fun snapshot(prefs: Preferences): JsonArray {
        val names = prefs[userSetNamesKey]?.let(::parseNameMap).orEmpty()
        val states = getCurrentEnabledStates(prefs)
        return normalizedAddonSnapshot(buildJsonArray {
            getCurrentList(prefs).forEach { raw ->
                val url = canonicalizeUrl(raw)
                add(buildJsonObject { put("url", url); put("name", names[url].orEmpty()); put("enabled", states[url] ?: true) })
            }
        })
    }

    private fun applySnapshot(prefs: MutablePreferences, snapshot: JsonArray) {
        val items = snapshot.map { it.jsonObject }
        prefs[orderedUrlsKey] = gson.toJson(items.map { it["url"]!!.jsonPrimitive.content })
        prefs[userSetNamesKey] = gson.toJson(items.associate { it["url"]!!.jsonPrimitive.content to it["name"]!!.jsonPrimitive.content })
        prefs[addonEnabledStatesKey] = gson.toJson(items.associate { it["url"]!!.jsonPrimitive.content to it["enabled"]!!.jsonPrimitive.boolean })
        prefs.remove(legacyUrlsKey)
    }

    private suspend fun mutate(transform: (MutablePreferences) -> Unit): Boolean {
        val active = profileManager.activeProfile
        if (active != null && !active.isPrimary && active.usesPrimaryAddons) return false
        val profileId = effectiveProfileId()
        val activeId = profileManager.activeProfileId.value
        val auth = authManager.authState.value
        val owner = captureSyncOwner()
        var changed = false
        store(profileId).edit { prefs ->
            if (profileManager.activeProfileId.value != activeId || effectiveProfileId() != profileId ||
                authManager.authState.value != auth || (owner != null && !isCurrent(owner))) {
                throw CancellationException("Addon mutation owner changed")
            }
            val previous = snapshot(prefs)
            transform(prefs)
            val next = snapshot(prefs)
            changed = previous != next
            if (changed && owner != null) journal(prefs, owner).recordLocal(previous, next)
        }
        return changed
    }

    /** URLs, names, enabled states and pending revisions share one atomic disk transaction. */
    internal suspend fun reconcileRemote(owner: AddonSyncOwner, remote: JsonArray): AddonSyncApplication {
        val normalized = normalizedAddonSnapshot(remote)
        var applied: AddonSyncApplication? = null
        store(owner.profileId).edit { prefs ->
            requireCurrent(owner)
            val journal = journal(prefs, owner)
            val rawPlan = journal.plan(normalized)
            val plan = rawPlan.copy(merged = normalizedAddonSnapshot(rawPlan.merged))
            check(journal.commitPull(plan)) { "Stale addon reconciliation" }
            applySnapshot(prefs, plan.merged)
            applied = AddonSyncApplication(plan.merged, journal.pending().takeIf { owner.mayUpload })
        }
        return checkNotNull(applied)
    }

    internal suspend fun acknowledgeUpload(owner: AddonSyncOwner, revision: Long, uploaded: JsonArray) {
        store(owner.profileId).edit { prefs ->
            requireCurrent(owner)
            journal(prefs, owner).acknowledge(revision, uploaded)
        }
    }

    suspend fun addAddon(url: String): Boolean = mutate { preferences ->
            val current = getCurrentList(preferences)
            val normalizedUrl = canonicalizeUrl(url)
            if (current.any { canonicalizeUrl(it) == normalizedUrl }) return@mutate
            preferences[orderedUrlsKey] = gson.toJson(current + normalizedUrl)
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            states[normalizedUrl] = true
            preferences[addonEnabledStatesKey] = gson.toJson(states)
    }

    suspend fun removeAddon(url: String): Boolean = mutate { preferences ->
            val current = getCurrentList(preferences).toMutableList()
            val normalizedUrl = canonicalizeUrl(url)

            val indexToRemove = current.indexOfFirst {
                canonicalizeUrl(it) == normalizedUrl
            }
            if (indexToRemove != -1) {
                current.removeAt(indexToRemove)
            }
            if (indexToRemove == -1) return@mutate
            preferences[orderedUrlsKey] = gson.toJson(current)
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            states.remove(normalizedUrl)
            preferences[addonEnabledStatesKey] = gson.toJson(states)
            val names = preferences[userSetNamesKey]?.let(::parseNameMap).orEmpty().toMutableMap()
            names.remove(normalizedUrl)
            preferences[userSetNamesKey] = gson.toJson(names)
    }

    suspend fun setAddonOrder(urls: List<String>): Boolean = mutate { preferences ->
            val orderedUrls = urls.map(::canonicalizeUrl)
            val currentUrls = getCurrentList(preferences).map(::canonicalizeUrl)
            if (orderedUrls == currentUrls) return@mutate
            preferences[orderedUrlsKey] = gson.toJson(orderedUrls)
            val currentStates = getCurrentEnabledStates(preferences)
            preferences[addonEnabledStatesKey] = gson.toJson(
                orderedUrls.associateWith { url -> currentStates[url] ?: true }
            )
    }

    suspend fun setAddonEnabled(url: String, enabled: Boolean): Boolean = mutate { preferences ->
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            val normalizedUrl = canonicalizeUrl(url)
            if (getCurrentList(preferences).none { canonicalizeUrl(it) == normalizedUrl }) return@mutate
            if ((states[normalizedUrl] ?: true) == enabled) return@mutate
            states[normalizedUrl] = enabled
            preferences[addonEnabledStatesKey] = gson.toJson(states)
    }

    suspend fun setAddonEnabledStates(states: Map<String, Boolean>) {
        mutate { preferences ->
            preferences[addonEnabledStatesKey] = gson.toJson(
                states.mapKeys { (url, _) -> canonicalizeUrl(url) }
            )
        }
    }

    private fun getCurrentList(preferences: Preferences): List<String> {
        val json = preferences[orderedUrlsKey]
        return if (json != null) {
            parseUrlList(json)
        } else {
            val legacySet = preferences[legacyUrlsKey] ?: getDefaultAddons()
            legacySet.toList()
        }
    }

    private fun parseUrlList(json: String): List<String> {
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: getDefaultAddons().toList()
        } catch (e: Exception) {
            getDefaultAddons().toList()
        }
    }

    val userSetNames: Flow<Map<String, String>> = installedSettings.map { it.names }.distinctUntilChanged()

    suspend fun setUserSetNames(names: Map<String, String>) {
        mutate { preferences ->
            preferences[userSetNamesKey] = gson.toJson(
                names.mapKeys { (url, _) -> canonicalizeUrl(url) }
            )
        }
    }

    private fun parseNameMap(json: String): Map<String, String> {
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val parsed: Map<String, String> = gson.fromJson(json, type) ?: emptyMap()
            parsed.mapKeys { (url, _) -> canonicalizeUrl(url) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun getCurrentEnabledStates(preferences: Preferences): Map<String, Boolean> {
        val json = preferences[addonEnabledStatesKey] ?: return emptyMap()
        return parseEnabledStateMap(json)
    }

    private fun parseEnabledStateMap(json: String): Map<String, Boolean> {
        return try {
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            val parsed: Map<String, Boolean> = gson.fromJson(json, type) ?: emptyMap()
            parsed.mapKeys { (url, _) -> canonicalizeUrl(url) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun getDefaultAddons(): Set<String> = setOf(
        "https://v3-cinemeta.strem.io",
        "https://opensubtitles-v3.strem.io"
    )
}
