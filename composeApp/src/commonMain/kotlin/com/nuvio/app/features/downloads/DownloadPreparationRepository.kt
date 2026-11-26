package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.streams.DolbyVisionProbeResult
import com.nuvio.app.features.streams.StreamDolbyVision
import com.nuvio.app.features.streams.StreamDolbyVisionLabel
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.platformDecodesDolbyVision
import com.nuvio.app.features.streams.probeDolbyVisionSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_preparing_none
import nuvio.composeapp.generated.resources.downloads_preparing_toast
import org.jetbrains.compose.resources.getString

/** Where a download stands while its video format is checked, before it is queued. */
enum class DownloadPreparationStage {
    /** Reading the file header to see whether this device can play its Dolby Vision. */
    Checking,

    /** The pick is Dolby Vision this device cannot play; looking for a compatible stream. */
    FindingCompatible,

    /** A compatible stream was found and is about to be queued in place of the pick. */
    Switched,

    /** No compatible stream was found; nothing was queued. */
    NoCompatible,
}

/**
 * A download shown on the Downloads screen before it exists: [display] carries the title and
 * artwork for the card, [switchedTo] what replaced the pick ("1080p • 2.7 GB").
 */
data class DownloadPreparation(
    val id: String,
    val display: DownloadItem,
    val stage: DownloadPreparationStage,
    val switchedTo: String? = null,
)

/**
 * Checks a download picked in the quality sheet before queueing it. On a device without a Dolby
 * Vision decoder, a pick labelled Dolby Vision has its header read; if it is a profile this
 * device cannot play, the closest compatible stream (same quality, then nearest size) is queued
 * instead. Progress shows as a card on the Downloads screen. Kept in memory only.
 */
object DownloadPreparationRepository {
    private const val MAX_ALTERNATIVES = 4
    private const val SWITCHED_NOTICE_MS = 2_500L

    private val log = Logger.withTag("DownloadPreparation")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _items = MutableStateFlow<List<DownloadPreparation>>(emptyList())
    val items: StateFlow<List<DownloadPreparation>> = _items.asStateFlow()
    private var nextId = 0L

    /**
     * Whether [option] has to be checked before downloading, which [start] then does in the
     * background. Otherwise the caller queues it straight away.
     */
    fun needsCheck(option: QualityDownloadOption): Boolean =
        !platformDecodesDolbyVision() && StreamDolbyVision.label(option.stream) != StreamDolbyVisionLabel.None

    /** Starts checking [option] in the background and shows its card on the Downloads screen. */
    fun start(result: QualityDownloadOptions, option: QualityDownloadOption) {
        val preparation = DownloadPreparation(
            id = "prep:${nextId++}",
            display = displayItem(result),
            stage = DownloadPreparationStage.Checking,
        )
        _items.update { it + preparation }
        scope.launch {
            NuvioToastController.show(getString(Res.string.downloads_preparing_toast))
            prepare(preparation.id, result, option)
        }
    }

    fun dismiss(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
    }

    private suspend fun prepare(id: String, result: QualityDownloadOptions, option: QualityDownloadOption) {
        val video = result.meta.videos.firstOrNull { it.id == result.videoId }
        val picked = resolve(option.stream, video?.season, video?.episode, showErrors = true)
        if (picked == null) {
            dismiss(id)
            return
        }
        val pickedResult = probe(picked)
        val pickedPlays = when (pickedResult) {
            DolbyVisionProbeResult.Supported -> true
            DolbyVisionProbeResult.Unsupported -> false
            // Header unreadable: trust the label, which only rules out "Dolby Vision only".
            DolbyVisionProbeResult.Unknown -> StreamDolbyVision.label(picked) != StreamDolbyVisionLabel.Only
        }
        if (pickedPlays) {
            dismiss(id)
            enqueue(result, picked)
            return
        }

        log.i { "Dolby Vision pick cannot play here, looking for a compatible stream" }
        setStage(id, DownloadPreparationStage.FindingCompatible)
        val tried = mutableSetOf(option.stream)
        repeat(MAX_ALTERNATIVES) {
            val next = DownloadDolbyVisionAlternatives.ordered(
                streams = result.streams,
                selected = option,
                tried = tried,
                config = DownloadFilterSettingsRepository.snapshot(),
                runtimeSeconds = result.runtimeSeconds,
            ).firstOrNull()
                ?: return@repeat
            tried += next
            val candidate = resolve(next, video?.season, video?.episode, showErrors = false) ?: return@repeat
            val plays = StreamDolbyVision.label(candidate) == StreamDolbyVisionLabel.None ||
                probe(candidate) != DolbyVisionProbeResult.Unsupported
            if (!plays) return@repeat
            setStage(id, DownloadPreparationStage.Switched, switchedTo = describe(next))
            delay(SWITCHED_NOTICE_MS)
            dismiss(id)
            enqueue(result, candidate)
            return
        }
        setStage(id, DownloadPreparationStage.NoCompatible)
        _items.value.firstOrNull { it.id == id }?.let { preparation ->
            DownloadsLiveStatusPlatform.notifyNoCompatibleVersion(
                item = preparation.display,
                message = getString(Res.string.downloads_preparing_none),
            )
        }
    }

    private suspend fun resolve(stream: StreamItem, season: Int?, episode: Int?, showErrors: Boolean): StreamItem? {
        if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) return stream
        return when (val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(stream, season, episode)) {
            is DirectDebridPlayableResult.Success -> resolved.stream
            else -> {
                if (showErrors) resolved.toastMessage()?.let { NuvioToastController.show(it) }
                null
            }
        }
    }

    private suspend fun probe(stream: StreamItem): DolbyVisionProbeResult {
        val url = stream.playableDirectUrl ?: return DolbyVisionProbeResult.Unknown
        return probeDolbyVisionSupport(url, sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request))
    }

    private fun setStage(id: String, stage: DownloadPreparationStage, switchedTo: String? = null) {
        _items.update { list ->
            list.map { if (it.id == id) it.copy(stage = stage, switchedTo = switchedTo ?: it.switchedTo) else it }
        }
    }

    private fun describe(stream: StreamItem): String {
        val tier = when (StreamQualityClassifier.resolutionOf(stream)) {
            StreamResolutionTier.UHD_4K -> if (StreamQualityClassifier.isHdr(stream)) "4K HDR" else "4K"
            StreamResolutionTier.FHD_1080 -> "1080p"
            StreamResolutionTier.HD_720 -> "720p"
            StreamResolutionTier.SD -> "480p"
            StreamResolutionTier.UNKNOWN -> null
        }
        val size = StreamQualityClassifier.sizeOf(stream)?.let(::formatBytes)
        return listOfNotNull(tier, size).joinToString(DownloadTextSeparator).ifBlank { stream.streamLabel }
    }

    private fun displayItem(result: QualityDownloadOptions): DownloadItem {
        val meta = result.meta
        val video = meta.videos.firstOrNull { it.id == result.videoId }
        val now = DownloadsClock.nowEpochMs()
        return DownloadItem(
            id = "preparing",
            contentType = meta.type,
            parentMetaId = meta.id,
            parentMetaType = meta.type,
            videoId = result.videoId,
            title = meta.name,
            logo = meta.logo,
            poster = meta.poster,
            background = meta.background,
            seasonNumber = video?.season,
            episodeNumber = video?.episode,
            episodeTitle = video?.title,
            episodeThumbnail = video?.thumbnail,
            streamTitle = "",
            providerName = "",
            sourceUrl = "",
            fileName = "",
            status = DownloadStatus.Downloading,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
    }

    internal fun enqueue(result: QualityDownloadOptions, stream: StreamItem) {
        val meta = result.meta
        val video = meta.videos.firstOrNull { it.id == result.videoId }
        val enqueued = DownloadsRepository.enqueueFromStream(
            contentType = meta.type,
            videoId = result.videoId,
            parentMetaId = meta.id,
            parentMetaType = meta.type,
            title = meta.name,
            logo = meta.logo,
            poster = meta.poster,
            background = meta.background,
            seasonNumber = video?.season,
            episodeNumber = video?.episode,
            episodeTitle = video?.title,
            episodeThumbnail = video?.thumbnail,
            stream = stream,
        )
        NuvioToastController.show(enqueued.toastMessage())
    }
}
