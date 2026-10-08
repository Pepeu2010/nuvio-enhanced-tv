package com.nuvio.tv.core.storage

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class MediaCachePolicyTest {
    @Test fun unknownDevicesUseInitialDefaultsAndKnownCapabilitiesAdaptAuto() {
        assertEquals(1024L * MIB, MediaCachePolicy.resolve(MediaCachePlatform.DESKTOP, MediaCacheSettings(), MediaCacheDeviceSnapshot()).totalBytes)
        assertEquals(256L * MIB, MediaCachePolicy.resolve(MediaCachePlatform.TV, MediaCacheSettings(), MediaCacheDeviceSnapshot()).totalBytes)
        assertEquals(2048L * MIB, MediaCachePolicy.resolve(MediaCachePlatform.DESKTOP, MediaCacheSettings(), MediaCacheDeviceSnapshot(memoryBytes = 16384L * MIB)).totalBytes)
        assertEquals(128L * MIB, MediaCachePolicy.resolve(MediaCachePlatform.TV, MediaCacheSettings(), MediaCacheDeviceSnapshot(memoryBytes = 1024L * MIB)).totalBytes)
    }

    @Test fun manualLimitsCanExceedTheInitialDefaultsButRespectStorageReserve() {
        val settings = MediaCacheSettings(MediaCacheMode.MANUAL, 4096L * MIB)
        val budget = MediaCachePolicy.resolve(MediaCachePlatform.DESKTOP, settings, MediaCacheDeviceSnapshot(usableStorageBytes = 8192L * MIB))
        assertEquals(4096L * MIB, budget.totalBytes)
        val low = MediaCachePolicy.resolve(MediaCachePlatform.TV, settings, MediaCacheDeviceSnapshot(usableStorageBytes = 300L * MIB))
        assertEquals(44L * MIB, low.totalBytes)
        assertTrue(MediaCacheConstraint.STORAGE_RESERVE in low.constraints)
    }

    @Test fun existingCacheDoesNotCauseAutoQuotaToCollapseAfterEachWrite() {
        val before = MediaCachePolicy.resolve(MediaCachePlatform.TV, MediaCacheSettings(), MediaCacheDeviceSnapshot(usableStorageBytes = 1024L * MIB))
        val after = MediaCachePolicy.resolve(MediaCachePlatform.TV, MediaCacheSettings(), MediaCacheDeviceSnapshot(usableStorageBytes = 924L * MIB, occupiedCacheBytes = 100L * MIB))
        assertEquals(before.totalBytes, after.totalBytes)
        assertEquals(0L, MediaCachePolicy.resolve(MediaCachePlatform.TV, MediaCacheSettings(), MediaCacheDeviceSnapshot(usableStorageBytes = 0)).totalBytes)
    }

    @Test fun occupiedCacheNeverOverridesTheRealFreeSpaceReserve() {
        for (platform in MediaCachePlatform.entries) for (mode in MediaCacheMode.entries) {
            for (free in listOf(0L, platform.reserveBytes - 1, platform.reserveBytes)) {
                val budget = MediaCachePolicy.resolve(platform, MediaCacheSettings(mode, 4096L * MIB),
                    MediaCacheDeviceSnapshot(usableStorageBytes = free, occupiedCacheBytes = Long.MAX_VALUE))
                assertEquals(0L, budget.totalBytes)
                assertTrue(MediaCacheConstraint.STORAGE_RESERVE in budget.constraints)
                assertTrue(budget.requestedBytes > 0)
            }
        }
    }

    @Test fun malformedSettingsAndExtremeTelemetryCannotOverflowOrOverallocate() {
        val budget = MediaCachePolicy.resolve(MediaCachePlatform.DESKTOP, MediaCacheSettings(MediaCacheMode.MANUAL, Long.MAX_VALUE),
            MediaCacheDeviceSnapshot(usableStorageBytes = Long.MAX_VALUE, occupiedCacheBytes = Long.MAX_VALUE))
        assertEquals(MediaCachePolicy.MAX_CONFIGURED_BYTES, budget.totalBytes)
        assertTrue(MediaCacheCategory.entries.sumOf { budget.quota(it) } <= budget.totalBytes)
        assertEquals(32L * MIB, MediaCachePolicy.resolve(MediaCachePlatform.TV, MediaCacheSettings(MediaCacheMode.MANUAL, -1), MediaCacheDeviceSnapshot()).totalBytes)
    }
}
