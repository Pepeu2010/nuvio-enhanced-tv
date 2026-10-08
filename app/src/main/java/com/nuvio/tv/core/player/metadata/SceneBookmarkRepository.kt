package com.nuvio.tv.core.player.metadata

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.studio.ProfileAvatarScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SceneBookmarkRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val profiles: ProfileManager,
    private val auth: AuthManager,
) {
    private val store = SceneBookmarkStore(context.filesDir.canonicalFile.toPath().resolve("scene-bookmarks-v1"))
    internal val revision = MutableStateFlow(0L)
    internal fun owners(profileId: Int) = combine(profiles.profiles, profiles.activeProfileId, auth.authState, profiles.activeProfileReady) { list, active, state, ready ->
        list.firstOrNull { ready && it.id == profileId && active == profileId }?.let { ProfileAvatarScope.resolve(it, state) }
    }.distinctUntilChanged()

    internal fun eligible(scope: SceneBookmarkScope): Boolean = profiles.activeProfileReady.value && profiles.activeProfileId.value == scope.owner.profileIndex &&
        profiles.profiles.value.firstOrNull { it.id == scope.owner.profileIndex }
            ?.let { ProfileAvatarScope.resolve(it, auth.authState.value) } == scope.owner

    internal suspend fun load(scope: SceneBookmarkScope) = withContext(Dispatchers.IO) {
        check(eligible(scope)); store.all(scope)
    }
    internal suspend fun save(scope: SceneBookmarkScope, position: Long, duration: Long, name: String) = withContext(Dispatchers.IO) {
        check(eligible(scope)); store.save(scope, position, duration, name); changed(); store.all(scope)
    }
    internal suspend fun rename(scope: SceneBookmarkScope, id: String, name: String) = withContext(Dispatchers.IO) {
        check(eligible(scope)); store.rename(scope, id, name); changed(); store.all(scope)
    }
    internal suspend fun remove(scope: SceneBookmarkScope, id: String) = withContext(Dispatchers.IO) {
        check(eligible(scope)); store.remove(scope, id); changed(); store.all(scope)
    }
    private fun changed() { revision.update { it + 1 } }
}
