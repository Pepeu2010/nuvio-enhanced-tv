package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.SnapshotSyncJournal
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionCatalogSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.model.TraktListSort
import com.nuvio.tv.domain.model.TraktSortHow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import androidx.datastore.preferences.core.MutablePreferences
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ValidationResult(
    val valid: Boolean,
    val error: String? = null,
    val collectionCount: Int = 0,
    val folderCount: Int = 0
)

@Singleton
class CollectionsDataStore @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val authManager: AuthManager,
    private val serverConfiguration: ServerConfiguration
) {
    companion object {
        private const val FEATURE = "collections"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()
    private val wireGson = GsonBuilder().serializeNulls().create()
    private val collectionsKey = stringPreferencesKey("collections_json")
    private fun string(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    val collections: Flow<List<Collection>> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs ->
                parseCollections(prefs[collectionsKey])
            }
        }

    suspend fun setCollections(collections: List<Collection>) = mutateCollections { collections }

    suspend fun addCollection(collection: Collection) {
        mutateCollections { current -> current.filterNot { it.id == collection.id } + collection }
    }

    suspend fun updateCollection(collection: Collection) {
        mutateCollections { current -> current.map { if (it.id == collection.id) collection else it } }
    }

    suspend fun removeCollection(collectionId: String) {
        mutateCollections { current -> current.filterNot { it.id == collectionId } }
    }

    internal fun captureSyncOwner(): CollectionSyncOwner? {
        val account = (authManager.authState.value as? AuthState.FullAccount)?.userId ?: return null
        return CollectionSyncOwner(account, profileManager.activeProfileId.value, serverConfiguration.backendUrl)
    }

    internal fun isCurrent(owner: CollectionSyncOwner): Boolean = captureSyncOwner() == owner

    private fun requireCurrent(owner: CollectionSyncOwner) {
        if (!isCurrent(owner)) throw kotlinx.coroutines.CancellationException("Collection sync owner changed")
    }

    private fun journal(prefs: MutablePreferences, owner: CollectionSyncOwner): SnapshotSyncJournal {
        val encodedOwner = JsonArray(listOf(JsonPrimitive(owner.backendUrl), JsonPrimitive(owner.userId), JsonPrimitive(owner.profileId)))
        val digest = MessageDigest.getInstance("SHA-256").digest(encodedOwner.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        val key = stringPreferencesKey("sync_journal_v1_$digest")
        return SnapshotSyncJournal({ prefs[key] }, { prefs[key] = it })
    }

    private fun rawSnapshot(payload: String?): JsonArray = if (payload.isNullOrBlank()) JsonArray(emptyList())
        else Json.parseToJsonElement(payload).jsonArray

    private suspend fun mutateCollections(transform: (List<Collection>) -> List<Collection>) {
        val profileId = profileManager.activeProfileId.value
        val auth = authManager.authState.value
        val owner = captureSyncOwner()
        store(profileId).edit { prefs ->
            if (profileManager.activeProfileId.value != profileId || authManager.authState.value != auth) {
                throw kotlinx.coroutines.CancellationException("Collection mutation owner changed")
            }
            val previous = rawSnapshot(prefs[collectionsKey])
            val encoded = Json.parseToJsonElement(wireGson.toJson(transform(parseCollections(previous.toString())).map { it.toSerializable() })).jsonArray
            val next = CollectionJsonPreserver.merge(previous, encoded)
            if (owner != null && previous != next) journal(prefs, owner).recordLocal(previous, next)
            prefs[collectionsKey] = next.toString()
        }
    }

    /** Snapshot and mutation journal are committed in the same DataStore transaction. */
    internal suspend fun reconcileRemote(owner: CollectionSyncOwner, remote: JsonArray, rowExists: Boolean): CollectionSyncApplication {
        // Never replace a readable local snapshot with malformed domain data.
        remote.forEach { item ->
            val record = item as? JsonObject ?: error("Invalid collection wire item")
            require((record["title"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) { "Invalid collection title" }
            require(record["folders"] is JsonArray) { "Invalid collection folders" }
            (record["folders"] as JsonArray).forEach { folder ->
                val value = folder as? JsonObject ?: error("Invalid collection folder")
                require((value["id"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true &&
                    (value["title"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) { "Invalid collection folder identity" }
            }
        }
        var result: CollectionSyncApplication? = null
        store(owner.profileId).edit { prefs ->
            requireCurrent(owner)
            val previous = rawSnapshot(prefs[collectionsKey])
            val journal = journal(prefs, owner)
            if (!rowExists && journal.pending() == null && previous.isNotEmpty()) journal.recordLocal(JsonArray(emptyList()), previous)
            val plan = journal.plan(remote)
            check(journal.commitPull(plan)) { "Stale collection reconciliation" }
            prefs[collectionsKey] = plan.merged.toString()
            result = CollectionSyncApplication(previous != plan.merged, journal.pending())
        }
        return checkNotNull(result)
    }

    internal suspend fun acknowledgeUpload(owner: CollectionSyncOwner, revision: Long, uploaded: JsonArray) {
        store(owner.profileId).edit { prefs ->
            requireCurrent(owner)
            journal(prefs, owner).acknowledge(revision, uploaded)
        }
    }

    fun generateId(): String = UUID.randomUUID().toString()

    fun exportToJson(collections: List<Collection>): String {
        return gson.toJson(collections.map { it.toSerializable() })
    }

    fun importFromJson(json: String): List<Collection> {
        return parseCollections(json)
    }

    suspend fun getCurrentCollections(): List<Collection> {
        val prefs = store().data.first()
        return parseCollections(prefs[collectionsKey])
    }

    suspend fun exportCurrentProfileJson(): String? {
        val prefs = store().data.first()
        return prefs[collectionsKey]
    }

    fun validateCollectionsJson(json: String): ValidationResult {
        if (json.isBlank()) return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_empty_input))
        return try {
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val parsed = gson.fromJson<List<Map<String, Any?>>>(json, type)
                ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_expected_array))
            if (parsed.isEmpty()) return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_empty_array))

            var folderCount = 0
            val validShapes = setOf("POSTER", "LANDSCAPE", "SQUARE", "poster", "wide", "square")

            for ((i, item) in parsed.withIndex()) {
                val id = item["id"] as? String
                if (id.isNullOrBlank()) return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_missing_id, i + 1))
                val title = item["title"] as? String
                    ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_missing_title, id))
                val folders = item["folders"] as? List<*>
                    ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_folders_array, title))

                for ((j, f) in folders.withIndex()) {
                    val folder = f as? Map<*, *>
                        ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_folder_invalid, title, j + 1))
                    val folderId = folder["id"] as? String
                    if (folderId.isNullOrBlank()) return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_folder_missing_id, title, j + 1))
                    val folderTitle = folder["title"] as? String
                        ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_folder_missing_title, title, folderId))
                    val sources = (folder["sources"] as? List<*>) ?: (folder["catalogSources"] as? List<*>)
                        ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_sources_array, title, folderTitle))
                    val shape = folder["tileShape"] as? String
                    if (shape != null && shape !in validShapes) {
                        return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_invalid_tile_shape, title, folderTitle, shape))
                    }
                    for ((k, s) in sources.withIndex()) {
                        val source = s as? Map<*, *>
                            ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_source_invalid, title, folderTitle, k + 1))
                        val provider = (source["provider"] as? String)?.lowercase()
                        val isAddonSource = provider == null || provider == "addon"
                        val isTmdbSource = provider == "tmdb"
                        val isTraktSource = provider == "trakt"
                        if (isAddonSource && (source["addonId"] !is String || source["type"] !is String || source["catalogId"] !is String)) {
                            return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_source_required_fields, title, folderTitle, k + 1))
                        }
                        if (isTmdbSource && source["tmdbSourceType"] !is String) {
                            return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_missing_tmdb_type, title, folderTitle, k + 1))
                        }
                        if (isTraktSource && (source["traktListId"] as? Number)?.toLong() == null) {
                            return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_missing_trakt_list_id, title, folderTitle, k + 1))
                        }
                    }
                    folderCount++
                }
            }
            ValidationResult(true, collectionCount = parsed.size, folderCount = folderCount)
        } catch (e: Exception) {
            ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_json_parse, e.message.orEmpty()))
        }
    }

    private fun parseCollections(json: String?): List<Collection> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<SerializableCollection>>() {}.type
            val parsed = gson.fromJson<List<SerializableCollection>>(json, type).orEmpty()
            // Deduplicate by ID at parse level to prevent duplicate LazyColumn keys
            // even if stored data contains duplicates (e.g. from sync or corrupted state).
            parsed.map { it.toDomain() }
                .associateBy { it.id }
                .values.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @androidx.annotation.Keep
    private data class SerializableCollection(
        val id: String,
        val title: String,
        val backdropImageUrl: String? = null,
        val pinToTop: Boolean = false,
        val focusGlowEnabled: Boolean? = null,
        val viewMode: String = "TABBED_GRID",
        val showAllTab: Boolean = true,
        val folders: List<SerializableFolder> = emptyList()
    )

    @androidx.annotation.Keep
    private data class SerializableFolder(
        val id: String,
        val title: String,
        val coverImageUrl: String? = null,
        val focusGifUrl: String? = null,
        val focusGifEnabled: Boolean? = null,
        val coverEmoji: String? = null,
        val tileShape: String = "SQUARE",
        val hideTitle: Boolean = false,
        val sources: List<SerializableSource>? = null,
        val catalogSources: List<SerializableCatalogSource> = emptyList(),
        val heroBackdropUrl: String? = null,
        val heroVideoUrl: String? = null,
        val titleLogoUrl: String? = null
    )

    @androidx.annotation.Keep
    private data class SerializableSource(
        val provider: String = "addon",
        val addonId: String? = null,
        val type: String? = null,
        val catalogId: String? = null,
        val genre: String? = null,
        val tmdbSourceType: String? = null,
        val title: String? = null,
        val tmdbId: Int? = null,
        val traktListId: Long? = null,
        val mediaType: String? = null,
        val sortBy: String? = null,
        val sortHow: String? = null,
        val filters: SerializableTmdbFilters? = null
    )

    @androidx.annotation.Keep
    private data class SerializableTmdbFilters(
        val withGenres: String? = null,
        val withoutGenres: String? = null,
        val releaseDateGte: String? = null,
        val releaseDateLte: String? = null,
        val voteAverageGte: Double? = null,
        val voteAverageLte: Double? = null,
        val voteCountGte: Int? = null,
        val withOriginalLanguage: String? = null,
        val withOriginCountry: String? = null,
        val withKeywords: String? = null,
        val withoutKeywords: String? = null,
        val withCompanies: String? = null,
        val withoutCompanies: String? = null,
        val withNetworks: String? = null,
        val year: Int? = null,
        val watchRegion: String? = null,
        val withWatchProviders: String? = null,
        val withoutWatchProviders: String? = null
    )

    @androidx.annotation.Keep
    private data class SerializableCatalogSource(
        val addonId: String,
        val type: String,
        val catalogId: String,
        val genre: String? = null
    )

    private fun Collection.toSerializable() = SerializableCollection(
        id = id,
        title = title,
        backdropImageUrl = backdropImageUrl,
        pinToTop = pinToTop,
        focusGlowEnabled = focusGlowEnabled,
        viewMode = viewMode.name,
        showAllTab = showAllTab,
        folders = folders.map { folder ->
            SerializableFolder(
                id = folder.id,
                title = folder.title,
                coverImageUrl = folder.coverImageUrl,
                focusGifUrl = folder.focusGifUrl,
                focusGifEnabled = folder.focusGifEnabled,
                coverEmoji = folder.coverEmoji,
                tileShape = folder.tileShape.name,
                hideTitle = folder.hideTitle,
                heroBackdropUrl = folder.heroBackdropUrl,
                heroVideoUrl = folder.heroVideoUrl,
                titleLogoUrl = folder.titleLogoUrl,
                sources = folder.sources.map { it.toSerializableSource() },
                catalogSources = folder.catalogSources.map { source ->
                    SerializableCatalogSource(
                        addonId = source.addonId,
                        type = source.type,
                        catalogId = source.catalogId,
                        genre = source.genre
                    )
                }
            )
        }
    )

    private fun CollectionSource.toSerializableSource(): SerializableSource {
        return when (this) {
            is AddonCatalogCollectionSource -> SerializableSource(
                provider = "addon",
                addonId = addonId,
                type = type,
                catalogId = catalogId,
                genre = genre
            )
            is TmdbCollectionSource -> SerializableSource(
                provider = "tmdb",
                tmdbSourceType = sourceType.name,
                title = title,
                tmdbId = tmdbId,
                mediaType = mediaType.name,
                sortBy = sortBy,
                filters = filters.toSerializable()
            )
            is TraktCollectionSource -> SerializableSource(
                provider = "trakt",
                title = title,
                traktListId = traktListId,
                mediaType = mediaType.name,
                sortBy = sortBy,
                sortHow = sortHow
            )
        }
    }

    private fun TmdbCollectionFilters.toSerializable() = SerializableTmdbFilters(
        withGenres = withGenres,
        withoutGenres = withoutGenres,
        releaseDateGte = releaseDateGte,
        releaseDateLte = releaseDateLte,
        voteAverageGte = voteAverageGte,
        voteAverageLte = voteAverageLte,
        voteCountGte = voteCountGte,
        withOriginalLanguage = withOriginalLanguage,
        withOriginCountry = withOriginCountry,
        withKeywords = withKeywords,
        withoutKeywords = withoutKeywords,
        withCompanies = withCompanies,
        withoutCompanies = withoutCompanies,
        withNetworks = withNetworks,
        year = year,
        watchRegion = watchRegion,
        withWatchProviders = withWatchProviders,
        withoutWatchProviders = withoutWatchProviders
    )

    private fun SerializableCollection.toDomain() = Collection(
        id = id,
        title = title,
        backdropImageUrl = backdropImageUrl,
        pinToTop = pinToTop,
        focusGlowEnabled = focusGlowEnabled ?: true,
        viewMode = FolderViewMode.fromString(viewMode),
        showAllTab = showAllTab,
        folders = folders.map { folder ->
            CollectionFolder(
                id = folder.id,
                title = folder.title,
                coverImageUrl = folder.coverImageUrl,
                focusGifUrl = folder.focusGifUrl,
                focusGifEnabled = folder.focusGifEnabled ?: true,
                coverEmoji = folder.coverEmoji,
                tileShape = PosterShape.fromString(folder.tileShape),
                hideTitle = folder.hideTitle,
                heroBackdropUrl = folder.heroBackdropUrl,
                heroVideoUrl = folder.heroVideoUrl,
                titleLogoUrl = folder.titleLogoUrl,
                sources = folder.sources?.mapNotNull { it.toDomainSource() }
                    ?: folder.catalogSources.map { source ->
                        AddonCatalogCollectionSource(
                            addonId = source.addonId,
                            type = source.type,
                            catalogId = source.catalogId,
                            genre = source.genre
                        )
                    }
            )
        }
    )

    private fun SerializableSource.toDomainSource(): CollectionSource? {
        return when (provider.lowercase()) {
            "tmdb" -> {
                val type = tmdbSourceType?.let { raw ->
                    runCatching { TmdbCollectionSourceType.valueOf(raw.uppercase()) }.getOrNull()
                } ?: return null
                val sourceSortBy = sortBy?.takeIf { it.isNotBlank() } ?: TmdbCollectionSort.POPULAR_DESC.value
                val normalizedSortBy = if (
                    type in setOf(TmdbCollectionSourceType.LIST, TmdbCollectionSourceType.COLLECTION) &&
                    sourceSortBy == TmdbCollectionSort.POPULAR_DESC.value
                ) {
                    TmdbCollectionSort.ORIGINAL.value
                } else {
                    sourceSortBy
                }
                TmdbCollectionSource(
                    sourceType = type,
                    title = title?.takeIf { it.isNotBlank() } ?: type.name.lowercase().replaceFirstChar { it.uppercase() },
                    tmdbId = tmdbId,
                    mediaType = mediaType?.let { raw ->
                        runCatching { TmdbCollectionMediaType.valueOf(raw.uppercase()) }.getOrNull()
                    } ?: TmdbCollectionMediaType.MOVIE,
                    sortBy = normalizedSortBy,
                    filters = filters?.toDomain() ?: TmdbCollectionFilters()
                )
            }
            "trakt" -> {
                val id = traktListId?.takeIf { it > 0L } ?: return null
                TraktCollectionSource(
                    title = title?.takeIf { it.isNotBlank() }
                        ?: string(R.string.collections_editor_trakt_list_with_id, id),
                    traktListId = id,
                    mediaType = mediaType?.let { raw ->
                        runCatching { TmdbCollectionMediaType.valueOf(raw.uppercase()) }.getOrNull()
                    } ?: TmdbCollectionMediaType.MOVIE,
                    sortBy = TraktListSort.normalize(sortBy),
                    sortHow = TraktSortHow.normalize(sortHow)
                )
            }
            else -> {
                val sourceAddonId = addonId?.takeIf { it.isNotBlank() } ?: return null
                val sourceType = type?.takeIf { it.isNotBlank() } ?: return null
                val sourceCatalogId = catalogId?.takeIf { it.isNotBlank() } ?: return null
                AddonCatalogCollectionSource(
                    addonId = sourceAddonId,
                    type = sourceType,
                    catalogId = sourceCatalogId,
                    genre = genre
                )
            }
        }
    }

    private fun SerializableTmdbFilters.toDomain() = TmdbCollectionFilters(
        withGenres = withGenres,
        withoutGenres = withoutGenres,
        releaseDateGte = releaseDateGte,
        releaseDateLte = releaseDateLte,
        voteAverageGte = voteAverageGte,
        voteAverageLte = voteAverageLte,
        voteCountGte = voteCountGte,
        withOriginalLanguage = withOriginalLanguage,
        withOriginCountry = withOriginCountry,
        withKeywords = withKeywords,
        withoutKeywords = withoutKeywords,
        withCompanies = withCompanies,
        withoutCompanies = withoutCompanies,
        withNetworks = withNetworks,
        year = year,
        watchRegion = watchRegion,
        withWatchProviders = withWatchProviders,
        withoutWatchProviders = withoutWatchProviders
    )
}
