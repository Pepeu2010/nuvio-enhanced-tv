package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.metadata.SceneBookmark
import com.nuvio.tv.core.player.metadata.SceneBookmarkRepository
import com.nuvio.tv.core.player.metadata.SceneBookmarkScope
import com.nuvio.tv.core.player.metadata.toBookmarkMarkers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class SceneBookmarkPanelState(
    val scope: SceneBookmarkScope? = null,
    val items: List<SceneBookmark> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

/** Uses the existing player's position and seek action; does not create another player. */
internal class PlayerSceneBookmarks(
    private val repository: SceneBookmarkRepository,
    private val player: StateFlow<PlayerUiState>,
    private val timeline: StateFlow<PlaybackTimelineState>,
    private val profileId: Int,
    private val mediaId: String?,
    private val mediaType: String?,
    private val coroutineScope: CoroutineScope,
    private val seek: (Long) -> Unit,
) {
    private val mutable = MutableStateFlow(SceneBookmarkPanelState())
    val state = mutable.asStateFlow()
    init {
        coroutineScope.launch {
            combine(repository.owners(profileId), player.map {
                Triple(it.currentVideoId ?: mediaId, it.currentStreamUrl, it.currentStreamInfoHash)
            }.distinctUntilChanged(), repository.revision) { owner, media, _ ->
                val source = media.second?.takeIf(String::isNotBlank)
                    ?: media.third?.takeIf(String::isNotBlank)?.let { "torrent:$it" }
                if (owner == null || source == null || mediaId.isNullOrBlank() || mediaType.isNullOrBlank() || media.first.isNullOrBlank()) null
                else runCatching { SceneBookmarkScope(owner, mediaId, mediaType, media.first!!,
                    SceneBookmarkScope.sourceEdition(source)) }.getOrNull()
            }.collectLatest { scope ->
                mutable.value = SceneBookmarkPanelState(scope = scope, loading = scope != null)
                if (scope != null) {
                    try {
                        val items = repository.load(scope)
                        if (repository.eligible(scope)) mutable.value = SceneBookmarkPanelState(scope, items, loading = false)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutable.value = SceneBookmarkPanelState(scope, loading = false, failed = true) }
                }
            }
        }
    }

    fun save(name: String) {
        val point = timeline.value
        if (point.isLive || point.duration <= 0 || point.currentPosition !in 0 until point.duration) return
        mutate { repository.save(it, point.currentPosition, point.duration, name) }
    }
    fun rename(id: String, name: String) = mutate { repository.rename(it, id, name) }
    fun remove(id: String) = mutate { repository.remove(it, id) }
    fun retry() { repository.revision.value += 1 }
    fun markers(durationMs: Long) = state.value.takeIf { it.scope?.let(::current) == true }
        ?.items?.toBookmarkMarkers(durationMs).orEmpty()
    fun jump(id: String): Boolean {
        val snapshot = state.value
        val scope = snapshot.scope ?: return false
        val point = timeline.value
        val item = snapshot.items.firstOrNull { it.id == id } ?: return false
        if (!current(scope) || point.isLive || item.positionMs !in 0 until point.duration) return false
        seek(item.positionMs)
        return true
    }
    private fun mutate(action: suspend (SceneBookmarkScope) -> List<SceneBookmark>) {
        val snapshot = state.value
        val scope = snapshot.scope ?: return
        if (snapshot.loading || snapshot.busy || snapshot.failed || !current(scope)) return
        mutable.value = snapshot.copy(busy = true)
        coroutineScope.launch {
            try {
                val items = action(scope)
                if (mutable.value.scope == scope && current(scope)) mutable.value = SceneBookmarkPanelState(scope, items, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (mutable.value.scope == scope) mutable.value = snapshot.copy(busy = false, failed = true)
            }
        }
    }

    private fun current(scope: SceneBookmarkScope): Boolean {
        val value = player.value
        val source = value.currentStreamUrl?.takeIf(String::isNotBlank)
            ?: value.currentStreamInfoHash?.takeIf(String::isNotBlank)?.let { "torrent:$it" }
        return repository.eligible(scope) && (value.currentVideoId ?: mediaId) == scope.videoId &&
            source != null && SceneBookmarkScope.sourceEdition(source) == scope.editionKey
    }
}
