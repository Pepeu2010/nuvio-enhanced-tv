package com.nuvio.tv.core.storage

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal data class MediaCacheOccupancy(val bytes: Long, val visitedEntries: Int, val complete: Boolean)

/** Read-only, allowlisted measurement. No file contents, durable data or linked directories are traversed. */
internal object OwnedMediaCacheOccupancy {
    fun measure(
        cacheDirectory: Path,
        roots: List<List<String>>,
        maxEntries: Int = 16_384,
        maxDepth: Int = 8,
        maxMillis: Long = 100,
    ): MediaCacheOccupancy {
        require(maxEntries > 0 && maxDepth >= 0 && maxMillis > 0)
        require(roots.all { it.isNotEmpty() && it.all { part ->
            part.isNotBlank() && part != "." && part != ".." && '/' !in part && '\\' !in part
        } })
        val base = try { cacheDirectory.toRealPath() }
            catch (_: NoSuchFileException) { return MediaCacheOccupancy(0, 0, true) }
            catch (_: IOException) { return MediaCacheOccupancy(0, 0, false) }
            catch (_: SecurityException) { return MediaCacheOccupancy(0, 0, false) }
        val started = System.nanoTime()
        var bytes = 0L
        var visited = 0
        var complete = true
        val fileKeys = HashSet<Any>()
        fun canContinue(): Boolean {
            if (visited >= maxEntries || (System.nanoTime() - started) / 1_000_000 >= maxMillis) {
                complete = false
                return false
            }
            return true
        }
        fun visit(path: Path, depth: Int) {
            if (!canContinue()) return
            visited++
            try {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attributes.isSymbolicLink || attributes.isOther) return
                // Also rejects Windows junctions and a parent replaced by a link between reads.
                if (!path.toRealPath().startsWith(base)) return
                if (attributes.isRegularFile) {
                    val key = attributes.fileKey()
                    if (key == null || fileKeys.add(key)) {
                        val size = attributes.size().coerceAtLeast(0)
                        bytes = if (bytes > Long.MAX_VALUE - size) Long.MAX_VALUE else bytes + size
                    }
                } else if (attributes.isDirectory) {
                    if (depth >= maxDepth) { complete = false; return }
                    Files.newDirectoryStream(path).use { children ->
                        val iterator = children.iterator()
                        while (canContinue() && iterator.hasNext()) visit(iterator.next(), depth + 1)
                    }
                }
            } catch (_: IOException) { complete = false }
              catch (_: SecurityException) { complete = false }
              catch (_: java.nio.file.DirectoryIteratorException) { complete = false }
        }
        for (segments in roots.distinct()) {
            if (!canContinue()) break
            var path = base
            var accepted = true
            for ((index, segment) in segments.withIndex()) {
                path = path.resolve(segment)
                try {
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (attributes.isSymbolicLink || attributes.isOther ||
                        (index < segments.lastIndex && !attributes.isDirectory) ||
                        !path.toRealPath().startsWith(base)) { accepted = false; break }
                } catch (_: NoSuchFileException) { accepted = false; break }
                  catch (_: IOException) { complete = false; accepted = false; break }
                  catch (_: SecurityException) { complete = false; accepted = false; break }
            }
            if (accepted) visit(path, 0)
        }
        return MediaCacheOccupancy(bytes, visited, complete)
    }
}
