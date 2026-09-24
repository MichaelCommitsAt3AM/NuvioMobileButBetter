package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.math.abs

/**
 * [Fast] lets the engine land on a nearby keyframe instead of decoding forward to the exact
 * target, which removes most of the post-seek stall. Use it for coarse user seeks (double-tap,
 * swipe, scrub); keep [Exact] where the position matters (resume, skip intro).
 */
enum class PlayerSeekPrecision { Exact, Fast }

/** How far a [PlayerSeekPrecision.Fast] seek may land from its target, either side. */
internal const val PlayerFastSeekToleranceMs = 3_000L

/**
 * Where a [PlayerSeekPrecision.Fast] seek from [fromMs] to [targetMs] may land. The side facing
 * [fromMs] is capped at half the seek distance, so a short forward seek can never land behind
 * where it started (and vice versa).
 */
internal fun fastSeekLandingRangeMs(fromMs: Long, targetMs: Long): LongRange {
    val towardOriginMs = minOf(PlayerFastSeekToleranceMs, abs(targetMs - fromMs) / 2)
    return if (targetMs >= fromMs) {
        (targetMs - towardOriginMs)..(targetMs + PlayerFastSeekToleranceMs)
    } else {
        (targetMs - PlayerFastSeekToleranceMs)..(targetMs + towardOriginMs)
    }
}

interface PlayerEngineController {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun seekTo(positionMs: Long, precision: PlayerSeekPrecision) = seekTo(positionMs)
    fun seekBy(offsetMs: Long, precision: PlayerSeekPrecision) = seekBy(offsetMs)
    fun retry()
    fun setPlaybackSpeed(speed: Float)
    fun setMuted(muted: Boolean) {}
    fun getAudioTracks(): List<AudioTrack>
    fun getSubtitleTracks(): List<SubtitleTrack>
    fun applyAudioLanguagePreferences(languages: List<String>)
    fun selectAudioTrack(index: Int)
    fun selectSubtitleTrack(index: Int)
    fun setSubtitleUri(url: String)
    fun clearExternalSubtitle()
    fun clearExternalSubtitleAndSelect(trackIndex: Int)
    fun applySubtitleStyle(style: SubtitleStyleState) {}
    fun applySubtitlePreferences(
        preferredLanguage: String,
        secondaryPreferredLanguage: String? = null,
        useForcedSubtitles: Boolean,
        autoSelectionApplied: Boolean,
        hasActiveSubtitle: Boolean,
        useCustomSubtitles: Boolean = false,
    ) {}
    fun setSubtitleDelayMs(delayMs: Int) {}
    fun configureIosVideoOutput(settings: PlayerSettingsUiState) {}
    fun updateNowPlayingMetadata(info: PlayerNowPlayingInfo) {}
    fun clearNowPlayingInfo() {}
}

internal fun sanitizePlaybackHeaders(headers: Map<String, String>?): Map<String, String> {
    val rawHeaders = headers ?: return emptyMap()
    if (rawHeaders.isEmpty()) return emptyMap()

    val sanitized = LinkedHashMap<String, String>(rawHeaders.size)
    rawHeaders.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        if (key.equals("Range", ignoreCase = true)) return@forEach
        sanitized[key] = value
    }
    return sanitized
}

internal fun sanitizePlaybackResponseHeaders(headers: Map<String, String>?): Map<String, String> {
    val rawHeaders = headers ?: return emptyMap()
    if (rawHeaders.isEmpty()) return emptyMap()

    val sanitized = LinkedHashMap<String, String>(rawHeaders.size)
    rawHeaders.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        sanitized[key] = value
    }
    return sanitized
}

@Composable
expect fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String? = null,
    sourceHeaders: Map<String, String> = emptyMap(),
    sourceResponseHeaders: Map<String, String> = emptyMap(),
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    streamType: String? = null,
    useYoutubeChunkedPlayback: Boolean = false,
    modifier: Modifier = Modifier,
    playWhenReady: Boolean = true,
    initialPositionMs: Long? = null,
    initialPositionRequestKey: String? = null,
    resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    /** Extra scale applied when [resizeMode] is [PlayerResizeMode.Auto]; `1f` means plain Fit. */
    autoZoom: Float = 1f,
    /** Whether the engine should measure black bars baked into the frame (main player only). */
    detectVideoBars: Boolean = false,
    useNativeController: Boolean = false,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit = { _, _ -> },
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
)
