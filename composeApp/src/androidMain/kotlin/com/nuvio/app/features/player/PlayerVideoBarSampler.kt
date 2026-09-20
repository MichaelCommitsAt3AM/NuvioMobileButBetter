package com.nuvio.app.features.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "NuvioPlayerBars"
private const val SAMPLE_WIDTH_PX = 480

/**
 * Reads one decoded frame straight from the video surface and measures its black bars.
 *
 * This deliberately reads the surface buffer, not the screen: it is the whole encoded frame no
 * matter how the view is zoomed or clipped (verified on device under Fit, a larger content frame
 * and Media3's own zoom mode), so the bars stay measurable while Auto or Zoom is applied.
 *
 * Returns null when the surface has nothing usable yet or the copy fails.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal suspend fun PlayerView.sampleFrameBars(): BarSample? {
    val surfaceView = videoSurfaceView as? SurfaceView ?: return null
    if (surfaceView.width <= 0 || surfaceView.height <= 0 || !surfaceView.holder.surface.isValid) return null

    val width = SAMPLE_WIDTH_PX
    val height = (width.toFloat() * surfaceView.height / surfaceView.width).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    val copied = suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(surfaceView, bitmap, { result ->
                if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
            }, Handler(Looper.getMainLooper()))
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "PixelCopy rejected the surface", error)
            if (continuation.isActive) continuation.resume(false)
        }
    }
    if (!copied) {
        bitmap.recycle()
        return null
    }

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    bitmap.recycle()
    return analyzeFrameBars(pixels, width, height)
}
