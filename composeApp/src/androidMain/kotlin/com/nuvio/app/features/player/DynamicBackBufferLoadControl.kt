package com.nuvio.app.features.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * [DefaultLoadControl] with an explicit RAM budget split between a bitrate-aware back buffer and
 * the forward buffer, so rewinding ~10 s (hearing a line again) stays instant.
 *
 * ExoPlayer stops loading once `allocator.getTotalBytesAllocated()` reaches the target, and that
 * total includes back-buffer samples it is still retaining. So the target is sized from the real
 * heap limit, the back buffer gets its 10-15 s window first, and it is only shortened when that
 * would leave the forward buffer less than [BufferBudget.MIN_FORWARD_BYTES]. Rewinds past the RAM
 * window fall through to [PlayerDiskCache] instead of the network.
 *
 * The bytes a second of media costs comes from the file itself where possible
 * ([updateEstimatedBitrate]: size / duration), since containers like MKV rarely declare a bitrate.
 *
 * On ActivityManager.isLowRamDevice() devices the back buffer stays at ExoPlayer's original zero:
 * those devices are the most exposed to OOM kills, and PlayerDiskCache still removes the network
 * round-trip on rewind for them.
 */
internal class DynamicBackBufferLoadControl(
    minBufferMs: Int,
    maxBufferMs: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    private val isLowRamDevice: Boolean,
    private val heapLimitBytes: Long = Runtime.getRuntime().maxMemory(),
) : DefaultLoadControl(
    DefaultAllocator(true, DEFAULT_BUFFER_SEGMENT_SIZE),
    minBufferMs,
    maxBufferMs,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    // Unset, so ExoPlayer asks calculateTargetBufferBytes() on every track selection.
    C.LENGTH_UNSET,
    DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
    /* backBufferDurationMs= */ 0,
    /* retainBackBufferFromKeyframe= */ true,
) {
    private val targetBufferBytes = BufferBudget.targetBufferBytes(heapLimitBytes, isLowRamDevice)

    @Volatile
    private var backBufferDurationUs: Long = secondsToUs(
        BufferBudget.backBufferSeconds(bitrateBitsPerSecond = null, targetBufferBytes, isLowRamDevice),
    )

    @Volatile
    private var declaredBitrate: Int? = null

    @Volatile
    private var estimatedBitrate: Int? = null

    /** Call whenever the selected video format becomes known/changes to size the RAM back buffer. */
    fun updateBackBufferForVideoFormat(format: Format?) {
        declaredBitrate = format?.declaredBitrate()
        resizeBackBuffer()
    }

    /**
     * Whole-file average bitrate (size / duration). Preferred over the declared video bitrate: it
     * counts audio too, which is what the buffer actually holds, and it exists for MKV.
     */
    fun updateEstimatedBitrate(contentLengthBytes: Long, durationMs: Long) {
        val bitrate = BufferBudget.estimateBitrate(contentLengthBytes, durationMs) ?: return
        if (bitrate == estimatedBitrate) return
        estimatedBitrate = bitrate
        resizeBackBuffer()
        Log.i(
            TAG,
            "loadBudget estimatedMbps=${bitrate / 1_000_000.0} fileMb=${contentLengthBytes / MB} " +
                "backSec=${backBufferDurationUs / 1_000_000.0}",
        )
    }

    private fun resizeBackBuffer() {
        backBufferDurationUs = secondsToUs(
            BufferBudget.backBufferSeconds(estimatedBitrate ?: declaredBitrate, targetBufferBytes, isLowRamDevice),
        )
    }

    override fun calculateTargetBufferBytes(trackSelectionArray: Array<ExoTrackSelection?>): Int {
        val videoFormat = trackSelectionArray.firstNotNullOfOrNull { selection ->
            selection?.selectedFormat?.takeIf { MimeTypes.isVideo(it.sampleMimeType) }
        }
        updateBackBufferForVideoFormat(videoFormat)
        Log.i(
            TAG,
            "loadBudget heapMb=${heapLimitBytes / MB} targetMb=${targetBufferBytes / MB} " +
                "videoMbps=${videoFormat?.declaredBitrate()?.let { it / 1_000_000.0 }} " +
                "backSec=${backBufferDurationUs / 1_000_000.0} lowRam=$isLowRamDevice",
        )
        return targetBufferBytes
    }

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = backBufferDurationUs

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = true

    companion object {
        private const val TAG = "NuvioPlayerDiag"
        private const val DEFAULT_BUFFER_SEGMENT_SIZE = 65_536
        private const val MB = 1024 * 1024

        private fun secondsToUs(seconds: Double): Long = (seconds * 1_000_000L).toLong()

        private fun Format.declaredBitrate(): Int? =
            listOf(bitrate, averageBitrate, peakBitrate).firstOrNull { it != Format.NO_VALUE && it > 0 }
    }
}

/** Pure sizing rules for [DynamicBackBufferLoadControl], kept separate so they can be unit tested. */
internal object BufferBudget {
    /** Share of the Java heap limit the player's media buffer may occupy (the app sets no largeHeap). */
    const val HEAP_SHARE = 0.4
    const val LOW_RAM_HEAP_SHARE = 0.25
    const val MIN_TARGET_BYTES = 32 * 1024 * 1024
    const val MAX_TARGET_BYTES = 256 * 1024 * 1024

    /** Forward buffer the back buffer may never eat into; below this, the back window shrinks. */
    const val MIN_FORWARD_BYTES = 32 * 1024 * 1024

    // In-RAM back buffer window before the byte cap: bigger for low-bitrate content, smaller for
    // high-bitrate remuxes.
    const val MIN_BACK_BUFFER_SECONDS = 10.0
    const val MAX_BACK_BUFFER_SECONDS = 15.0
    private const val LOW_BITRATE_MBPS = 15.0
    private const val HIGH_BITRATE_MBPS = 60.0

    // Until the file's real bitrate is known, budget as if it were a remux so an unknown
    // high-bitrate file can't squeeze the forward buffer below its floor.
    const val ASSUMED_UNKNOWN_BITRATE_MBPS = 40.0

    fun targetBufferBytes(heapLimitBytes: Long, isLowRamDevice: Boolean): Int {
        val share = if (isLowRamDevice) LOW_RAM_HEAP_SHARE else HEAP_SHARE
        return (heapLimitBytes * share).toLong()
            .coerceIn(MIN_TARGET_BYTES.toLong(), MAX_TARGET_BYTES.toLong())
            .toInt()
    }

    fun backBufferSeconds(bitrateBitsPerSecond: Int?, targetBufferBytes: Int, isLowRamDevice: Boolean): Double {
        if (isLowRamDevice) return 0.0
        val mbps = bitrateBitsPerSecond?.let { it / 1_000_000.0 }
        val wanted = when {
            mbps == null -> MIN_BACK_BUFFER_SECONDS
            mbps <= LOW_BITRATE_MBPS -> MAX_BACK_BUFFER_SECONDS
            mbps >= HIGH_BITRATE_MBPS -> MIN_BACK_BUFFER_SECONDS
            else -> {
                val t = (mbps - LOW_BITRATE_MBPS) / (HIGH_BITRATE_MBPS - LOW_BITRATE_MBPS)
                MAX_BACK_BUFFER_SECONDS - t * (MAX_BACK_BUFFER_SECONDS - MIN_BACK_BUFFER_SECONDS)
            }
        }
        val bytesPerSecond = (mbps ?: ASSUMED_UNKNOWN_BITRATE_MBPS) * 1_000_000.0 / 8.0
        val backBytesAvailable = (targetBufferBytes - MIN_FORWARD_BYTES).coerceAtLeast(0)
        return minOf(wanted, backBytesAvailable / bytesPerSecond)
    }

    /** Average bitrate of a whole file, or null when either input is unknown or implausible. */
    fun estimateBitrate(contentLengthBytes: Long, durationMs: Long): Int? {
        if (contentLengthBytes <= 0L || durationMs < MIN_ESTIMATE_DURATION_MS) return null
        val bitsPerSecond = contentLengthBytes * 8.0 * 1000.0 / durationMs
        return bitsPerSecond.takeIf { it in 100_000.0..Int.MAX_VALUE.toDouble() }?.toInt()
    }

    // Short clips (error placeholders, trailers) give a meaningless average.
    private const val MIN_ESTIMATE_DURATION_MS = 60_000L
}
