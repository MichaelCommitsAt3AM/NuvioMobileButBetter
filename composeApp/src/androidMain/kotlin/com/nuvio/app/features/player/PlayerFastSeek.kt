package com.nuvio.app.features.player

import androidx.media3.exoplayer.SeekParameters

/**
 * Seek parameters for a [PlayerSeekPrecision.Fast] seek from [fromMs] to [targetMs].
 *
 * An exact seek makes the decoder decode every frame from the previous keyframe up to the
 * target before anything shows, which on long-GOP HEVC remuxes is most of the post-seek stall
 * even when the target is already buffered. Landing on a keyframe skips that.
 *
 * The window comes from [fastSeekLandingRangeMs], so sparse keyframes fall back to an exact seek
 * instead of jumping far, and skip-interval marking agrees with where the seek can land.
 */
internal fun fastSeekParameters(fromMs: Long, targetMs: Long): SeekParameters {
    val landingRangeMs = fastSeekLandingRangeMs(fromMs, targetMs)
    return SeekParameters(
        /* toleranceBeforeUs= */ (targetMs - landingRangeMs.first) * 1_000L,
        /* toleranceAfterUs= */ (landingRangeMs.last - targetMs) * 1_000L,
    )
}
