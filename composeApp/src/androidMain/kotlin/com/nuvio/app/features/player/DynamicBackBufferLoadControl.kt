package com.nuvio.app.features.player

import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * [DefaultLoadControl] that keeps only a small, bitrate-aware amount of
 * already-played media in RAM so short rewinds are instant, without paying
 * the full memory cost of that window on high-bitrate sources (e.g. Blu-ray
 * remuxes). Rewinds beyond this window fall through to [PlayerDiskCache]
 * instead of the network.
 *
 * The back buffer duration isn't gated by targetBufferBytes (ExoPlayer
 * retains it regardless of the forward-buffer byte budget), so it's kept
 * deliberately small and bitrate-scaled here rather than fixed.
 *
 * On ActivityManager.isLowRamDevice() devices this reverts to ExoPlayer's
 * original zero-back-buffer behavior: those devices are the most exposed to
 * OOM kills and the least predictable in worst-case content bitrate, so
 * memory safety wins over instant rewind there. PlayerDiskCache still
 * removes the network round-trip on rewind for them, just with a small
 * disk-read + reseek-to-keyframe cost instead of zero latency.
 */
internal class DynamicBackBufferLoadControl(
    minBufferMs: Int,
    maxBufferMs: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    targetBufferBytes: Int,
    private val isLowRamDevice: Boolean,
) : DefaultLoadControl(
    DefaultAllocator(true, DEFAULT_BUFFER_SEGMENT_SIZE),
    minBufferMs,
    maxBufferMs,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    targetBufferBytes,
    DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
    /* backBufferDurationMs= */ (MIN_BACK_BUFFER_SECONDS * 1000).toInt(),
    /* retainBackBufferFromKeyframe= */ true,
) {
    @Volatile
    private var backBufferDurationUs: Long = secondsToUs(
        if (isLowRamDevice) LOW_RAM_BACK_BUFFER_SECONDS else MIN_BACK_BUFFER_SECONDS
    )

    /** Call whenever the selected video format becomes known/changes to size the RAM back buffer. */
    fun updateBackBufferForVideoFormat(format: Format?) {
        val seconds = if (isLowRamDevice) {
            LOW_RAM_BACK_BUFFER_SECONDS
        } else {
            secondsForBitrate(format?.declaredBitrate())
        }
        backBufferDurationUs = secondsToUs(seconds)
    }

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = backBufferDurationUs

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = true

    companion object {
        private const val DEFAULT_BUFFER_SEGMENT_SIZE = 65_536

        // In-RAM back buffer window: bigger for low-bitrate content, smaller for
        // high-bitrate remuxes so worst-case memory stays bounded.
        private const val MIN_BACK_BUFFER_SECONDS = 10.0
        private const val MAX_BACK_BUFFER_SECONDS = 15.0
        // Matches ExoPlayer's original DEFAULT_BACK_BUFFER_DURATION_MS (0):
        // PlayerDiskCache already removes the network round-trip on rewind,
        // so low-RAM devices don't need any RAM back buffer on top of that.
        private const val LOW_RAM_BACK_BUFFER_SECONDS = 0.0
        private const val LOW_BITRATE_MBPS = 15.0
        private const val HIGH_BITRATE_MBPS = 60.0

        private fun secondsToUs(seconds: Double): Long = (seconds * 1_000_000L).toLong()

        private fun Format.declaredBitrate(): Int? =
            listOf(bitrate, averageBitrate, peakBitrate).firstOrNull { it != Format.NO_VALUE && it > 0 }

        private fun secondsForBitrate(bitrateBitsPerSecond: Int?): Double {
            if (bitrateBitsPerSecond == null) return MIN_BACK_BUFFER_SECONDS
            val mbps = bitrateBitsPerSecond / 1_000_000.0
            return when {
                mbps <= LOW_BITRATE_MBPS -> MAX_BACK_BUFFER_SECONDS
                mbps >= HIGH_BITRATE_MBPS -> MIN_BACK_BUFFER_SECONDS
                else -> {
                    val t = (mbps - LOW_BITRATE_MBPS) / (HIGH_BITRATE_MBPS - LOW_BITRATE_MBPS)
                    MAX_BACK_BUFFER_SECONDS - t * (MAX_BACK_BUFFER_SECONDS - MIN_BACK_BUFFER_SECONDS)
                }
            }
        }
    }
}
