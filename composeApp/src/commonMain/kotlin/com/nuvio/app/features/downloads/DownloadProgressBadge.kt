package com.nuvio.app.features.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.MetaVideo
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The downloads of one title, for looking up each episode's download from the episode list. */
@Composable
fun rememberTitleDownloads(parentMetaType: String, parentMetaId: String): List<DownloadItem> {
    LaunchedEffect(Unit) { DownloadsRepository.ensureLoaded() }
    val state by DownloadsRepository.visibleUiState.collectAsState()
    return remember(state.items, parentMetaType, parentMetaId) {
        state.items.filter { it.parentMetaId == parentMetaId && it.parentMetaType == parentMetaType }
    }
}

/** The download for an episode: matched by video id, or by season and episode number. The newest one wins. */
fun List<DownloadItem>.forEpisode(episode: MetaVideo): DownloadItem? =
    filter { item ->
        item.videoId == episode.id ||
            (episode.season != null && episode.episode != null &&
                item.seasonNumber == episode.season && item.episodeNumber == episode.episode)
    }.maxByOrNull { it.updatedAtEpochMs }

/** The newest download of a title (a movie has one video), or null. */
@Composable
fun rememberTitleDownload(parentMetaType: String, parentMetaId: String): DownloadItem? {
    val items = rememberTitleDownloads(parentMetaType, parentMetaId)
    return remember(items) { items.maxByOrNull { it.updatedAtEpochMs } }
}

val DownloadItem.isInProgress: Boolean
    get() = status == DownloadStatus.Downloading || status == DownloadStatus.Paused

/** A progress ring that fills clockwise from the top, drawn over a round element of the same size. */
@Composable
fun DownloadRing(
    progress: Float,
    paused: Boolean,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.5.dp,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), animationSpec = tween(400), label = "download_ring")
    val ringColor = if (paused) Color.White.copy(alpha = 0.63f) else Color.White
    Canvas(modifier = modifier.fillMaxSize()) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        drawArc(ringColor, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun downloadDescription(item: DownloadItem): String {
    val percent = (item.progressFraction * 100).roundToInt()
    return when (item.status) {
        DownloadStatus.Completed -> stringResource(Res.string.episodes_cd_downloaded)
        DownloadStatus.Failed -> stringResource(Res.string.episodes_cd_download_failed)
        DownloadStatus.Paused -> stringResource(Res.string.episodes_cd_download_paused, percent)
        DownloadStatus.Downloading -> stringResource(Res.string.episodes_cd_downloading, percent)
    }
}

/**
 * Round download control with an episode's download state, styled like the movie Download button: the download
 * icon inside a filling ring while downloading (dimmed when paused), on a white disc once downloaded and red when
 * the download failed. Sits on the right of the episode card; tapping it opens the episode's quality sheet.
 */
@Composable
fun EpisodeDownloadButton(
    item: DownloadItem,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
) {
    val description = downloadDescription(item)
    val completed = item.status == DownloadStatus.Completed
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (completed) Color.White else Color.White.copy(alpha = 0.10f))
            .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (item.isInProgress) {
            DownloadRing(progress = item.progressFraction, paused = item.status == DownloadStatus.Paused, modifier = Modifier.padding(1.dp))
        }
        Icon(
            imageVector = if (item.status == DownloadStatus.Failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Download,
            contentDescription = null,
            tint = when (item.status) {
                DownloadStatus.Completed -> Color.Black
                DownloadStatus.Failed -> Color(0xFFFF8A8A)
                DownloadStatus.Paused -> Color.White.copy(alpha = 0.7f)
                DownloadStatus.Downloading -> Color.White
            },
            modifier = Modifier.size(size * 0.47f),
        )
    }
}

/**
 * Small round badge with a download's state, for compact places such as the episode actions row. Same states as
 * [EpisodeDownloadButton]. Shows nothing when there is no download.
 */
@Composable
fun DownloadProgressBadge(
    item: DownloadItem?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = item != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        if (item != null) EpisodeDownloadButton(item = item, onClick = null, size = 26.dp)
    }
}
