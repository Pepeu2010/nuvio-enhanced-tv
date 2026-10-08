package com.nuvio.tv.core.player.metadata

import com.nuvio.tv.core.profile.studio.ProfileAvatarScope
import java.nio.file.Files
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SceneBookmarkStoreTest {
    private val scope = SceneBookmarkScope(ProfileAvatarScope("account-A", 2, "profile-stable-A"), "tt12879200", "series", "tt12879200:1:2",
        SceneBookmarkScope.sourceEdition("https://provider.invalid/br-78?token=private"))
    private fun sandbox(block: (java.nio.file.Path, SceneBookmarkStore) -> Unit) {
        val root = Files.createTempDirectory("telumia-bookmarks")
        try { block(root, SceneBookmarkStore(root)) } finally { root.toFile().deleteRecursively() }
    }
    @Test fun persistsExactMomentsAcrossRecreationWithoutStoringSourceTokens() = sandbox { root, store ->
        store.save(scope, 32_180, 100_000, "Cena favorita", 1234)
        val later = store.save(scope, 72_430, 100_000, "Rever depois", 5678)
        assertEquals(later, SceneBookmarkStore(root).load(scope))
        assertEquals(listOf(32_180L,72_430L), later.map { it.positionMs })
        assertEquals(listOf(1234L,5678L), later.map { it.createdAtMs })
        Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach {
            assertFalse(Files.readString(it).contains("private")); assertFalse(Files.readString(it).contains("provider.invalid"))
        } }
    }
    @Test fun separatesAccountsProfileGenerationsEpisodesAndCuts() = sandbox { _, store ->
        store.save(scope, 12_000, 100_000, "Meu momento", 1234)
        for (other in listOf(scope.copy(owner = scope.owner.copy(ownerId = "account-B")),
            scope.copy(owner = scope.owner.copy(profileIndex = 3)), scope.copy(owner = scope.owner.copy(identity = "recreated")),
            scope.copy(videoId = "tt12879200:1:3"), scope.copy(editionKey = SceneBookmarkScope.sourceEdition("original-31")))) {
            assertTrue(store.load(other).isEmpty())
        }
        assertEquals(1, store.load(scope).size)
    }
    @Test fun renamesAndRemovesOnlyTheSelectedId() = sandbox { _, store ->
        val first = store.save(scope, 12_000, 100_000, "Uma cena", 1234).first()
        val all = store.save(scope, 50_000, 100_000, "Outra cena", 5678)
        val renamed = store.rename(scope, first.id, "Minha cena")
        assertEquals(first.copy(name = "Minha cena"), renamed.first())
        assertEquals(all.last(), renamed.last())
        assertEquals(listOf(all.last()), store.remove(scope, first.id))
    }
    @Test fun invalidPositionsAndLabelsCannotReplaceExistingData() = sandbox { _, store ->
        val existing = store.save(scope, 12_000, 100_000, "Uma cena", 1234)
        for ((position,duration,name) in listOf(Triple(-1L,100_000L,"ok"),Triple(100_000L,100_000L,"ok"),
            Triple(0L,0L,"ok"),Triple(0L,100_000L," "),Triple(0L,100_000L,"bad\nlabel"),Triple(0L,100_000L,"a".repeat(129)))) {
            try { store.save(scope,position,duration,name); fail("Invalid input accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(existing,store.load(scope))
    }
    @Test fun futureAndMalformedDocumentsAreNeverOverwritten() = sandbox { root,store ->
        store.save(scope,12_000,100_000,"Uma cena",1234)
        val file = Files.walk(root).use { it.filter { Files.isRegularFile(it) }.findFirst().get() }
        for (payload in listOf("{\"schemaVersion\":99,\"items\":[]}", "{bad")) {
            Files.writeString(file,payload)
            try { store.save(scope,20_000,100_000,"Outra"); fail("Unsupported document changed") } catch (_: Exception) { }
            assertEquals(payload,Files.readString(file))
        }
    }
    @Test fun failedAtomicPublicationPreservesPreviousDataAndCleansTemporaryFile() = sandbox { root,store ->
        val existing=store.save(scope,12_000,100_000,"Uma cena",1234)
        val failing=SceneBookmarkStore(root) { _,_ -> throw IOException("publication fixture") }
        try { failing.save(scope,20_000,100_000,"Outra");fail("Failed publish accepted") } catch (_:IOException) { }
        assertEquals(existing,store.load(scope))
        Files.walk(root).use { assertEquals(1L,it.filter { Files.isRegularFile(it) }.count()) }
    }
    @Test fun capacityRefusesNewDataWithoutEvictingDurableMoments() = sandbox { _,store ->
        repeat(SceneBookmarkStore.MAX_BOOKMARKS) { store.save(scope,it.toLong(),100_000,"Cena $it",1234+it.toLong()) }
        val existing=store.load(scope)
        try { store.save(scope,50_000,100_000,"Extra");fail("Unbounded list accepted") } catch (_:IllegalStateException) { }
        assertEquals(existing,store.load(scope))
    }
    @Test fun idsCannotBecomePathsAndOccupiedOwnerDirectoryIsRefused() = sandbox { root,store ->
        val hostile=scope.copy(owner=scope.owner.copy(ownerId="../../elsewhere",identity="/private"),mediaId="../../media")
        store.save(hostile,1000,2000,"Seguro",1234)
        assertEquals(1,store.load(hostile).size)
        val directory=Files.list(root).use { it.findFirst().get() }
        directory.toFile().deleteRecursively();Files.writeString(directory,"must stay")
        try { store.save(hostile,1200,2000,"Outra");fail("Unsafe directory accepted") } catch (_:IllegalStateException) { }
        assertEquals("must stay",Files.readString(directory))
    }
    @Test fun projectsRealTimestampIntoExistingTimelineAndOmitsOutOfDurationPoints() = sandbox { _,store ->
        val items=store.save(scope,25_000,100_000,"Rever",1234)
        val marker=items.toBookmarkMarkers(100_000).single()
        assertEquals(.25f,marker.startFraction,0f);assertEquals(TimedMetadataKind.BOOKMARK,marker.kind)
        assertEquals("Rever",marker.label);assertNull(marker.endFraction)
        assertTrue(items.toBookmarkMarkers(20_000).isEmpty());assertTrue(items.toBookmarkMarkers(0).isEmpty())
    }
}

