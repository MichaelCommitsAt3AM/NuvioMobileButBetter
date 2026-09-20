package com.nuvio.app.features.player

import kotlin.math.min

/**
 * Smallest zoom worth offering. 1.06 is roughly 3% of black per side; below that (a 1.85:1
 * picture in a 16:9 frame is ~2%) the gain isn't noticeable enough to bother the user.
 */
internal const val AUTO_MIN_ZOOM = 1.06f

/**
 * When true, Auto is only offered if removing the bars can't crop the sides - i.e. the picture is
 * no wider than the viewport. Wider pictures (scope films on a 20:9 phone) are already well served
 * by Fit. Flip to false to also offer the "fill the width, no side bars" zoom for those.
 */
internal const val AUTO_ONLY_WHEN_PICTURE_NARROWER_THAN_VIEWPORT = true

private const val NARROWER_TOLERANCE = 1.01f
private const val MIN_PICTURE_HEIGHT_FRACTION = 0.1f

internal enum class AutoZoomReason {
    /** Auto is worth offering; [AutoZoomDecision.zoom] is above 1. */
    Offered,
    NoBars,
    BarsTooThin,
    PictureWiderThanViewport,
    InvalidInput,
}

internal data class AutoZoomDecision(
    val zoom: Float,
    val reason: AutoZoomReason,
    /** Aspect ratio of the picture inside the frame, or 0 when it could not be worked out. */
    val pictureAspect: Float = 0f,
)

/**
 * How much to scale the video, on top of Fit, so the picture fills the viewport instead of the
 * frame - or a zoom of `1f` with the [AutoZoomReason] it isn't offered.
 *
 * Fit letterboxes the whole *frame*, including any black baked into it. With the frame at aspect
 * `F`, the picture inside it at `P`, and the viewport at `V`:
 *
 *     zoom = min(P, V) / min(F, V)
 *
 * The `min` against `V` is what guarantees this never crops: the picture is scaled to fill the
 * viewport height only as far as it still fits the viewport width.
 *
 * The bars are used at their *smaller* side, so any measurement error leaves a sliver of bar
 * instead of cutting into the picture.
 */
internal fun evaluateAutoZoom(
    bars: PlayerVideoBars?,
    frameWidth: Int,
    frameHeight: Int,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
): AutoZoomDecision {
    if (bars == null) return AutoZoomDecision(1f, AutoZoomReason.NoBars)
    if (frameWidth <= 0 || frameHeight <= 0) return AutoZoomDecision(1f, AutoZoomReason.InvalidInput)
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return AutoZoomDecision(1f, AutoZoomReason.InvalidInput)

    val bar = min(bars.topFraction, bars.bottomFraction)
    if (bar <= 0f) return AutoZoomDecision(1f, AutoZoomReason.NoBars)
    val pictureHeightFraction = 1f - 2f * bar
    if (pictureHeightFraction < MIN_PICTURE_HEIGHT_FRACTION) {
        return AutoZoomDecision(1f, AutoZoomReason.InvalidInput)
    }

    val frameAspect = frameWidth.toFloat() / frameHeight
    val viewportAspect = viewportWidthPx.toFloat() / viewportHeightPx
    val pictureAspect = frameAspect / pictureHeightFraction

    if (AUTO_ONLY_WHEN_PICTURE_NARROWER_THAN_VIEWPORT &&
        pictureAspect > viewportAspect * NARROWER_TOLERANCE
    ) {
        return AutoZoomDecision(1f, AutoZoomReason.PictureWiderThanViewport, pictureAspect)
    }

    val zoom = min(pictureAspect, viewportAspect) / min(frameAspect, viewportAspect)
    return if (zoom >= AUTO_MIN_ZOOM) {
        AutoZoomDecision(zoom, AutoZoomReason.Offered, pictureAspect)
    } else {
        AutoZoomDecision(1f, AutoZoomReason.BarsTooThin, pictureAspect)
    }
}

internal fun resolveAutoZoom(
    bars: PlayerVideoBars?,
    frameWidth: Int,
    frameHeight: Int,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
): Float = evaluateAutoZoom(bars, frameWidth, frameHeight, viewportWidthPx, viewportHeightPx).zoom
