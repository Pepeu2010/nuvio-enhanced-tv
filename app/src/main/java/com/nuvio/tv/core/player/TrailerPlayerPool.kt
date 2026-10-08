package com.nuvio.tv.core.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Application-scoped singleton that holds a single ExoPlayer instance dedicated to
 * trailer/preview playback on the home screen.
 *
 * Creating and tearing down ExoPlayer for every poster focus is extremely expensive
 * (codec init, hardware decoder allocation). This pool keeps one instance alive and
 * reuses it across focus changes. The player is stopped and cleared between uses but
 * never released until the process dies or [release] is explicitly called.
 *
 * When the full-screen player needs hardware decoders, call [yield] to free
 * codec resources without destroying the instance. Call [reclaim] when returning to
 * the home screen to lazily rebuild if needed.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Singleton
class TrailerPlayerPool private constructor(
    private val context: Context,
    nativeAllocationPreference: Flow<Boolean>
) {
    @Inject constructor(@ApplicationContext context: Context, settings: PlayerSettingsDataStore) :
        this(context, settings.nuvioPerformanceModeEnabled)

    /** Native QA exercises the real pool without account/backend preference setup. */
    internal constructor(context: Context, forceNative: Boolean) : this(context, flowOf(forceNative))
    companion object {
        private const val TAG = "TrailerPlayerPool"
    }

    private var _player: ExoPlayer? = null
    private val yielded = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val owner = MutableStateFlow<Any?>(null)
    internal val activeOwner = owner.asStateFlow()
    private val generation = MutableStateFlow(0L)
    internal val availabilityGeneration = generation.asStateFlow()
    internal fun isOwner(token: Any): Boolean = owner.value === token

    @Volatile
    private var cachedForceNative: Boolean = false

    init {
        Thread {
            try {
                cachedForceNative = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    nativeAllocationPreference.first()
                }
            } catch (_: Exception) {
                cachedForceNative = false
            }
        }.start()
    }

    /**
     * Returns the shared trailer ExoPlayer, creating it lazily if needed.
     * Returns null only if [release] was called (process shutdown).
     */
    fun acquire(token: Any): ExoPlayer? {
        // Full playback retains its decoder handoff until an explicit reclaim.
        if (released.get() || yielded.get()) return null
        val player = _player ?: createPlayer().also { _player = it }
        if (!isOwner(token)) {
            stop()
            owner.value = token
        }
        return player
    }

    /** A disappearing old card cannot stop the preview that took over its slot. */
    fun stop(token: Any) {
        if (owner.compareAndSet(token, null)) stopPlayer()
    }

    /**
     * Stops playback and clears media but keeps the instance alive for reuse.
     * Call this when the trailer is no longer visible (poster lost focus, screen change).
     */
    fun stop() {
        owner.value = null
        stopPlayer()
    }

    private fun stopPlayer() {
        _player?.let { player ->
            runCatching {
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
            }
        }
    }

    /**
     * Releases codec resources so the detail-screen player can claim hardware decoders.
     * The ExoPlayer instance is released here; [reclaim] will create a fresh one.
     */
    fun yield() {
        if (yielded.compareAndSet(false, true)) {
            owner.value = null
            Log.d(TAG, "Yielding trailer player for detail playback")
            _player?.let { player ->
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
                runCatching { player.release() }
            }
            _player = null
            generation.value++
        }
    }

    /**
     * Re-creates the player after a [yield]. Safe to call multiple times.
     */
    fun reclaim() {
        if (released.get()) return
        if (yielded.compareAndSet(true, false)) {
            generation.value++
            Log.d(TAG, "Reclaiming trailer player")
            // Player will be lazily created on next acquire()
        }
    }

    /**
     * Permanently releases the player. Called on process death / Application.onTerminate.
     */
    fun release() {
        if (released.compareAndSet(false, true)) {
            owner.value = null
            _player?.let { player ->
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
                runCatching { player.release() }
            }
            _player = null
            generation.value++
        }
    }

    private fun createPlayer(): ExoPlayer {
        val forceNative = cachedForceNative
        Log.d(TAG, "Creating shared trailer ExoPlayer instance with forceNativeAllocation = $forceNative")
        val loadControlBuilder = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1_000,
                /* maxBufferMs = */ 10_000,
                /* bufferForPlaybackMs = */ 400,
                /* bufferForPlaybackAfterRebufferMs = */ 750
            )
            .setTargetBufferBytes(16 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
        if (forceNative) {
            val allocator = DefaultAllocator(
                /* trimOnReset = */ true,
                /* individualAllocationSize = */ 65536,
                /* initialAllocationCount = */ 0,
                /* forceNativeAllocation = */ true
            )
            loadControlBuilder.setAllocator(allocator)
        }
        val loadControl = loadControlBuilder.build()
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(1280, 720)
                    .setMaxVideoBitrate(4_000_000)
                    .setExceedVideoConstraintsIfNecessary(false)
            )
        }
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(
                DefaultBandwidthMeter.Builder(context)
                    .setInitialBitrateEstimate(4_000_000L)
                    .build()
            )
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                volume = 0f
            }
    }
}
