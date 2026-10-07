package com.nuvio.tv.core.profile.studio

import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.UserProfile
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal data class ProfileAvatarScope(val ownerId: String, val profileIndex: Int, val identity: String) {
    init { require(ownerId.length in 1..512 && profileIndex in 1..6 && identity.length in 1..512) }
    companion object {
        fun resolve(profile: UserProfile, auth: AuthState): ProfileAvatarScope? {
            val owner = when (auth) {
                AuthState.Loading -> return null
                AuthState.SignedOut -> if (profile.studioOwnerId == null) "device-local" else return null
                is AuthState.FullAccount -> if (profile.studioOwnerId == auth.userId) "account-${auth.userId}" else return null
            }
            return runCatching { ProfileAvatarScope(owner,profile.id,profile.studioIdentity) }.getOrNull()
        }
    }
}

internal sealed interface LocalProfileAvatar {
    data class Photo(val assetId: String, val file: File) : LocalProfileAvatar
    data class Bundled(val assetId: String) : LocalProfileAvatar
}

/** Private, durable images. Remote profile fields and media caches never contain this manifest. */
internal class OwnedProfileAvatarStore(
    private val root: File,
    private val publish: (File,File)->Unit = { source,destination -> android.system.Os.rename(source.absolutePath,destination.absolutePath) }
) {
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val bundlePattern = Regex("openmoji-[a-f0-9]{4,6}")

    @Synchronized fun load(scope: ProfileAvatarScope): LocalProfileAvatar? = runCatching {
        val directory = directory(scope,false) ?: return@runCatching null
        val manifest = File(directory,"avatar.json")
        if (!regular(manifest) || manifest.length() !in 1..16384) return@runCatching null
        val payload = Json.parseToJsonElement(manifest.readText()).jsonObject
        if (payload["schemaVersion"]?.jsonPrimitive?.intOrNull != 1) return@runCatching null
        val id = payload["assetId"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        when (payload["kind"]?.jsonPrimitive?.contentOrNull) {
            "photo" -> if (hashPattern.matches(id)) File(directory,"$id-512.png").takeIf { regular(it) && it.length() in 1..2097152 }?.let { LocalProfileAvatar.Photo(id,it) } else null
            "bundle" -> id.takeIf(bundlePattern::matches)?.let(LocalProfileAvatar::Bundled)
            else -> null
        }
    }.getOrNull()

    @Synchronized fun savePhoto(scope: ProfileAvatarScope, variants: Map<Int,ByteArray>): LocalProfileAvatar.Photo {
        require(variants.keys == AvatarImagePolicy.variantSizes.toSet() && variants.values.all { it.size in 1..2097152 })
        val directory = requireNotNull(directory(scope,true))
        requireWritableManifest(directory)
        val previous = load(scope)
        val id = digest(variants.getValue(512))
        try {
            variants.forEach { (size,bytes) -> atomicWrite(File(directory,"$id-$size.png"),bytes) }
            manifest(directory,"photo",id)
        } catch (error: Exception) {
            if (previous !is LocalProfileAvatar.Photo || previous.assetId != id)
                AvatarImagePolicy.variantSizes.forEach { File(directory,"$id-$it.png").takeIf(::regular)?.delete() }
            throw error
        }
        removePrevious(directory,previous,id)
        return LocalProfileAvatar.Photo(id,File(directory,"$id-512.png"))
    }

    @Synchronized fun saveBundled(scope: ProfileAvatarScope, id: String, availableIds: Set<String>) {
        require(bundlePattern.matches(id) && id in availableIds)
        val directory = requireNotNull(directory(scope,true))
        requireWritableManifest(directory)
        val previous = load(scope)
        manifest(directory,"bundle",id)
        removePrevious(directory,previous,null)
    }

    @Synchronized fun clear(scope: ProfileAvatarScope) {
        val directory = directory(scope,false) ?: return
        requireWritableManifest(directory)
        val previous = load(scope)
        val manifest = File(directory,"avatar.json")
        if (manifest.exists() && (!regular(manifest) || !manifest.delete())) error("Avatar reset failed")
        removePrevious(directory,previous,null)
    }

    @Synchronized fun adoptIdentity(from: ProfileAvatarScope, to: ProfileAvatarScope) {
        require(from.ownerId == to.ownerId && from.profileIndex == to.profileIndex)
        if (from == to) return
        val destination = directory(to,false)
        if (destination != null && File(destination,"avatar.json").exists()) return
        when (val previous = load(from)) {
            is LocalProfileAvatar.Photo -> {
                val directory = requireNotNull(directory(from,false))
                val variants = AvatarImagePolicy.variantSizes.associateWith {
                    val file=File(directory,"${previous.assetId}-$it.png")
                    require(regular(file) && file.length() in 1..2097152)
                    file.readBytes()
                }
                savePhoto(to,variants)
            }
            is LocalProfileAvatar.Bundled -> saveBundled(to,previous.assetId,setOf(previous.assetId))
            null -> return
        }
        // Keep the prior copy until the profile DataStore commit succeeds; later owned-file GC
        // may remove it. A failed preferences write must not discard the old selection.
    }

    private fun directory(scope: ProfileAvatarScope, create: Boolean): File? {
        val owner = File(root,digest(scope.ownerId.toByteArray()))
        val profile = File(owner,"profile-${scope.profileIndex}-${digest(scope.identity.toByteArray())}")
        for (file in listOf(root,owner,profile)) {
            if (file.absoluteFile.normalize() != file.canonicalFile) return null
            if (create && !file.exists() && !file.mkdirs()) return null
            if (!file.isDirectory) return null
        }
        return profile
    }

    private fun regular(file: File) = file.isFile && file.absoluteFile.normalize() == file.canonicalFile
    private fun requireWritableManifest(directory: File) {
        val file=File(directory,"avatar.json")
        if (!file.exists()) return
        require(regular(file) && file.length() in 1..16384) { "Avatar manifest cannot be modified" }
        val payload=Json.parseToJsonElement(file.readText()).jsonObject
        require(payload["schemaVersion"]?.jsonPrimitive?.intOrNull == 1) { "Avatar schema requires a newer application" }
    }
    private fun manifest(directory: File, kind: String, id: String) = atomicWrite(File(directory,"avatar.json"),
        buildJsonObject { put("schemaVersion",1);put("kind",kind);put("assetId",id) }.toString().toByteArray())

    private fun atomicWrite(file: File, bytes: ByteArray) {
        // Android's native rename replaces atomically on the same private filesystem (API 21+).
        val pending = File.createTempFile("avatar-",".part",file.parentFile)
        try {
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            publish(pending,file)
        } finally { pending.delete() }
    }

    private fun removePrevious(directory: File, previous: LocalProfileAvatar?, currentId: String?) {
        if (previous !is LocalProfileAvatar.Photo || previous.assetId == currentId) return
        AvatarImagePolicy.variantSizes.forEach { File(directory,"${previous.assetId}-$it.png").takeIf(::regular)?.delete() }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
