package com.nuvio.tv.core.profile.studio

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

internal data class StudioDevicePhoto(val uri: Uri,val name:String)

/** Explicit local photo selection when a TV has no working document picker. */
internal object StudioDevicePhotos {
    fun documentPickerAvailable(context:Context):Boolean {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        val activity=context.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return false
        return activity.packageName != "com.android.tv.frameworkpackagestubs" && !activity.name.contains("DocumentsStub")
    }
    fun permissions():Array<String> = when {
        Build.VERSION.SDK_INT>=34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT>=33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun canRead(context:Context)=permissions().any { ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED }

    fun load(resolver:ContentResolver):List<StudioDevicePhoto> {
        val uri=MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val allowed=AvatarImagePolicy.rasterMimeTypes.toList()
        val selection="${MediaStore.Images.Media.MIME_TYPE} IN (${allowed.joinToString(","){"?"}}) AND ${MediaStore.Images.Media.SIZE} > 0 AND ${MediaStore.Images.Media.SIZE} <= ?"
        val args=(allowed + AvatarImagePolicy.MAX_INPUT_BYTES.toString()).toTypedArray()
        val result=mutableListOf<StudioDevicePhoto>()
        resolver.query(uri,arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME),selection,args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")?.use { cursor ->
            val id=cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name=cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while(result.size<256 && cursor.moveToNext()) {
                val number=cursor.getLong(id)
                if(number<0)continue
                result+=StudioDevicePhoto(ContentUris.withAppendedId(uri,number),
                    (cursor.getString(name)?:"").filterNot { it.isISOControl() }.take(128))
            }
        } ?: error("Local photo provider unavailable")
        return result
    }
}
