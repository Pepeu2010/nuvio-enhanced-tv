package com.nuvio.tv.core.profile.studio

import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.UserProfile
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class OwnedProfileAvatarStoreTest {
    private fun sandbox(block:(File,OwnedProfileAvatarStore)->Unit) {
        val root=Files.createTempDirectory("telumia-avatar-test").toFile()
        try { block(root,OwnedProfileAvatarStore(root) { source,destination ->
            Files.move(source.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }) } finally { root.deleteRecursively() }
    }
    private fun variants(seed:Int)=AvatarImagePolicy.variantSizes.associateWith { byteArrayOf(seed.toByte(),it.toByte()) }
    private val scope=ProfileAvatarScope("account-test",2,"local-new")

    @Test fun failedManifestPublicationKeepsThePreviousSelectionAndRemovesNewPhotoVariants() = sandbox { root,store ->
        val old=store.savePhoto(scope,variants(1))
        val failing=OwnedProfileAvatarStore(root) { source,destination ->
            if(destination.name=="avatar.json")throw java.io.IOException("fixture publication failure")
            Files.move(source.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        try { failing.savePhoto(scope,variants(2));fail("Failed manifest accepted") } catch(_:java.io.IOException) { }
        assertEquals(old,store.load(scope))
        assertEquals(4,old.file.parentFile!!.listFiles()!!.count { it.extension=="png" })
    }

    @Test fun photoSurvivesRecreationAndOtherOwnersAndProfileGenerationsCannotReadIt() = sandbox { root,store ->
        val saved=store.savePhoto(scope,variants(1))
        assertEquals(saved,OwnedProfileAvatarStore(root).load(scope))
        assertEquals(4,saved.file.parentFile!!.listFiles()!!.count { it.extension=="png" })
        assertNull(store.load(scope.copy(ownerId="other")))
        assertNull(store.load(scope.copy(profileIndex=3)))
        assertNull(store.load(scope.copy(identity="recreated")))
    }
    @Test fun replacingAndResettingOnlyRemovesOwnedVariantsAndRejectsArbitraryBundlePaths() = sandbox { _,store ->
        val old=store.savePhoto(scope,variants(1))
        val unrelated=File(old.file.parentFile,"leave-me.txt").apply { writeText("untouched") }
        store.saveBundled(scope,"openmoji-1f98a",setOf("openmoji-1f98a"))
        assertFalse(old.file.exists());assertTrue(unrelated.exists())
        for(id in listOf("../private","file:///image","openmoji-ffff")) {
            try { store.saveBundled(scope,id,setOf("openmoji-1f98a"));fail("Unsafe bundle accepted") } catch(_:IllegalArgumentException) { }
        }
        store.clear(scope);assertNull(store.load(scope));assertTrue(unrelated.exists())
    }
    @Test fun missingVariantsAreRefusedBeforeTheExistingManifestIsChanged() = sandbox { _,store ->
        val old=store.savePhoto(scope,variants(1))
        try { store.savePhoto(scope,mapOf(512 to byteArrayOf(9)));fail("Partial variants accepted") } catch(_:IllegalArgumentException) { }
        assertEquals(old,store.load(scope))
        File(old.file.parentFile,"avatar.json").writeText("{\"schemaVersion\":999,\"kind\":\"future\"}")
        assertNull(store.load(scope));assertTrue(File(old.file.parentFile,"avatar.json").readText().contains("999"))
    }
    @Test fun firstRemoteIdentityAdoptsTheSelectionWithoutOverwritingAnExistingDestination() = sandbox { _,store ->
        val photo=store.savePhoto(scope,variants(1))
        val remote=scope.copy(identity="remote-uuid")
        store.adoptIdentity(scope,remote)
        assertArrayEquals(photo.file.readBytes(),(store.load(remote) as LocalProfileAvatar.Photo).file.readBytes())
        assertEquals(photo,store.load(scope)) // protects a failed preferences commit
        store.saveBundled(remote,"openmoji-1f98a",setOf("openmoji-1f98a"))
        store.adoptIdentity(scope,remote)
        assertEquals(LocalProfileAvatar.Bundled("openmoji-1f98a"),store.load(remote))
    }
    @Test fun futureManifestSurvivesResetAndBothReplacementActionsByteForByte() = sandbox { _,store ->
        val photo=store.savePhoto(scope,variants(1))
        val manifest=File(photo.file.parentFile,"avatar.json")
        val future="{\"schemaVersion\":999,\"kind\":\"future\",\"data\":\"keep\"}"
        manifest.writeText(future)
        for(action in listOf<()->Unit>({store.clear(scope)},{store.savePhoto(scope,variants(2))},
            {store.saveBundled(scope,"openmoji-1f98a",setOf("openmoji-1f98a"))})) {
            try { action();fail("Future schema was modified") } catch(_:IllegalArgumentException) { }
            assertEquals(future,manifest.readText())
            assertEquals(4,photo.file.parentFile!!.listFiles()!!.count { it.extension=="png" })
        }
    }
    @Test fun presentationScopeRequiresTheMatchingOwnerAndAStableProfileIdentity() {
        val local=UserProfile(2,"Local","#FFFFFF",studioIdentity="local-id")
        assertNotNull(ProfileAvatarScope.resolve(local,AuthState.SignedOut))
        assertNull(ProfileAvatarScope.resolve(local,AuthState.Loading))
        assertNull(ProfileAvatarScope.resolve(local,AuthState.FullAccount("one","a@example.invalid")))
        val account=local.copy(studioOwnerId="one")
        assertNotNull(ProfileAvatarScope.resolve(account,AuthState.FullAccount("one","a@example.invalid")))
        assertNull(ProfileAvatarScope.resolve(account,AuthState.FullAccount("two","b@example.invalid")))
        assertNull(ProfileAvatarScope.resolve(account,AuthState.SignedOut))
        assertNull(ProfileAvatarScope.resolve(local.copy(studioIdentity=""),AuthState.SignedOut))
        assertNull(ProfileAvatarScope.resolve(account.copy(id=7),AuthState.FullAccount("one","a@example.invalid")))
    }
}
