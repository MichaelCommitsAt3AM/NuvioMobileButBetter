package com.nuvio.app.features.details.components

import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.app.features.player.PlayerDiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fork-only: keeps the details hero's trailer player alive while the hero is scrolled off screen.
 *
 * The hero is a list item, so scrolling far enough drops it from composition. Releasing the player
 * there meant scrolling back rebuilt it and re-buffered the trailer from scratch. Instead the
 * surface hands its player back here when it leaves composition; if a surface asks for the same
 * trailer again within [GRACE_MS] it gets the same player back, already buffered and at the same
 * position. A different trailer, or the grace period running out, releases it.
 *
 * Main thread only (composition and the main-dispatcher timer).
 */
internal object HeroTrailerPlayerPool {
    private const val GRACE_MS = 45_000L

    private class Entry(val key: String, val player: ExoPlayer) {
        var releaseJob: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var entry: Entry? = null

    /** Returns the pooled player for [key], or builds one with [create]. A different key frees the old player first. */
    fun acquire(key: String, create: () -> ExoPlayer): ExoPlayer {
        val current = entry
        if (current != null && current.key == key) {
            current.releaseJob?.cancel()
            current.releaseJob = null
            return current.player
        }
        current?.let(::destroy)
        val player = create()
        PlayerDiskCache.onPlaybackStarted()
        entry = Entry(key, player)
        return player
    }

    /** The surface left composition: pause, and release later unless the same trailer comes back. */
    fun release(key: String) {
        val current = entry?.takeIf { it.key == key } ?: return
        current.player.pause()
        current.releaseJob?.cancel()
        current.releaseJob = scope.launch {
            delay(GRACE_MS)
            if (entry === current) {
                entry = null
                destroy(current)
            }
        }
    }

    private fun destroy(target: Entry) {
        target.releaseJob?.cancel()
        runCatching {
            target.player.stop()
            target.player.release()
        }
        PlayerDiskCache.onPlaybackStopped()
        if (entry === target) entry = null
    }
}
