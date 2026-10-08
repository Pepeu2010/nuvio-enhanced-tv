package com.nuvio.tv.core.storage

enum class MediaCachePlatform(val initialBytes: Long, val reserveBytes: Long) {
    DESKTOP(1024L * MIB, 2048L * MIB), TV(256L * MIB, 256L * MIB)
}
enum class MediaCacheMode { AUTO, MANUAL }
enum class MediaCacheCategory(val directoryName: String, val share: Int) {
    IMAGES("images", 50), BADGES("badges", 4), AVATARS("avatars", 6), PREVIEWS("previews", 10),
    THUMBNAILS("thumbnails", 8), FILMSTRIP("filmstrip", 4), METADATA("metadata", 10), EPG("epg", 5), SCENE_INFO("scene-info", 3)
}
const val MIB: Long = 1024L * 1024L
data class MediaCacheSettings(val mode: MediaCacheMode = MediaCacheMode.AUTO, val manualBytes: Long? = null)
data class MediaCacheDeviceSnapshot(val memoryBytes: Long? = null, val usableStorageBytes: Long? = null, val occupiedCacheBytes: Long = 0)
enum class MediaCacheConstraint { DEVICE_MEMORY, STORAGE_RESERVE, MANUAL_LIMIT }
data class MediaCacheBudget(val totalBytes: Long, val requestedBytes: Long, val constraints: Set<MediaCacheConstraint>) {
    fun quota(category: MediaCacheCategory): Long = totalBytes / 100 * category.share
}

/** Defaults are initial estimates, not permanent device ceilings. Durable user data is outside this policy. */
object MediaCachePolicy {
    const val MAX_CONFIGURED_BYTES: Long = 64L * 1024L * MIB
    fun resolve(platform: MediaCachePlatform, settings: MediaCacheSettings, device: MediaCacheDeviceSnapshot): MediaCacheBudget {
        val constraints = mutableSetOf<MediaCacheConstraint>()
        val memory = device.memoryBytes?.takeIf { it > 0 }
        val multiplier = when {
            memory == null -> 1
            platform == MediaCachePlatform.TV && memory <= 1536L * MIB -> -1
            platform == MediaCachePlatform.TV && memory >= 4096L * MIB -> 2
            platform == MediaCachePlatform.DESKTOP && memory <= 4096L * MIB -> -1
            platform == MediaCachePlatform.DESKTOP && memory >= 16384L * MIB -> 2
            else -> 1
        }
        val requested = if (settings.mode == MediaCacheMode.MANUAL) {
            constraints += MediaCacheConstraint.MANUAL_LIMIT
            (settings.manualBytes ?: platform.initialBytes).coerceIn(32L * MIB, MAX_CONFIGURED_BYTES)
        } else {
            if (multiplier != 1) constraints += MediaCacheConstraint.DEVICE_MEMORY
            if (multiplier == -1) platform.initialBytes / 2 else platform.initialBytes * multiplier
        }
        val free = device.usableStorageBytes?.takeIf { it >= 0 }
        val available = free?.let {
            val occupied = device.occupiedCacheBytes.coerceAtLeast(0)
            val combined = if (it > Long.MAX_VALUE - occupied) Long.MAX_VALUE else it + occupied
            (combined - platform.reserveBytes).coerceAtLeast(0)
        }
        val storageCeiling = available?.let { if (settings.mode == MediaCacheMode.AUTO) it / 10 else it }
        // Occupied cache stabilizes the estimate, but cannot grant writes below the real free-space reserve.
        val effective = if (free != null && free <= platform.reserveBytes) 0L
            else storageCeiling?.let { requested.coerceAtMost(it) } ?: requested
        if (effective < requested) constraints += MediaCacheConstraint.STORAGE_RESERVE
        return MediaCacheBudget(effective, requested, constraints)
    }
}
