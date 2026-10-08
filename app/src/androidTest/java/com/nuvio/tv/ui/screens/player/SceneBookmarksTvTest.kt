package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.player.metadata.*
import com.nuvio.tv.core.profile.studio.ProfileAvatarScope
import com.nuvio.tv.domain.model.NavigationMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real dialog and durable files; seek callback is checked, not video playback. */
@RunWith(AndroidJUnit4::class)
class SceneBookmarksTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val scope = SceneBookmarkScope(ProfileAvatarScope("device-local",2,"native-profile"),"show","series","show:1:2",
        SceneBookmarkScope.sourceEdition("local-cut-78"))

    private fun activate(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag(tag).assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
    }
    private fun sandbox(block: (SceneBookmarkStore) -> Unit) {
        val root = File(context.filesDir,"scene-bookmark-qa-${UUID.randomUUID()}")
        try { block(SceneBookmarkStore(root.toPath())) } finally { root.deleteRecursively() }
    }
    @Test fun dpadSaveRenameJumpAndRemoveUseTheSameDurableMoment() = sandbox { store ->
        val state = mutableStateOf(SceneBookmarkPanelState(scope,loading=false))
        var jumped: Long? = null
        compose.setContent { NuvioTheme(navigationMotion=NavigationMotion.OFF) {
            SceneBookmarksDialog(state.value,32_180,true,
                onSave={ state.value=state.value.copy(items=store.save(scope,32_180,100_000,it)) },
                onRename={ id,name -> state.value=state.value.copy(items=store.rename(scope,id,name)) },
                onRemove={ state.value=state.value.copy(items=store.remove(scope,it)) },
                onJump={ id,_ -> jumped=store.load(scope).first { it.id==id }.positionMs },onRetry={},onDismiss={})
        } }
        compose.onNodeWithTag("scene-bookmark-save").assertIsFocused()
        compose.onNodeWithTag("scene-bookmark-name").performTextReplacement("Cena favorita para rever com a família")
        activate("scene-bookmark-save")
        val item=store.load(scope).single()
        assertEquals(32_180L,item.positionMs)
        activate("scene-bookmark-rename-${item.id}")
        compose.onNodeWithTag("scene-bookmark-name").performTextReplacement("Rever depois")
        activate("scene-bookmark-save")
        assertEquals("Rever depois",store.load(scope).single().name)
        activate("scene-bookmark-jump-${item.id}")
        assertEquals(32_180L,jumped)
        val directory=context.getExternalFilesDir(null)!!
        File(directory,"telumia-scene-bookmarks-tv.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }
        activate("scene-bookmark-remove-${item.id}")
        assertTrue(store.load(scope).isEmpty())
    }
    @Test fun busyAndUnavailableStatesDoNotExposeEnabledSaveAndBackDismisses() {
        val state=mutableStateOf(SceneBookmarkPanelState(scope,loading=true))
        var dismissed=false
        compose.setContent { NuvioTheme(navigationMotion=NavigationMotion.OFF) {
            SceneBookmarksDialog(state.value,0,false,onSave={fail("save during load")},onRename={_,_->},onRemove={},onJump={_,_->},onRetry={},onDismiss={dismissed=true})
        } }
        compose.onNodeWithTag("scene-bookmark-save").assertIsNotEnabled()
        compose.onNodeWithTag("scene-bookmark-close").assertIsFocused()
        compose.runOnIdle { state.value=SceneBookmarkPanelState(scope=null,loading=false) }
        compose.onNodeWithTag("scene-bookmark-save").assertIsNotEnabled()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.runOnIdle { assertTrue(dismissed) }
    }
    @Test fun rejectedPublicationKeepsRealPreviousMomentAndErrorOffersRetry() = sandbox { store ->
        val item=store.save(scope,10_000,100_000,"Momento existente",1234).single()
        var retries=0
        val state=mutableStateOf(SceneBookmarkPanelState(scope,listOf(item),loading=false,failed=true))
        compose.setContent { NuvioTheme(navigationMotion=NavigationMotion.OFF) {
            SceneBookmarksDialog(state.value,0,true,onSave={fail("write through error")},onRename={_,_->},onRemove={},onJump={_,_->},
                onRetry={retries++;state.value=SceneBookmarkPanelState(scope,store.load(scope),loading=false)},onDismiss={})
        } }
        compose.onNodeWithTag("scene-bookmark-save").assertIsNotEnabled()
        compose.onNodeWithTag("scene-bookmark-jump-${item.id}").assertIsNotEnabled()
        val retry=context.getString(com.nuvio.tv.R.string.scene_bookmarks_retry)
        compose.onNodeWithText(retry).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle();assertEquals(1,retries);assertEquals(item,store.load(scope).single())
        compose.onNodeWithTag("scene-bookmark-jump-${item.id}").assertIsEnabled().assertIsDisplayed()
    }
    @Test fun renewedOrDifferentSourceRequiresAnExplicitCutConfirmation() = sandbox { store ->
        val other=scope.copy(editionKey=SceneBookmarkScope.sourceEdition("another-url-or-cut"))
        val item=store.save(other,42_000,100_000,"Cena em outra fonte",1234).single()
        var jumps=0
        compose.setContent { NuvioTheme(navigationMotion=NavigationMotion.OFF) {
            SceneBookmarksDialog(SceneBookmarkPanelState(scope,store.all(scope),loading=false),0,true,
                onSave={},onRename={_,_->},onRemove={},onJump={id,confirmed -> assertEquals(item.id,id);assertTrue(confirmed);jumps++},onRetry={},onDismiss={})
        } }
        activate("scene-bookmark-jump-${item.id}")
        assertEquals(0,jumps)
        compose.onNodeWithTag("scene-bookmark-confirm").assertIsDisplayed()
        File(context.getExternalFilesDir(null),"telumia-scene-bookmarks-confirm-tv.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }
        activate("scene-bookmark-confirm")
        assertEquals(1,jumps);assertEquals(item,store.load(other).single())
    }
}
