package com.nuvio.tv.ui.screens.player

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.player.metadata.SceneBookmarkRepository
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PlayerSceneBookmarksTest {
    private suspend fun await(check: () -> Boolean) = withTimeout(5000) { while (!check()) delay(10) }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.job.cancelAndJoin();fixture.root.toFile().deleteRecursively() }
    }
    private class Fixture {
        val root=Files.createTempDirectory("telumia-bookmark-player")
        val job=SupervisorJob()
        val scope=CoroutineScope(job+Dispatchers.Unconfined)
        val selected=MutableStateFlow(2)
        val ready=MutableStateFlow(true)
        val authState=MutableStateFlow<AuthState>(AuthState.SignedOut)
        val profiles=MutableStateFlow(listOf(UserProfile(id=2,name="Local",avatarColorHex="#223344",studioIdentity="identity-A")))
        val player=MutableStateFlow(PlayerUiState(currentVideoId="show:1:2",currentStreamUrl="https://fixture.invalid/br-78"))
        val timeline=MutableStateFlow(PlaybackTimelineState(currentPosition=32_180,duration=100_000))
        val seeks=mutableListOf<Long>()
        val repo: SceneBookmarkRepository
        val controller: PlayerSceneBookmarks
        init {
            val context=mockk<Context>();every { context.filesDir } returns root.toFile()
            val manager=mockk<ProfileManager>();every { manager.profiles } returns profiles
            every { manager.activeProfileId } returns selected;every { manager.activeProfileReady } returns ready
            val auth=mockk<AuthManager>();every { auth.authState } returns authState
            repo=SceneBookmarkRepository(context,manager,auth)
            controller=PlayerSceneBookmarks(repo,player,timeline,2,"show","series",scope) { seeks+=it }
        }
    }
    @Test fun saveJumpAndDeleteUseRealDurableDataAndCurrentPlayerPosition() = fixture {
        await { !controller.state.value.loading && controller.state.value.scope!=null }
        controller.save("Rever depois");await { controller.state.value.items.size==1 && !controller.state.value.busy }
        val item=controller.state.value.items.single()
        assertEquals(32_180L,item.positionMs);assertTrue(item.createdAtMs>0)
        timeline.value=timeline.value.copy(currentPosition=50_000)
        assertTrue(controller.jump(item.id));assertEquals(listOf(32_180L),seeks)
        controller.rename(item.id,"Minha cena");await { controller.state.value.items.singleOrNull()?.name=="Minha cena" }
        controller.remove(item.id);await { controller.state.value.items.isEmpty() && !controller.state.value.busy }
        assertFalse(controller.jump(item.id))
    }
    @Test fun episodeAndSourceChangesCannotSeekUsingOldMarkers() = fixture {
        await { !controller.state.value.loading && controller.state.value.scope!=null }
        controller.save("Cena");await { controller.state.value.items.size==1 && !controller.state.value.busy }
        val item=controller.state.value.items.single()
        player.value=player.value.copy(currentVideoId="show:1:3")
        assertFalse(controller.jump(item.id));assertTrue(controller.markers(100_000).isEmpty())
        await { !controller.state.value.loading && controller.state.value.scope?.videoId=="show:1:3" }
        assertTrue(controller.state.value.items.isEmpty())
        player.value=player.value.copy(currentVideoId="show:1:2")
        await { controller.state.value.items.size==1 }
        player.value=player.value.copy(currentStreamUrl="https://fixture.invalid/original-31")
        assertFalse(controller.jump(item.id))
        await { !controller.state.value.loading && controller.state.value.items.isEmpty() }
        player.value=player.value.copy(currentStreamUrl="https://fixture.invalid/br-78")
        await { controller.state.value.items.size==1 }
        assertEquals(item,controller.state.value.items.single())
    }
    @Test fun switchingProfileOrSigningIntoAnotherAccountClearsVisibleDataAndRefusesStaleActions() = fixture {
        await { !controller.state.value.loading && controller.state.value.scope!=null }
        controller.save("Cena");await { controller.state.value.items.size==1 && !controller.state.value.busy }
        val item=controller.state.value.items.single()
        selected.value=3;assertFalse(controller.jump(item.id))
        await { controller.state.value.scope==null };assertTrue(controller.state.value.items.isEmpty())
        selected.value=2;await { controller.state.value.items.size==1 }
        ready.value=false;assertFalse(controller.jump(item.id));await { controller.state.value.scope==null }
        ready.value=true;await { controller.state.value.items.size==1 }
        authState.value=AuthState.Loading
        assertFalse(controller.jump(item.id));await { controller.state.value.scope==null }
        authState.value=AuthState.SignedOut;await { controller.state.value.items.size==1 }
        profiles.value=listOf(profiles.value.single().copy(studioIdentity="identity-recreated"))
        assertFalse(controller.jump(item.id));await { !controller.state.value.loading && controller.state.value.items.isEmpty() }
    }
    @Test fun liveAndUnknownDurationDoNotCreateBookmarksOrDispatchSeek() = fixture {
        await { !controller.state.value.loading && controller.state.value.scope!=null }
        timeline.value=timeline.value.copy(isLive=true);controller.save("No live")
        timeline.value=timeline.value.copy(isLive=false,duration=0);controller.save("Unknown")
        delay(100);assertTrue(controller.state.value.items.isEmpty());assertTrue(seeks.isEmpty())
    }
    @Test fun loadFailureIsVisibleAndRetryDoesNotDestroyUnsupportedData() = fixture {
        await { !controller.state.value.loading && controller.state.value.scope!=null }
        controller.save("Cena");await { controller.state.value.items.size==1 && !controller.state.value.busy }
        val file=Files.walk(root).use { it.filter { path -> Files.isRegularFile(path) }.findFirst().get() }
        val future="{\"schemaVersion\":99,\"items\":[]}"
        Files.writeString(file,future);controller.retry()
        await { controller.state.value.failed };controller.save("Do not replace")
        assertEquals(future,Files.readString(file))
        controller.retry();await { controller.state.value.failed }
        assertEquals(future,Files.readString(file))
    }
}
