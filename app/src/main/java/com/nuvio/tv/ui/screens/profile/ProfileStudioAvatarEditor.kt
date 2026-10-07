package com.nuvio.tv.ui.screens.profile

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.studio.*
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint @InstallIn(SingletonComponent::class)
internal interface ProfileStudioEntryPoint {
    fun studioAvatars(): ProfileStudioAvatarRepository
    fun authManager(): AuthManager
}

@Composable private fun studioDependencies(): ProfileStudioEntryPoint {
    val context=LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context,ProfileStudioEntryPoint::class.java) }
}

@Composable internal fun rememberStudioAvatarImage(profile: UserProfile?): String? {
    val dependencies=studioDependencies()
    val auth by dependencies.authManager().authState.collectAsState()
    val revision by dependencies.studioAvatars().revision.collectAsState()
    return key(profile,auth,revision) {
        val image by produceState<String?>(null) {
            value=withContext(Dispatchers.IO) { profile?.let { dependencies.studioAvatars().imageUri(it,auth) } }
        }
        image
    }
}

@Composable internal fun ProfileStudioAvatarEditor(profile: UserProfile, onBusyChanged: (Boolean)->Unit = {}) {
    val dependencies=studioDependencies()
    val auth by dependencies.authManager().authState.collectAsState()
    // Discard unsaved pixels on a profile/account change instead of transferring them.
    key(profile.studioIdentity,auth) {
        val context=LocalContext.current
        val repository=dependencies.studioAvatars()
        val coroutine=rememberCoroutineScope()
        val eligible=ProfileAvatarScope.resolve(profile,auth)!=null
        var source by remember { mutableStateOf<AvatarRasterSource?>(null) }
        var crop by remember { mutableStateOf(AvatarCrop()) }
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var saveFailure by remember { mutableStateOf(false) }
        var libraryFailed by remember { mutableStateOf(false) }
        var saved by remember { mutableStateOf(false) }
        var library by remember { mutableStateOf<List<StudioAvatar>>(emptyList()) }
        var retry by remember { mutableIntStateOf(0) }
        var devicePhotos by remember { mutableStateOf<List<StudioDevicePhoto>?>(null) }
        var photosLoading by remember { mutableStateOf(false) }
        var photoError by remember { mutableStateOf<String?>(null) }
        val current=rememberStudioAvatarImage(profile)
        LaunchedEffect(busy) { onBusyChanged(busy) }
        DisposableEffect(Unit) { onDispose { onBusyChanged(false) } }
        DisposableEffect(source) { val owned=source; onDispose { owned?.close() } }
        LaunchedEffect(retry) {
            libraryFailed=false
            try { library=withContext(Dispatchers.IO) { repository.avatars() } }
            catch(error:CancellationException) { throw error }
            catch(_:Exception) { libraryFailed=true }
        }
        val preview by produceState<ByteArray?>(null,source,crop) {
            value=null
            val image=source ?: return@produceState
            delay(60)
            value=withContext(Dispatchers.IO) { runCatching { AvatarRasterPipeline.preview(image,crop) }.getOrNull() }
        }
        fun import(uri:Uri) {
            if(busy || !eligible)return
            coroutine.launch {
                busy=true;failed=false;saveFailure=false;saved=false
                var decoded:AvatarRasterSource?=null
                try {
                    withContext(Dispatchers.IO) { decoded=AvatarRasterPipeline.readContent(context.contentResolver,uri) }
                    source=decoded;decoded=null;crop=AvatarCrop()
                } catch(error:CancellationException) { throw error }
                catch(_:Exception) { failed=true }
                finally { decoded?.close();busy=false }
            }
        }
        val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null)import(uri) }
        fun loadDevicePhotos() {
            if(busy || photosLoading)return
            coroutine.launch {
                photosLoading=true;devicePhotos=emptyList();photoError=null
                try { devicePhotos=withContext(Dispatchers.IO) { StudioDevicePhotos.load(context.contentResolver) } }
                catch(error:CancellationException) { throw error }
                catch(_:Exception) { photoError=context.getString(R.string.studio_avatar_photos_error) }
                finally { photosLoading=false }
            }
        }
        val photoPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if(grants.values.any { it } || StudioDevicePhotos.canRead(context))loadDevicePhotos()
            else photoError=context.getString(R.string.studio_avatar_photos_permission)
        }
        fun save(action:()->Unit) {
            if(busy || !eligible)return
            coroutine.launch {
                busy=true;failed=false;saveFailure=true;saved=false
                try {
                    withContext(Dispatchers.IO) {
                        check(dependencies.authManager().authState.value==auth)
                        action()
                    }
                    saved=true;source=null
                } catch(error:CancellationException) { throw error }
                catch(_:Exception) { failed=true }
                finally { busy=false }
            }
        }
        ProfileStudioAvatarEditorContent(
            library=library,currentImage=current,preview=preview,crop=crop,hasDraft=source!=null,
            busy=busy,eligible=eligible,failed=failed,saved=saved,libraryFailed=libraryFailed,
            onChooseFile={
                saveFailure=false
                try {
                    if(StudioDevicePhotos.documentPickerAvailable(context))picker.launch(arrayOf("image/png","image/jpeg","image/gif","image/bmp","image/webp"))
                    else if(StudioDevicePhotos.canRead(context))loadDevicePhotos()
                    else photoPermission.launch(StudioDevicePhotos.permissions())
                } catch(_:Exception) { failed=true }
            },
            onPaste={
                saveFailure=false
                try {
                    val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip=clipboard.primaryClip
                    val uri=clip?.takeIf { it.itemCount==1 }?.getItemAt(0)?.uri
                    if(uri?.scheme=="content")import(uri) else failed=true
                } catch(_:Exception) { failed=true }
            },
            onReset={ save { repository.reset(profile,auth) } },
            onSelect={ avatar -> save { repository.saveBundled(profile,auth,avatar.id) } },
            onCrop={ crop=it.validated();saved=false },
            onSave={ val image=source;val selection=crop;if(image!=null)save { repository.savePhoto(profile,auth,AvatarRasterPipeline.variants(image,selection)) } },
            onCancel={ if(!busy) { source=null;failed=false } },
            onRetry={retry++},devicePhotos=devicePhotos,photosLoading=photosLoading,photoError=photoError,
            onDevicePhoto={import(it.uri)},onLibrary={devicePhotos=null;photoError=null},onRefreshPhotos={loadDevicePhotos()},
            errorMessage=if(saveFailure)stringResource(R.string.studio_avatar_save_error)else null,
            independentScroll=false
        )
    }
}

private val StudioInk=Color(0xFF0D1522)
private val StudioGold=Color(0xFFE6BD75)
private val StudioPaper=Color(0xFFF5EFDF)

/** Content of the existing profile overlay; actions are real imports/persistence in its wrapper. */
@Composable internal fun ProfileStudioAvatarEditorContent(
    library:List<StudioAvatar>,currentImage:String?,preview:ByteArray?,crop:AvatarCrop,hasDraft:Boolean,
    busy:Boolean,eligible:Boolean,failed:Boolean,saved:Boolean,libraryFailed:Boolean,
    onChooseFile:()->Unit,onPaste:()->Unit,onReset:()->Unit,onSelect:(StudioAvatar)->Unit,
    onCrop:(AvatarCrop)->Unit,onSave:()->Unit,onCancel:()->Unit,onRetry:()->Unit,
    devicePhotos:List<StudioDevicePhoto>?=null,photosLoading:Boolean=false,photoError:String?=null,
    onDevicePhoto:(StudioDevicePhoto)->Unit={},onLibrary:()->Unit={},onRefreshPhotos:()->Unit={},
    errorMessage:String?=null,
    independentScroll:Boolean=true,
    modifier:Modifier=Modifier
) {
    val portuguese=LocalConfiguration.current.locales[0].language=="pt"
    var category by remember { mutableStateOf<String?>(null) }
    val choices=remember(library,category) { library.filter { category==null || it.category==category } }
    val scrollState=rememberScrollState()
    Column(modifier.fillMaxWidth().then(if(independentScroll)Modifier.heightIn(max=340.dp)else Modifier)
        .clip(RoundedCornerShape(18.dp)).background(StudioInk).padding(12.dp)
        .then(if(independentScroll)Modifier.verticalScroll(scrollState)else Modifier)
        .testTag("studio-tv-editor"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            AsyncImage(currentImage,stringResource(R.string.studio_avatar_current),modifier=Modifier.size(40.dp).clip(CircleShape).background(StudioPaper),contentScale=ContentScale.Crop)
            StudioButton(stringResource(R.string.studio_avatar_file),"studio-file",!busy&&eligible,onChooseFile)
            StudioButton(stringResource(R.string.studio_avatar_paste),"studio-paste",!busy&&eligible,onPaste)
            StudioButton(stringResource(R.string.studio_avatar_restore),"studio-restore",!busy&&eligible,onReset)
        }
        if(!eligible) Text(stringResource(R.string.studio_avatar_unavailable),color=StudioPaper,fontSize=13.sp)
        if(busy) Text(stringResource(R.string.studio_avatar_working),color=StudioGold,fontSize=13.sp)
        if(failed) Text(errorMessage?:stringResource(R.string.studio_avatar_error),color=Color(0xFFFFB7A5),fontSize=13.sp,modifier=Modifier.testTag("studio-error"))
        if(saved) Text(stringResource(R.string.studio_avatar_saved),color=StudioGold,fontSize=13.sp,modifier=Modifier.testTag("studio-saved"))
        if(photoError!=null)Text(photoError,color=Color(0xFFFFB7A5),fontSize=13.sp)
        if(hasDraft) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val controls: @Composable ()->Unit = {
                    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    CropControl(stringResource(R.string.studio_avatar_zoom),"zoom",crop.zoom,1f,8f,.25f,!busy) { onCrop(crop.copy(zoom=it)) }
                    CropControl(stringResource(R.string.studio_avatar_horizontal),"horizontal",crop.centerX,0f,1f,.05f,!busy) { onCrop(crop.copy(centerX=it)) }
                    CropControl(stringResource(R.string.studio_avatar_vertical),"vertical",crop.centerY,0f,1f,.05f,!busy) { onCrop(crop.copy(centerY=it)) }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        StudioButton(stringResource(R.string.studio_avatar_save_crop),"studio-save",!busy&&eligible,onSave)
                        StudioButton(stringResource(R.string.studio_avatar_cancel_crop),"studio-cancel",!busy,onCancel)
                    }
                }
                }
                if(maxWidth<420.dp) Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    AsyncImage(preview,stringResource(R.string.studio_avatar_preview),modifier=Modifier.size(96.dp).clip(CircleShape).background(StudioPaper),contentScale=ContentScale.Crop)
                    controls()
                } else Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    AsyncImage(preview,stringResource(R.string.studio_avatar_preview),modifier=Modifier.size(140.dp).clip(CircleShape).background(StudioPaper),contentScale=ContentScale.Crop)
                    controls()
                }
            }
        } else if(devicePhotos!=null) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                StudioButton(stringResource(R.string.studio_avatar_library),"studio-back-library",!busy,onLibrary)
                StudioButton(stringResource(R.string.studio_avatar_retry),"studio-refresh-photos",!busy&&!photosLoading,onRefreshPhotos)
            }
            if(photosLoading)Text(stringResource(R.string.studio_avatar_loading),color=StudioPaper,fontSize=13.sp)
            else if(devicePhotos.isEmpty())Text(stringResource(R.string.studio_avatar_photos_empty),color=StudioPaper,fontSize=13.sp)
            else LazyVerticalGrid(GridCells.Adaptive(90.dp),Modifier.fillMaxWidth().height(180.dp).testTag("studio-device-photos"),
                horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(devicePhotos,key={it.uri.toString()}) { photo ->
                    StudioButton("","studio-photo-${photo.uri.lastPathSegment}",!busy&&eligible,{onDevicePhoto(photo)}) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.width(74.dp)) {
                            AsyncImage(photo.uri,photo.name,modifier=Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),contentScale=ContentScale.Crop)
                            Text(photo.name,color=androidx.tv.material3.LocalContentColor.current,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                listOf(null to R.string.studio_avatar_all,"animals" to R.string.studio_avatar_animals,"fantasy" to R.string.studio_avatar_fantasy,"space" to R.string.studio_avatar_space,"nature" to R.string.studio_avatar_nature).forEach { (id,label) ->
                    StudioButton(stringResource(label),"studio-category-${id?:"all"}",!busy,{category=id},selected=category==id)
                }
            }
            if(libraryFailed) {
                Text(stringResource(R.string.studio_avatar_library_error),color=StudioPaper,fontSize=13.sp)
                StudioButton(stringResource(R.string.studio_avatar_retry),"studio-retry",!busy,onRetry)
            } else if(library.isEmpty()) Text(stringResource(R.string.studio_avatar_loading),color=StudioPaper,fontSize=13.sp)
            else LazyVerticalGrid(GridCells.Adaptive(80.dp),Modifier.fillMaxWidth().height(180.dp).testTag("studio-library"),
                horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(choices,key={it.id}) { avatar ->
                    StudioButton("","studio-${avatar.id}",!busy&&eligible,{onSelect(avatar)}) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.width(64.dp)) {
                            AsyncImage(avatar.imageUri,if(portuguese)avatar.namePtBr else avatar.nameEn,modifier=Modifier.size(48.dp).clip(CircleShape).background(StudioPaper),contentScale=ContentScale.Fit)
                            Text(if(portuguese)avatar.namePtBr else avatar.nameEn,color=androidx.tv.material3.LocalContentColor.current,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Text(stringResource(R.string.studio_avatar_credits),color=StudioPaper.copy(alpha=.7f),fontSize=11.sp)
        }
    }
}

@Composable private fun StudioButton(label:String,tag:String,enabled:Boolean,onClick:()->Unit,selected:Boolean=false,description:String?=null,content:(@Composable ()->Unit)?=null) {
    Button(onClick=onClick,enabled=enabled,modifier=Modifier.testTag(tag).semantics { if(description!=null)contentDescription=description },
        scale=ButtonDefaults.scale(focusedScale=1f),
        colors=ButtonDefaults.colors(containerColor=if(selected)Color(0xFF3A3021)else Color(0xFF1A2637),contentColor=StudioPaper,
            focusedContainerColor=StudioGold,focusedContentColor=StudioInk),contentPadding=PaddingValues(horizontal=10.dp,vertical=7.dp)) {
        if(content!=null)content() else Text(label,color=androidx.tv.material3.LocalContentColor.current,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
    }
}

@Composable private fun CropControl(label:String,tag:String,value:Float,min:Float,max:Float,step:Float,enabled:Boolean,onChange:(Float)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label,Modifier.width(96.dp),color=StudioPaper,fontSize=12.sp)
        StudioButton("−","studio-$tag-minus",enabled&&value>min,{onChange((value-step).coerceIn(min,max))},description=stringResource(R.string.studio_avatar_decrease,label))
        Text(if(tag=="zoom")String.format(java.util.Locale.ROOT,"%.2f×",value)else "${(value*100).toInt()}%",Modifier.width(50.dp),color=StudioGold,fontSize=12.sp)
        StudioButton("+","studio-$tag-plus",enabled&&value<max,{onChange((value+step).coerceIn(min,max))},description=stringResource(R.string.studio_avatar_increase,label))
    }
}
