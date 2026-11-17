package com.nuvio.app.features.details.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

@Composable
actual fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    startPositionMs: Long,
    seekRequest: com.nuvio.app.features.details.HeroTrailerSeekRequest?,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onBufferingChanged: (Boolean) -> Unit,
    onError: () -> Unit,
) {
    LaunchedEffect(sourceUrl) {
        onError()
    }
}
