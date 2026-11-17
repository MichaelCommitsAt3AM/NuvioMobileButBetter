package com.nuvio.app.features.details

/**
 * Fork-only: tuning values for the trailer behind the phone details hero.
 */
object HeroTrailerPlaybackConfig {
    /** Wait this long after the screen opens before the trailer starts. */
    const val AUTOPLAY_DELAY_MS = 1_500L

    /** Scrolling past this share of the hero height pauses the trailer and brings the artwork back. */
    const val SCROLL_PAUSE_FRACTION = 0.12f

    /** Scrolling back above this share resumes it. Lower than the pause point so it cannot flicker. */
    const val SCROLL_RESUME_FRACTION = 0.06f

    /**
     * The whole poster-to-trailer transition, in both directions: the poster shrinks into the
     * frame's rectangle, then the video crossfades in over it (reversed on the way out).
     */
    const val FADE_DURATION_MS = 750

    /** Share of the transition spent shrinking the poster into the frame. */
    const val MORPH_FRACTION = 0.6f

    /** Point in the transition where the video starts fading in over the shrunk poster. */
    const val VIDEO_FADE_START_FRACTION = 0.45f

    /** Height of the progress line along the bottom edge of the frame. */
    const val PROGRESS_LINE_HEIGHT_DP = 2.5f

    /** Gap between the status bar and the frame, leaving room for the back button. */
    const val FRAME_TOP_OFFSET_DP = 56

    /** Opacity of the black behind the pill and the mute button. */
    const val CONTROL_BACKGROUND_ALPHA = 0.55f

    /** Tapped player controls hide again after this long while playing. */
    const val CONTROLS_AUTO_HIDE_MS = 3_000L

    /** Height of the touch area around the progress line, for seeking. */
    const val SEEK_BAR_TOUCH_HEIGHT_DP = 28

    /** Diameter of the centre play/pause button. */
    const val CENTER_BUTTON_DP = 56

    /** How often playback progress is reported to the controls. */
    const val PROGRESS_INTERVAL_MS = 250L
}

enum class HeroTrailerPlaybackState {
    /** Nothing playing yet: waiting for the trailer to load, or blocked. */
    Idle,
    Playing,
    PausedByScroll,
    Ended,
}

/** The trailer first has data to show. Starts paused if the screen is already scrolled. */
internal fun HeroTrailerPlaybackState.onReady(scrollFraction: Float): HeroTrailerPlaybackState =
    if (this == HeroTrailerPlaybackState.Idle) {
        if (scrollFraction > HeroTrailerPlaybackConfig.SCROLL_PAUSE_FRACTION) {
            HeroTrailerPlaybackState.PausedByScroll
        } else {
            HeroTrailerPlaybackState.Playing
        }
    } else {
        this
    }

/** Pauses past the pause point and resumes (from where it stopped) below the resume point. */
internal fun HeroTrailerPlaybackState.onScroll(scrollFraction: Float): HeroTrailerPlaybackState =
    when (this) {
        HeroTrailerPlaybackState.Playing ->
            if (scrollFraction > HeroTrailerPlaybackConfig.SCROLL_PAUSE_FRACTION) {
                HeroTrailerPlaybackState.PausedByScroll
            } else {
                this
            }
        HeroTrailerPlaybackState.PausedByScroll ->
            if (scrollFraction < HeroTrailerPlaybackConfig.SCROLL_RESUME_FRACTION) {
                HeroTrailerPlaybackState.Playing
            } else {
                this
            }
        else -> this
    }

/** The trailer reached the end, or failed to load. */
internal fun HeroTrailerPlaybackState.onEnded(): HeroTrailerPlaybackState = HeroTrailerPlaybackState.Ended

/** Whether the player should be running (before the autoplay delay and other screen conditions). */
internal val HeroTrailerPlaybackState.wantsPlayback: Boolean
    get() = this == HeroTrailerPlaybackState.Idle || this == HeroTrailerPlaybackState.Playing

/** Whether the trailer frame is on screen instead of the poster. */
internal val HeroTrailerPlaybackState.showsTrailer: Boolean
    get() = this == HeroTrailerPlaybackState.Playing

/** A request to jump the trailer to [positionMs]; a new [token] makes the same position count again. */
data class HeroTrailerSeekRequest(val positionMs: Long, val token: Int)
