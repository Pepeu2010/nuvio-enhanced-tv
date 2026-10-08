package com.nuvio.tv.core.player.metadata

import com.nuvio.tv.core.profile.studio.ProfileAvatarScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.UUID

/** An unknown cut stays tied to its source; never apply its timestamps to another cut. */
internal data class SceneBookmarkScope(
    val owner: ProfileAvatarScope,
    val mediaId: String,
    val mediaType: String,
    val videoId: String,
    val editionKey: String,
) {
    init {
        require(listOf(mediaId, mediaType, videoId).all { it.isNotBlank() && it.length <= 1024 })
        require(Regex("[a-f0-9]{64}").matches(editionKey))
    }
    companion object {
        fun sourceEdition(source: String): String = bookmarkDigest(source)
    }
}

@Serializable
internal data class SceneBookmark(
    val id: String,
    val mediaId: String,
    val mediaType: String,
    val videoId: String,
    val editionKey: String,
    val positionMs: Long,
    val name: String,
    val createdAtMs: Long,
)

@Serializable
private data class BookmarkDocument(val schemaVersion: Int = 1, val items: List<SceneBookmark>)

/** Durable local user data, outside media cache. Existing profile ownership rules are reused. */
internal class SceneBookmarkStore(
    private val root: Path,
    private val publish: (Path, Path) -> Unit = { source, destination ->
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING) }
    },
) {
    companion object { const val MAX_BOOKMARKS = 256; const val MAX_BYTES = 512 * 1024 }
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized fun load(scope: SceneBookmarkScope): List<SceneBookmark> {
        val file = file(scope, false) ?: return emptyList()
        if (!Files.exists(file, NOFOLLOW_LINKS)) return emptyList()
        check(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) in 1..MAX_BYTES.toLong())
        val document = json.decodeFromString<BookmarkDocument>(Files.readString(file))
        check(document.schemaVersion == 1) { "Newer bookmark schema" }
        check(document.items.size <= MAX_BOOKMARKS && document.items.map { it.id }.toSet().size == document.items.size)
        check(document.items.all { valid(it, scope) }) { "Invalid bookmark data" }
        return document.items.sortedWith(compareBy({ it.positionMs }, { it.createdAtMs }))
    }

    @Synchronized fun save(scope: SceneBookmarkScope, positionMs: Long, durationMs: Long, name: String,
                           nowMs: Long = System.currentTimeMillis()): List<SceneBookmark> {
        require(durationMs > 0 && positionMs in 0 until durationMs && nowMs > 0)
        val label = name.trim()
        require(label.length in 1..128 && label.none(Char::isISOControl))
        val previous = load(scope) // Unknown/malformed data is preserved; never overwrite it with an empty list.
        check(previous.size < MAX_BOOKMARKS) { "Bookmark capacity reached" }
        val item = SceneBookmark(UUID.randomUUID().toString(), scope.mediaId, scope.mediaType, scope.videoId,
            scope.editionKey, positionMs, label, nowMs)
        write(scope, previous + item)
        return load(scope)
    }

    @Synchronized fun rename(scope: SceneBookmarkScope, id: String, name: String): List<SceneBookmark> {
        val label = name.trim()
        require(label.length in 1..128 && label.none(Char::isISOControl))
        val previous = load(scope)
        check(previous.any { it.id == id })
        write(scope, previous.map { if (it.id == id) it.copy(name = label) else it })
        return load(scope)
    }

    @Synchronized fun remove(scope: SceneBookmarkScope, id: String): List<SceneBookmark> {
        val previous = load(scope)
        val next = previous.filterNot { it.id == id }
        if (next.size != previous.size) write(scope, next)
        return next
    }

    private fun valid(item: SceneBookmark, scope: SceneBookmarkScope): Boolean =
        runCatching { UUID.fromString(item.id).toString() == item.id }.getOrDefault(false) &&
            item.mediaId == scope.mediaId && item.mediaType == scope.mediaType && item.videoId == scope.videoId &&
            item.editionKey == scope.editionKey && item.positionMs >= 0 && item.positionMs < Long.MAX_VALUE &&
            item.createdAtMs > 0 && item.name.length in 1..128 && item.name.isNotBlank() && item.name.none(Char::isISOControl)

    private fun file(scope: SceneBookmarkScope, create: Boolean): Path? {
        if (create) Files.createDirectories(root)
        if (!Files.exists(root, NOFOLLOW_LINKS)) return null
        check(Files.isDirectory(root, NOFOLLOW_LINKS))
        val ownerKey = json.encodeToString(listOf(scope.owner.ownerId, scope.owner.profileIndex.toString(), scope.owner.identity))
        val directory = root.resolve(bookmarkDigest(ownerKey))
        if (Files.exists(directory, NOFOLLOW_LINKS)) check(Files.isDirectory(directory, NOFOLLOW_LINKS))
        if (create) Files.createDirectories(directory)
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return null
        check(directory.toRealPath().startsWith(root.toRealPath()))
        val key = json.encodeToString(listOf(scope.mediaType, scope.mediaId, scope.videoId, scope.editionKey))
        return directory.resolve("${bookmarkDigest(key)}.json")
    }

    private fun write(scope: SceneBookmarkScope, items: List<SceneBookmark>) {
        val file = requireNotNull(file(scope, true))
        if (Files.exists(file, NOFOLLOW_LINKS)) check(Files.isRegularFile(file, NOFOLLOW_LINKS))
        val bytes = json.encodeToString(BookmarkDocument(items = items)).toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES)
        val pending = Files.createTempFile(file.parent, "bookmark-", ".part")
        try { Files.write(pending, bytes); publish(pending, file) }
        finally { Files.deleteIfExists(pending) }
    }
}

internal fun bookmarkDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun List<SceneBookmark>.toBookmarkMarkers(durationMs: Long): List<PlayerTimedMarker> {
    if (durationMs <= 0) return emptyList()
    return filter { it.positionMs < durationMs }.map {
        PlayerTimedMarker("local-bookmark:${it.id}", TimedMetadataKind.BOOKMARK,
            (it.positionMs.toDouble() / durationMs).toFloat(), null, it.name, "local-user")
    }
}
