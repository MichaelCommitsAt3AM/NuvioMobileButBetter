package com.nuvio.app.features.player

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.app.R
import kotlin.math.roundToInt

/**
 * Scales the video by [zoom] on top of Fit, keeping the picture centred and undistorted.
 *
 * Only the video's content frame is resized - it is laid out larger than the PlayerView and the
 * PlayerView clips the overflow. Subtitles are drawn by sibling views in the PlayerView, so they
 * stay where they are instead of being scaled off-screen with the picture.
 *
 * A real layout change (not a render transform) is used on purpose: it gives the SurfaceView an
 * honest new size, which composes correctly, unlike scaling the surface with a transform.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerView.setContentZoom(zoom: Float) {
    setTag(R.id.player_content_zoom, zoom)
    ensureContentZoomLayoutListener()
    applyContentZoom()
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerView.applyContentZoom() {
    val zoom = getTag(R.id.player_content_zoom) as? Float ?: 1f
    val frame = findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame) ?: return
    val params = frame.layoutParams as? FrameLayout.LayoutParams ?: return

    val zoomed = zoom > 1f && width > 0 && height > 0
    val targetWidth = if (zoomed) (width * zoom).roundToInt() else FrameLayout.LayoutParams.MATCH_PARENT
    val targetHeight = if (zoomed) (height * zoom).roundToInt() else FrameLayout.LayoutParams.MATCH_PARENT
    if (params.width == targetWidth && params.height == targetHeight && params.gravity == Gravity.CENTER) return

    params.width = targetWidth
    params.height = targetHeight
    params.gravity = Gravity.CENTER
    frame.layoutParams = params
}

/** The zoom is sized from the PlayerView, so it must follow rotation, split-screen and folds. */
private fun PlayerView.ensureContentZoomLayoutListener() {
    if (getTag(R.id.player_content_zoom_listener) != null) return
    val listener = View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        val sizeChanged = (right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)
        if (sizeChanged) (view as? PlayerView)?.applyContentZoom()
    }
    addOnLayoutChangeListener(listener)
    setTag(R.id.player_content_zoom_listener, listener)
}
