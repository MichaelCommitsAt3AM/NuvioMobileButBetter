package com.nuvio.app.features.player

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.nuvio.app.R

/** What the bar-detection loop is doing right now; written by the loop, read by the overlay. */
internal class PlayerBarDebugInfo {
    var state: String = "waiting for frames"
    var nextIntervalMs: Long = 0L
    var samples: Int = 0
    var captureFailures: Int = 0
    var windowCount: Int = 0
    var windowSpread: Float = 0f
    var lastTop: Float = Float.NaN
    var lastBottom: Float = Float.NaN
    var lastLuma: Int = -1
    var lastVerdict: String = "-"
    var bars: PlayerVideoBars? = null

    fun recordFrom(detector: VideoBarDetector) {
        samples = detector.sampleCount
        windowCount = detector.windowCount
        windowSpread = detector.windowSpread
        detector.lastSample?.let {
            lastTop = it.top
            lastBottom = it.bottom
            lastLuma = it.centerLuma
        }
        lastVerdict = detector.lastVerdict?.name ?: "-"
    }
}

internal class PlayerBarDebugSources(
    val info: () -> PlayerBarDebugInfo,
    val frameSize: () -> Pair<Int, Int>,
    val autoZoom: () -> Float,
    val resizeMode: () -> PlayerResizeMode,
)

private const val REFRESH_MS = 500L

/**
 * Shows the auto-resize internals on top of the video: viewport and frame sizes, what the bar
 * detector last measured, and why Auto is or isn't being offered.
 *
 * Debuggable builds only - it is inert in release builds, so it can stay in the code.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerView.installBarDebugOverlay(sources: PlayerBarDebugSources) {
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!debuggable || getTag(R.id.player_bar_debug_overlay) != null) return

    val overlay = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.MONOSPACE
        setTextColor(Color.WHITE)
        setBackgroundColor(0xAA000000.toInt())
        val padding = (6 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        isClickable = false
        isFocusable = false
    }
    val margin = (12 * resources.displayMetrics.density).toInt()
    addView(
        overlay,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ).apply {
            leftMargin = margin
            topMargin = margin
        },
    )
    setTag(R.id.player_bar_debug_overlay, overlay)

    val handler = Handler(Looper.getMainLooper())
    val refresh = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            overlay.text = describe(sources)
            handler.postDelayed(this, REFRESH_MS)
        }
    }
    handler.post(refresh)
}

private fun pct(fraction: Float) = if (fraction.isNaN()) "--" else "%.1f%%".format(fraction * 100f)

private fun reasonText(reason: AutoZoomReason) = when (reason) {
    AutoZoomReason.Offered -> "yes"
    AutoZoomReason.NoBars -> "no - no bars detected"
    AutoZoomReason.BarsTooThin -> "no - bars too thin"
    AutoZoomReason.PictureWiderThanViewport -> "no - picture wider than screen"
    AutoZoomReason.InvalidInput -> "no - waiting for sizes"
}

private fun PlayerView.describe(sources: PlayerBarDebugSources): String {
    val info = sources.info()
    val (frameWidth, frameHeight) = sources.frameSize()
    val viewportAspect = if (height > 0) width.toFloat() / height else 0f
    val frameAspect = if (frameHeight > 0) frameWidth.toFloat() / frameHeight else 0f
    val decision = evaluateAutoZoom(info.bars, frameWidth, frameHeight, width, height)

    return buildString {
        appendLine("AUTO DEBUG (debug build)")
        appendLine("viewport $width x $height   V=${"%.3f".format(viewportAspect)}")
        appendLine("frame    $frameWidth x $frameHeight   F=${"%.3f".format(frameAspect)}")
        appendLine("mode     ${sources.resizeMode().name}   zoom applied x${"%.3f".format(sources.autoZoom())}")
        appendLine("detector ${info.state}")
        appendLine(
            "samples  ${info.samples}  (window ${info.windowCount}/5, spread ${pct(info.windowSpread)}, " +
                "next ${info.nextIntervalMs / 1000}s)",
        )
        appendLine("last     top ${pct(info.lastTop)}  bot ${pct(info.lastBottom)}  luma ${info.lastLuma}  ${info.lastVerdict}")
        info.bars?.let { appendLine("bars     top ${pct(it.topFraction)}  bottom ${pct(it.bottomFraction)}") }
        if (info.captureFailures > 0) appendLine("capture failures ${info.captureFailures}")
        append("offer    ${reasonText(decision.reason)}")
        if (decision.reason == AutoZoomReason.Offered) {
            append("   zoom x${"%.3f".format(decision.zoom)}   P=${"%.3f".format(decision.pictureAspect)}")
        } else if (decision.pictureAspect > 0f) {
            append("   P=${"%.3f".format(decision.pictureAspect)}")
        }
    }
}
