package com.nuvio.app.features.player

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One measurement of a decoded frame. [top] and [bottom] are the fraction of the frame height that
 * is black at that edge; [centerLuma] is the mean brightness (0..255) of the middle of the frame,
 * used to tell a genuinely dark scene apart from a frame that simply has bars.
 */
internal class BarSample(
    val top: Float,
    val bottom: Float,
    val centerLuma: Int,
)

private const val BAR_LUMA_MAX = 24

/**
 * Measures the black bars at the top and bottom of an ARGB [pixels] frame (row-major).
 *
 * A row counts as bar only if every pixel in it is near-black, so a single bright pixel ends the
 * bar - detection errs toward under-measuring, never over-measuring.
 */
internal fun analyzeFrameBars(
    pixels: IntArray,
    width: Int,
    height: Int,
    barLumaMax: Int = BAR_LUMA_MAX,
): BarSample {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return BarSample(0f, 0f, 0)

    val rowMax = IntArray(height)
    var centerSum = 0L
    var centerCount = 0
    val cx0 = width / 4
    val cx1 = width * 3 / 4
    val cy0 = height / 4
    val cy1 = height * 3 / 4

    for (y in 0 until height) {
        var rowPeak = 0
        for (x in 0 until width) {
            val p = pixels[y * width + x]
            val luma = (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
            if (luma > rowPeak) rowPeak = luma
            if (y in cy0 until cy1 && x in cx0 until cx1) {
                centerSum += luma
                centerCount++
            }
        }
        rowMax[y] = rowPeak
    }

    var top = 0
    while (top < height && rowMax[top] <= barLumaMax) top++
    var bottom = 0
    while (bottom < height && rowMax[height - 1 - bottom] <= barLumaMax) bottom++

    return BarSample(
        top = top.toFloat() / height,
        bottom = bottom.toFloat() / height,
        centerLuma = if (centerCount > 0) (centerSum / centerCount).toInt() else 0,
    )
}

internal enum class BarSampleVerdict { Usable, TooDark, BarsTooLarge, Asymmetric }

internal sealed interface BarDetectionResult {
    /** Still gathering evidence. */
    data object Pending : BarDetectionResult

    /** Confident: the frame has these bars. */
    data class Found(val bars: PlayerVideoBars) : BarDetectionResult

    /** Gave up after the sampling budget without ever seeing stable bars. */
    data object NoBars : BarDetectionResult
}

internal data class BarDetectorConfig(
    /** Consecutive valid samples that must agree. */
    val windowSize: Int = 5,
    /** Max difference between samples in the window, as a fraction of frame height. */
    val maxSpread: Float = 0.015f,
    /** Bars larger than this are almost certainly a fade or a dark frame, not letterboxing. */
    val maxBar: Float = 0.45f,
    /** Top and bottom bars are normally symmetric; a big mismatch means the sample is suspect. */
    val maxAsymmetry: Float = 0.02f,
    /** A frame whose middle is darker than this is a dark scene: edges can't be judged. */
    val minCenterLuma: Int = 10,
    /** Agreeing samples with less bar than this mean "no bars", which is not a result. */
    val minBar: Float = 0.005f,
    val fastSamples: Int = 30,
    val maxSamples: Int = 90,
    val fastIntervalMs: Long = 2_000L,
    val slowIntervalMs: Long = 10_000L,
)

/**
 * Turns a stream of [BarSample]s into a confident answer.
 *
 * Conservative by construction: unusable frames (dark, fade, asymmetric) are ignored, the answer
 * is the *smallest* bar seen in the agreeing window (so any error leaves a little bar rather than
 * cropping picture), and a window of zero-bar samples is not a result - it keeps sampling, because
 * a film can open in another aspect ratio (e.g. a 4:3 prologue) before its real one.
 */
internal class VideoBarDetector(private val config: BarDetectorConfig = BarDetectorConfig()) {
    var result: BarDetectionResult = BarDetectionResult.Pending
        private set

    private val window = ArrayDeque<BarSample>()
    private var total = 0

    val sampleCount: Int get() = total

    /** What the detector last saw, for diagnostics. */
    var lastSample: BarSample? = null
        private set
    var lastVerdict: BarSampleVerdict? = null
        private set
    val windowCount: Int get() = window.size
    val windowSpread: Float
        get() = if (window.isEmpty()) 0f else max(
            window.maxOf { it.top } - window.minOf { it.top },
            window.maxOf { it.bottom } - window.minOf { it.bottom },
        )

    fun nextIntervalMs(): Long =
        if (total < config.fastSamples) config.fastIntervalMs else config.slowIntervalMs

    fun add(sample: BarSample) {
        if (result != BarDetectionResult.Pending) return
        total++
        lastSample = sample
        val verdict = verdictOf(sample)
        lastVerdict = verdict

        if (verdict == BarSampleVerdict.Usable) {
            window.addLast(sample)
            if (window.size > config.windowSize) window.removeFirst()
            evaluateWindow()
        }

        if (result == BarDetectionResult.Pending && total >= config.maxSamples) {
            result = BarDetectionResult.NoBars
        }
    }

    private fun verdictOf(sample: BarSample): BarSampleVerdict = when {
        sample.centerLuma < config.minCenterLuma -> BarSampleVerdict.TooDark
        sample.top > config.maxBar || sample.bottom > config.maxBar -> BarSampleVerdict.BarsTooLarge
        abs(sample.top - sample.bottom) > config.maxAsymmetry -> BarSampleVerdict.Asymmetric
        else -> BarSampleVerdict.Usable
    }

    private fun evaluateWindow() {
        if (window.size < config.windowSize) return
        val minTop = window.minOf { it.top }
        val maxTop = window.maxOf { it.top }
        val minBottom = window.minOf { it.bottom }
        val maxBottom = window.maxOf { it.bottom }
        if (max(maxTop - minTop, maxBottom - minBottom) > config.maxSpread) return
        if (min(minTop, minBottom) < config.minBar) return
        result = BarDetectionResult.Found(PlayerVideoBars(topFraction = minTop, bottomFraction = minBottom))
    }
}
