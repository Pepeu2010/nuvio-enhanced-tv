package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.UserProfile
import org.junit.Assert.*
import org.junit.Test

class ProfileStudioIdentityTest {
    private fun profile(owner:String?="one",remote:String?=null)=UserProfile(2,"Person","#FFFFFF",studioOwnerId=owner,studioRemoteId=remote)
    @Test fun legacyLocalIdentityIsPreservedOnEditsButRecreatedProfilesGetNewIdentities() {
        val first=profile().withStudioIdentity()
        assertEquals(first.studioIdentity,profile().withStudioIdentity(first).studioIdentity)
        assertNotEquals(first.studioIdentity,profile().withStudioIdentity().studioIdentity)
        assertNotEquals(first.studioIdentity,profile("two").withStudioIdentity(first).studioIdentity)
    }
    @Test fun remoteIdentitySurvivesAccountStoreResetAndDetectsRecreationAtTheSameIndex() {
        val first=profile(remote="uuid-a").withStudioIdentity()
        assertEquals(first.studioIdentity,profile(remote="uuid-a").withStudioIdentity(first).studioIdentity)
        assertEquals(first.studioIdentity,profile(remote="uuid-a").withStudioIdentity().studioIdentity)
        assertNotEquals(first.studioIdentity,profile(remote="uuid-b").withStudioIdentity(first).studioIdentity)
    }
    @Test fun localJsonRoundTripKeepsTheSeparatePresentationIdentityAndLegacyAvatarFields() {
        val source=profile(remote="uuid-a").copy(avatarId="server-avatar",avatarUrl="https://example.invalid/avatar.png").withStudioIdentity()
        assertEquals(source,ProfileJson.fromDomain(source).toDomain())
        assertEquals("legacy-local-2",ProfileJson(2,"Legacy","#FFFFFF").toDomain().studioIdentity)
        assertEquals("server-avatar",ProfileJson.fromDomain(source).avatarId)
        assertEquals("https://example.invalid/avatar.png",ProfileJson.fromDomain(source).avatarUrl)
    }
}
