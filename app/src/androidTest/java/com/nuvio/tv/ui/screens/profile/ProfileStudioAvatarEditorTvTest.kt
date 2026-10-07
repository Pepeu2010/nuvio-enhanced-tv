package com.nuvio.tv.ui.screens.profile

import android.graphics.Bitmap
import android.graphics.Color
import android.content.ContentValues
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.profile.studio.*
import com.nuvio.tv.domain.model.NavigationMotion
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileStudioAvatarEditorTvTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun capture(name:String) {
        File(context.getExternalFilesDir(null),name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
    private fun focus(tag:String) { compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() };compose.waitForIdle() }
    private fun press(key:Int) { instrumentation.sendKeyDownUpSync(key);compose.waitForIdle() }
    private fun sandbox(block:(OwnedProfileAvatarStore)->Unit) {
        val root=File(context.cacheDir.canonicalFile,"studio-ui-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try { block(OwnedProfileAvatarStore(root)) } finally { root.deleteRecursively() }
    }
    @Composable private fun Screen(content:@Composable ()->Unit) {
        NuvioTheme(navigationMotion=NavigationMotion.OFF) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF080E18)).padding(24.dp)) {
                Column(Modifier.fillMaxWidth().widthIn(max=760.dp)) { content() }
            }
        }
    }

    @Test fun packagedLibraryIsVerifiedAndRemoteCanSelectAndRestoreAPersistentAvatar() = sandbox { store ->
        val library=ProfileStudioAvatarRepository(context).avatars()
        assertEquals(64,library.size)
        val scope=ProfileAvatarScope("fixture",2,"studio-library")
        val saved=mutableStateOf(false)
        compose.setContent { Screen {
            ProfileStudioAvatarEditorContent(library,null,null,AvatarCrop(),false,false,true,false,saved.value,false,
                {},{},{store.clear(scope);saved.value=false},{store.saveBundled(scope,it.id,library.map { it.id }.toSet());saved.value=true},
                {},{},{},{})
        } }
        compose.waitForIdle()
        focus("studio-${library.first().id}");press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(LocalProfileAvatar.Bundled(library.first().id),store.load(scope))
        press(KeyEvent.KEYCODE_DPAD_RIGHT);press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(LocalProfileAvatar.Bundled(library[1].id),store.load(scope))
        compose.onNodeWithTag("studio-saved").assertIsDisplayed()
        capture("telumia-studio-library-tv.png")
        focus("studio-restore");press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertNull(store.load(scope))
        compose.onNodeWithTag("studio-saved").assertDoesNotExist()
    }

    @Test fun remoteAdjustsZoomAndPositionAndSavesFourActualRasterVariants() = sandbox { store ->
        val bitmap=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
        val input=try { for(x in 0 until 200)for(y in 0 until 100)bitmap.setPixel(x,y,if(x<100)Color.RED else Color.BLUE)
            ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray() }
        } finally { bitmap.recycle() }
        AvatarRasterPipeline.decode(input).use { source ->
            val crop=mutableStateOf(AvatarCrop())
            val saved=mutableStateOf(false)
            val scope=ProfileAvatarScope("fixture",2,"studio-crop")
            compose.setContent { Screen {
                ProfileStudioAvatarEditorContent(emptyList(),null,AvatarRasterPipeline.preview(source,crop.value),crop.value,true,false,true,false,saved.value,false,
                    {},{},{},{},{crop.value=it},{store.savePhoto(scope,AvatarRasterPipeline.variants(source,crop.value));saved.value=true},{},{})
            } }
            compose.waitForIdle()
            focus("studio-zoom-plus");press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(1.25f,crop.value.zoom)
            focus("studio-horizontal-plus");press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(.55f,crop.value.centerX)
            capture("telumia-studio-crop-tv.png")
            focus("studio-save");press(KeyEvent.KEYCODE_DPAD_CENTER)
            val photo=store.load(scope) as LocalProfileAvatar.Photo
            assertEquals(4,photo.file.parentFile!!.listFiles()!!.count { it.extension=="png" })
            compose.onNodeWithTag("studio-saved").assertIsDisplayed()
        }
    }

    @Test fun failedClipboardActionPreservesTheSelectionAndBusyActionsAreDisabled() {
        val failed=mutableStateOf(false)
        val busy=mutableStateOf(false)
        var cancelled=0
        compose.setContent { Screen {
            ProfileStudioAvatarEditorContent(emptyList(),null,null,AvatarCrop(),true,busy.value,true,failed.value,false,false,
                {},{failed.value=true},{},{},{},{},{cancelled++},{})
        } }
        compose.waitForIdle()
        focus("studio-paste");press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("studio-error").assertIsDisplayed()
        compose.runOnIdle { busy.value=true }
        compose.onNodeWithTag("studio-paste").assertIsNotEnabled()
        compose.onNodeWithTag("studio-save").assertIsNotEnabled()
        compose.onNodeWithTag("studio-cancel").assertIsNotEnabled()
        compose.runOnIdle { busy.value=false }
        focus("studio-cancel");press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1,cancelled)
    }

    @Test fun localGallerySelectsARealContentPhotoAndPersistsItsCrop() = sandbox { store ->
        val resolver=context.contentResolver
        val name="telumia-studio-test-${java.util.UUID.randomUUID()}.png"
        val uri=checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,name)
            put(MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/TelumiaTests")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }))
        try {
            val bitmap=Bitmap.createBitmap(160,80,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            try { checkNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { bitmap.recycle() }
            resolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            val photos=StudioDevicePhotos.load(resolver)
            val photo=photos.single { it.uri==uri }
            assertEquals(name,photo.name)
            assertTrue(photos.size<=256)
            var selected:StudioDevicePhoto?=null
            compose.setContent { Screen {
                ProfileStudioAvatarEditorContent(emptyList(),null,null,AvatarCrop(),false,false,true,false,false,false,
                    {},{},{},{},{},{},{},{},devicePhotos=photos,onDevicePhoto={selected=it})
            } }
            compose.waitForIdle()
            focus("studio-photo-${uri.lastPathSegment}");press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(uri,selected?.uri)
            AvatarRasterPipeline.readContent(resolver,checkNotNull(selected).uri).use { source ->
                val scope=ProfileAvatarScope("fixture",2,"studio-device-photo")
                store.savePhoto(scope,AvatarRasterPipeline.variants(source,AvatarCrop()))
                assertTrue((store.load(scope) as LocalProfileAvatar.Photo).file.isFile)
            }
            capture("telumia-studio-device-photos-tv.png")
        } finally { resolver.delete(uri,null,null) }
    }

    @Test fun fullProfileDialogRemainsReachableAndSavingPreservesItsIdentity() {
        val profile=UserProfile(6,"Perfil cinematográfico longo", "#E6BD75",studioIdentity="fixture-${java.util.UUID.randomUUID()}")
        var saved:UserProfile?=null
        var dismissed=0
        compose.setContent {
            NuvioTheme(navigationMotion=NavigationMotion.OFF) {
                EditProfileOverlay(profile,emptyList(),emptyList(),false,false,{null},{dismissed++},{saved=it})
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("studio-file").fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
        capture("telumia-studio-full-dialog-initial-tv.png")
        compose.onNodeWithTag("studio-profile-save").assertIsDisplayed()
        compose.onNodeWithTag("studio-profile-cancel").performScrollTo().assertIsDisplayed()
        focus("studio-profile-save");press(KeyEvent.KEYCODE_DPAD_CENTER)
        val viewport=compose.onRoot().fetchSemanticsNode().boundsInRoot
        val saveBounds=compose.onNodeWithTag("studio-profile-save").fetchSemanticsNode().boundsInRoot
        assertTrue("Save action must fit entirely inside the viewport",saveBounds.left>=viewport.left &&
            saveBounds.top>=viewport.top && saveBounds.right<=viewport.right && saveBounds.bottom<=viewport.bottom)
        assertEquals(profile,saved)
        compose.onNodeWithTag("studio-file").performScrollTo().assertIsDisplayed()
        focus("studio-file")
        val rootDp=compose.onRoot().getUnclippedBoundsInRoot()
        val scale=viewport.width/(rootDp.right-rootDp.left).value
        val intended=compose.onNodeWithTag("studio-file").getUnclippedBoundsInRoot()
        val visible=compose.onNodeWithTag("studio-file").fetchSemanticsNode().boundsInRoot
        assertTrue("Local import action must be fully visible after D-pad focus",
            visible.height>=(intended.bottom-intended.top).value*scale-1f &&
                visible.width>=(intended.right-intended.left).value*scale-1f)
        capture("telumia-studio-full-dialog-tv.png")
        compose.onNodeWithTag("studio-profile-cancel").performScrollTo()
        focus("studio-profile-cancel");press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1,dismissed)
    }
}
