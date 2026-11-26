package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

/**
 * Error the Android engine reports when the video is Dolby Vision this device cannot show
 * (no Dolby Vision decoder and no HDR10/SDR base layer, e.g. profile 5). It is never shown as is.
 */
internal const val DOLBY_VISION_UNSUPPORTED_ERROR = "DOLBY_VISION_UNSUPPORTED"

private const val DOLBY_VISION_FALLBACK_MAX_SOURCES = 3
private const val DOLBY_VISION_SOURCE_SEARCH_TIMEOUT_MS = 30_000L
private const val DOLBY_VISION_SWITCH_NOTICE_MS = 2_500L

private val log = Logger.withTag("PlayerDolbyVision")

internal fun isUnsupportedDolbyVisionError(message: String?): Boolean =
    message?.startsWith(DOLBY_VISION_UNSUPPORTED_ERROR) == true

/**
 * Handles the engine finding Dolby Vision this device cannot show: whether auto selection or the
 * user picked the source, it moves on to a compatible one and says so in the player.
 */
internal fun PlayerScreenRuntime.handleUnsupportedDolbyVision() {
    val failedUrl = activeSourceUrl
    if (dolbyVisionBlockedSourceUrl == failedUrl && (dolbyVisionFallbackJob?.isActive == true || errorMessage != null)) return
    dolbyVisionBlockedSourceUrl = failedUrl
    dolbyVisionTriedKeys += activeStream?.playerSourceIdentityKey() ?: "url:$failedUrl"
    removeFailedStreamFromCache()
    startDolbyVisionCompatibleSourceSearch()
}

/** Forgets the sources tried for unsupported Dolby Vision, e.g. for a source the user picked or the next episode. */
internal fun PlayerScreenRuntime.resetDolbyVisionFallback() {
    dolbyVisionFallbackJob?.cancel()
    dolbyVisionFallbackAttempts = 0
    dolbyVisionTriedKeys.clear()
    dolbyVisionBlockedSourceUrl = null
}

private fun PlayerScreenRuntime.startDolbyVisionCompatibleSourceSearch() {
    if (dolbyVisionFallbackJob?.isActive == true) return
    val videoId = activeVideoId
    if (videoId == null || dolbyVisionFallbackAttempts >= DOLBY_VISION_FALLBACK_MAX_SOURCES) {
        scope.launch { showNoCompatibleDolbyVisionSource() }
        return
    }
    val failedUrl = activeSourceUrl
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    errorMessage = null
    sourceFallbackJob?.cancel()
    decoderRetryJob?.cancel()
    // Stays up while sources load; the switching notice below replaces it.
    showGestureFeedback(
        GestureFeedbackState(messageRes = Res.string.player_dolby_vision_finding_source_message),
        durationMs = DOLBY_VISION_SOURCE_SEARCH_TIMEOUT_MS,
    )

    dolbyVisionFallbackJob = scope.launch {
        try {
            PlayerStreamsRepository.loadSources(
                contentTitle = title,
                type = contentType ?: parentMetaType,
                videoId = videoId,
                season = season,
                episode = episode,
            )
            // Wait for the whole list, so a "DV | HDR10" or HDR10 copy found later is not missed.
            val state = withTimeoutOrNull(DOLBY_VISION_SOURCE_SEARCH_TIMEOUT_MS) {
                PlayerStreamsRepository.sourceState.first { !it.isAnyLoading }
            } ?: PlayerStreamsRepository.sourceState.value
            val streams = state.groups.flatMap { it.streams }
            while (dolbyVisionFallbackAttempts < DOLBY_VISION_FALLBACK_MAX_SOURCES) {
                if (activeSourceUrl != failedUrl || activeVideoId != videoId) return@launch
                val next = nextDolbyVisionCompatibleCandidate(
                    streams = streams,
                    settings = playerSettingsUiState,
                    triedKeys = dolbyVisionTriedKeys,
                    failedUrl = failedUrl,
                ) ?: break
                dolbyVisionTriedKeys += next.playerSourceIdentityKey() ?: "url:${next.playableDirectUrl}"
                dolbyVisionFallbackAttempts++

                val playable = when (
                    val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(next, season, episode)
                ) {
                    is DirectDebridPlayableResult.Success -> resolved.stream
                    else -> null
                }
                val url = playable?.playableDirectUrl
                if (playable == null || url.isNullOrBlank() || url == failedUrl) continue
                if (activeSourceUrl != failedUrl || activeVideoId != videoId) return@launch
                log.i { "Dolby Vision not supported here, playing ${playable.streamLabel} from ${playable.addonName}" }
                showGestureFeedback(
                    GestureFeedbackState(messageRes = Res.string.player_dolby_vision_switching_source_message),
                    durationMs = DOLBY_VISION_SWITCH_NOTICE_MS,
                )
                playReplacementSource(playable, url, videoId)
                return@launch
            }
        } finally {
            PlayerStreamsRepository.stopSourcesLoading()
        }
        if (activeSourceUrl == failedUrl) showNoCompatibleDolbyVisionSource()
    }
}

private suspend fun PlayerScreenRuntime.showNoCompatibleDolbyVisionSource() {
    gestureMessageJob?.cancel()
    gestureFeedback = null
    errorMessage = getString(Res.string.player_error_dolby_vision_no_compatible_source)
    controlsVisible = !playerControlsLocked
}


/**
 * The next source to try after unsupported Dolby Vision: the auto selection order (first stream
 * when auto selection is off), with sources labelled Dolby Vision only moved last.
 */
internal fun nextDolbyVisionCompatibleCandidate(
    streams: List<StreamItem>,
    settings: PlayerSettingsUiState,
    triedKeys: Set<String>,
    failedUrl: String?,
    installedAddonNames: Set<String> = AddonRepository.uiState.value.addons.enabledAddons().map { it.displayTitle }.toSet(),
    debridEnabled: Boolean = DebridSettingsRepository.snapshot().canResolvePlayableLinks,
    activeResolverProviderId: String? = DebridSettingsRepository.snapshot().activeResolverProviderId,
): StreamItem? {
    val untried = streams.filterNot { stream ->
        val key = stream.playerSourceIdentityKey() ?: "url:${stream.playableDirectUrl}"
        key in triedKeys || (failedUrl != null && stream.playableDirectUrl == failedUrl)
    }
    val mode = settings.streamAutoPlayMode.takeUnless { it == StreamAutoPlayMode.MANUAL }
        ?: StreamAutoPlayMode.FIRST_STREAM
    return StreamAutoPlaySelector.evaluateAutoPlayStream(
        streams = untried,
        mode = mode,
        regexPattern = settings.streamAutoPlayRegex,
        source = settings.streamAutoPlaySource,
        installedAddonNames = installedAddonNames,
        selectedAddons = settings.streamAutoPlaySelectedAddons,
        selectedPlugins = settings.streamAutoPlaySelectedPlugins,
        debridEnabled = debridEnabled,
        activeResolverProviderId = activeResolverProviderId,
        demoteDolbyVisionOnly = true,
    ).readyStreams.firstOrNull()
}
