package com.nuvio.tv.core.profile.studio

import android.content.Context
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

internal data class StudioAvatar(val id: String,val category: String,val namePtBr: String,val nameEn: String,val imageUri: String)

@Singleton
class ProfileStudioAvatarRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val store = OwnedProfileAvatarStore(File(context.filesDir.canonicalFile,"profile-studio-v1/avatars"))
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private var library: List<StudioAvatar>? = null

    @Synchronized internal fun avatars(): List<StudioAvatar> {
        library?.let { return it }
        val path = "profile-studio/avatars"
        fun bytes(name: String, limit: Int): ByteArray = context.assets.open("$path/$name").use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) { val count=input.read(buffer);if(count<0)break;if(out.size()+count>limit)error("Avatar asset oversized");out.write(buffer,0,count) }
            out.toByteArray()
        }
        val catalog = Json.parseToJsonElement(bytes("catalog.json",65536).toString(Charsets.UTF_8)).jsonObject
        require(catalog["schemaVersion"]?.jsonPrimitive?.intOrNull == 1 && catalog["sourceCommit"]?.jsonPrimitive?.content == "f9fc506a3f913be9897ab0181d611d4c910a4104")
        val entries = catalog.getValue("items").jsonArray
        require(entries.size == 64)
        val result = entries.map { item ->
            val data=item.jsonObject
            fun value(key:String)=data.getValue(key).jsonPrimitive.content
            val id=value("id");val file=value("file")
            require(Regex("openmoji-[a-f0-9]{4,6}").matches(id) && Regex("[A-F0-9]{4,6}\\.svg").matches(file))
            val svg=bytes(file,262144)
            val hash=MessageDigest.getInstance("SHA-256").digest(svg).joinToString(""){"%02x".format(it)}
            require(hash==value("sha256"))
            require(!Regex("<!DOCTYPE|<!ENTITY|<script|<foreignObject|(?:xlink:)?href\\s*=|on\\w+\\s*=",RegexOption.IGNORE_CASE).containsMatchIn(svg.toString(Charsets.UTF_8)))
            StudioAvatar(id,value("category"),value("namePtBr"),value("nameEn"),"file:///android_asset/$path/$file")
        }
        require(result.map { it.id }.toSet().size == 64)
        library=result
        return result
    }

    internal fun imageUri(profile: UserProfile, auth: AuthState): String? {
        val scope=ProfileAvatarScope.resolve(profile,auth) ?: return null
        return when(val avatar=store.load(scope)) {
            is LocalProfileAvatar.Photo -> avatar.file.toURI().toString()
            is LocalProfileAvatar.Bundled -> runCatching { avatars().firstOrNull { it.id==avatar.assetId }?.imageUri }.getOrNull()
            null -> null
        }
    }
    internal fun savePhoto(profile: UserProfile,auth:AuthState,variants:Map<Int,ByteArray>) {
        store.savePhoto(requireNotNull(ProfileAvatarScope.resolve(profile,auth)),variants); changed()
    }
    internal fun saveBundled(profile:UserProfile,auth:AuthState,id:String) {
        store.saveBundled(requireNotNull(ProfileAvatarScope.resolve(profile,auth)),id,avatars().map { it.id }.toSet());changed()
    }
    internal fun reset(profile:UserProfile,auth:AuthState) {
        store.clear(requireNotNull(ProfileAvatarScope.resolve(profile,auth)));changed()
    }
    internal fun adoptRemoteIdentity(previous:UserProfile?,next:UserProfile) {
        if (previous == null || previous.studioOwnerId == null || previous.studioOwnerId != next.studioOwnerId ||
            previous.studioRemoteId != null || next.studioRemoteId == null || previous.studioIdentity == next.studioIdentity) return
        val owner="account-${next.studioOwnerId}"
        store.adoptIdentity(ProfileAvatarScope(owner,previous.id,previous.studioIdentity),ProfileAvatarScope(owner,next.id,next.studioIdentity))
        changed()
    }
    @Synchronized private fun changed() { changes.value++ }
}
