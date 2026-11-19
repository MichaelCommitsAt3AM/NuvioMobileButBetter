package com.nuvio.app.features.downloads

import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_quality_desc
import nuvio.composeapp.generated.resources.download_quality_error
import nuvio.composeapp.generated.resources.download_quality_large_file
import nuvio.composeapp.generated.resources.download_quality_loading
import nuvio.composeapp.generated.resources.download_quality_none_fit
import nuvio.composeapp.generated.resources.download_quality_none_found
import nuvio.composeapp.generated.resources.download_quality_size_unknown
import nuvio.composeapp.generated.resources.download_quality_starting
import nuvio.composeapp.generated.resources.download_quality_tier_fhd
import nuvio.composeapp.generated.resources.download_quality_tier_hd
import nuvio.composeapp.generated.resources.download_quality_tier_sd
import nuvio.composeapp.generated.resources.download_quality_tier_uhd
import nuvio.composeapp.generated.resources.download_quality_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private sealed interface QualitySheetPhase {
    data object Loading : QualitySheetPhase
    data object Failed : QualitySheetPhase
    data class Ready(val result: QualityDownloadOptions) : QualitySheetPhase
}

/**
 * One-tap download picker for a title. Finds the best stream for each of 4K / 1080p / 720p within the
 * user's bitrate budget and starts the download without opening the streams screen. Qualities with no
 * suitable stream are not offered. The desktop app shows the same options, in the same order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityDownloadSheet(
    meta: MetaDetails,
    onDismiss: () -> Unit,
    /** The episode's video id for a series; the title itself for a movie. */
    videoId: String = meta.id,
    /** Names what is being downloaded, e.g. "S2 E5 · Episode title". */
    subtitle: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }
    var phase by remember(meta.type, meta.id, videoId) { mutableStateOf<QualitySheetPhase>(QualitySheetPhase.Loading) }
    var startingTier by remember { mutableStateOf<StreamResolutionTier?>(null) }

    LaunchedEffect(meta.type, meta.id, videoId) {
        phase = QualitySheetPhase.Loading
        phase = try {
            QualitySheetPhase.Ready(DownloadQualityOptionsLoader.load(meta.type, meta.id, videoId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            QualitySheetPhase.Failed
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Res.string.download_quality_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle != null) Message(subtitle)
            // Grow to the new height and crossfade when the options arrive, instead of snapping.
            AnimatedContent(
                targetState = phase,
                contentKey = { it::class },
                transitionSpec = {
                    fadeIn(tween(durationMillis = 220, delayMillis = 90)) togetherWith
                        fadeOut(tween(durationMillis = 90)) using
                        SizeTransform(clip = false) { _, _ -> tween(durationMillis = 300, easing = FastOutSlowInEasing) }
                },
                label = "quality_download_sheet_phase",
            ) { current ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (current) {
                        QualitySheetPhase.Loading -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(Res.string.download_quality_loading),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        QualitySheetPhase.Failed -> Message(stringResource(Res.string.download_quality_error))
                        is QualitySheetPhase.Ready -> {
                            val result = current.result
                            if (result.options.isEmpty()) {
                                Message(
                                    stringResource(
                                        if (result.loadedStreams == 0) {
                                            Res.string.download_quality_none_found
                                        } else {
                                            Res.string.download_quality_none_fit
                                        },
                                    ),
                                )
                            } else {
                                Message(stringResource(Res.string.download_quality_desc))
                                val sizeUnknown = stringResource(Res.string.download_quality_size_unknown)
                                val starting = stringResource(Res.string.download_quality_starting)
                                result.options.forEach { option ->
                                    // Every stream of this quality is over the data saver budget: the smallest is offered anyway.
                                    val tierName = stringResource(tierSubtitle(option.tier))
                                    QualityOptionRow(
                                        title = tierTitle(option.tier, option.hdr),
                                        subtitle = if (option.overBudget) {
                                            stringResource(Res.string.download_quality_large_file, tierName)
                                        } else {
                                            tierName
                                        },
                                        subtitleColor = if (option.overBudget) LargeFileColor else null,
                                        trailing = if (startingTier == option.tier) {
                                            starting
                                        } else {
                                            formatDownloadSize(option.sizeBytes, sizeUnknown)
                                        },
                                        enabled = startingTier == null,
                                        onClick = {
                                            startingTier = option.tier
                                            scope.launch {
                                                try {
                                                    startDownload(result, option)
                                                } finally {
                                                    startingTier = null
                                                }
                                                dismiss()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun QualityOptionRow(
    title: String,
    subtitle: String,
    subtitleColor: Color? = null,
    trailing: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = trailing,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** HDR is only called out on 4K, and only when that particular file is HDR. */
private fun tierTitle(tier: StreamResolutionTier, hdr: Boolean): String = when (tier) {
    StreamResolutionTier.UHD_4K -> if (hdr) "4K HDR" else "4K"
    StreamResolutionTier.FHD_1080 -> "1080p"
    StreamResolutionTier.HD_720 -> "720p"
    StreamResolutionTier.SD -> "480p"
    else -> ""
}

private fun tierSubtitle(tier: StreamResolutionTier): StringResource = when (tier) {
    StreamResolutionTier.UHD_4K -> Res.string.download_quality_tier_uhd
    StreamResolutionTier.FHD_1080 -> Res.string.download_quality_tier_fhd
    StreamResolutionTier.SD -> Res.string.download_quality_tier_sd
    else -> Res.string.download_quality_tier_hd
}

/** "2.5 GB" / "14 GB" / "700 MB", matching the desktop picker. */
internal fun formatDownloadSize(bytes: Long?, unknown: String): String {
    if (bytes == null || bytes <= 0L) return unknown
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return when {
        gb >= 10.0 -> "${gb.roundToInt()} GB"
        gb >= 1.0 -> {
            val tenths = (gb * 10).roundToInt()
            "${tenths / 10}.${tenths % 10} GB"
        }
        else -> "${(bytes / (1024.0 * 1024.0)).roundToInt().coerceAtLeast(1)} MB"
    }
}

/** Same enqueue path as the streams screen: resolve debrid links first, then enqueue and toast the result. */
private suspend fun startDownload(result: QualityDownloadOptions, option: QualityDownloadOption) {
    val meta = result.meta
    val video = meta.videos.firstOrNull { it.id == result.videoId }
    var stream = option.stream
    if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
        when (
            val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                stream = stream,
                season = video?.season,
                episode = video?.episode,
            )
        ) {
            is DirectDebridPlayableResult.Success -> stream = resolved.stream
            else -> {
                resolved.toastMessage()?.let { NuvioToastController.show(it) }
                return
            }
        }
    }
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

/** Amber for a quality offered over the data saver limit, matching the desktop sheet. */
private val LargeFileColor = Color(0xFFF0B35A)
