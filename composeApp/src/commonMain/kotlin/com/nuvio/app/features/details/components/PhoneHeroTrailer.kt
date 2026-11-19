package com.nuvio.app.features.details.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.LocalVerticalBarPadding
import com.nuvio.app.features.details.HeroTrailerPlaybackConfig
import com.nuvio.app.features.details.HeroTrailerSeekRequest
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.hero_trailer_fullscreen
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.hero_trailer_label
import nuvio.composeapp.generated.resources.hero_trailer_mute
import nuvio.composeapp.generated.resources.hero_trailer_pause
import nuvio.composeapp.generated.resources.hero_trailer_play
import nuvio.composeapp.generated.resources.hero_trailer_play_badge
import nuvio.composeapp.generated.resources.hero_trailer_unmute
import org.jetbrains.compose.resources.stringResource

/**
 * Fork-only: the trailer on phones, behaving like a small player. A full-width 16:9 frame,
 * fitted and never cropped, sits below the status bar and back button. [visibility] (0..1)
 * fades the video in over the poster once the poster has morphed into the frame's rectangle. Tapping the frame shows the controls: a centre
 * pause/play button and a progress bar that can be tapped or dragged to seek.
 */
@Composable
internal fun BoxScope.PhoneHeroTrailerFrame(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    userPaused: Boolean,
    muted: Boolean,
    startPositionMs: Long,
    visibility: () -> Float,
    scrollOffset: () -> Int,
    positionMs: () -> Long,
    durationMs: () -> Long,
    onUserPausedToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onError: () -> Unit,
    onFullscreen: (() -> Unit)? = null,
) {
    val topOffset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        HeroTrailerPlaybackConfig.FRAME_TOP_OFFSET_DP.dp
    var controlsVisible by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var seekRequest by remember { mutableStateOf<HeroTrailerSeekRequest?>(null) }
    var seekToken by remember { mutableIntStateOf(0) }
    var buffering by remember { mutableStateOf(false) }

    // Hide the controls once the frame fades out, and a few seconds after the last interaction.
    LaunchedEffect(Unit) {
        snapshotFlow { visibility() > 0.5f }.collect { shown -> if (!shown) controlsVisible = false }
    }
    LaunchedEffect(controlsVisible, userPaused, seeking) {
        if (controlsVisible && !userPaused && !seeking) {
            delay(HeroTrailerPlaybackConfig.CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(LocalVerticalBarPadding.current)
            .padding(top = topOffset)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .graphicsLayer {
                // The poster has already shrunk into this exact rectangle (see DetailHero), so the
                // video only crossfades in place over it.
                alpha = visibility()
                translationY = scrollOffset() * 0.5f
            },
    ) {
        HeroTrailerPlayerSurface(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            playWhenReady = playWhenReady && !userPaused,
            muted = muted,
            startPositionMs = startPositionMs,
            seekRequest = seekRequest,
            modifier = Modifier.fillMaxSize(),
            onReady = onReady,
            onEnded = onEnded,
            onProgress = onProgress,
            onBufferingChanged = { buffering = it },
            onError = onError,
        )
        // Tapping the video toggles the controls. It sits under the controls, so they keep their own taps.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (visibility() > 0.5f) controlsVisible = !controlsVisible
                },
        )
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                val description = stringResource(
                    if (userPaused) Res.string.hero_trailer_play else Res.string.hero_trailer_pause,
                )
                Box(
                    modifier = Modifier
                        .size(HeroTrailerPlaybackConfig.CENTER_BUTTON_DP.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = HeroTrailerPlaybackConfig.CONTROL_BACKGROUND_ALPHA))
                        .clickable(role = Role.Button, onClickLabel = description) {
                            if (visibility() > 0.5f) onUserPausedToggle()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (buffering) {
                        TrailerLoadingSpinner()
                    } else {
                        Icon(
                            imageVector = if (userPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = description,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                // Fades in and out with the pause button, in the frame's top-right corner.
                onFullscreen?.let { openFullscreen ->
                    HeroTrailerFullscreenButton(
                        onClick = { if (visibility() > 0.5f) openFullscreen() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp),
                    )
                }
            }
        }
        // While seeking or rebuffering with the controls hidden, show the spinner on its own.
        if (buffering && !controlsVisible) {
            TrailerLoadingSpinner(modifier = Modifier.align(Alignment.Center))
        }
        TrailerBottomControls(
            muted = muted,
            controlsVisible = controlsVisible,
            visibility = visibility,
            positionMs = positionMs,
            durationMs = durationMs,
            onMuteToggle = onMuteToggle,
            onSeekingChanged = { seeking = it },
            onSeek = { target ->
                seekToken += 1
                seekRequest = HeroTrailerSeekRequest(target, seekToken)
                onProgress(target, durationMs())
            },
        )
    }
}

@Composable
private fun BoxScope.TrailerBottomControls(
    muted: Boolean,
    controlsVisible: Boolean,
    visibility: () -> Float,
    positionMs: () -> Long,
    durationMs: () -> Long,
    onMuteToggle: () -> Unit,
    onSeekingChanged: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val controlBackground = Color.Black.copy(alpha = HeroTrailerPlaybackConfig.CONTROL_BACKGROUND_ALPHA)
    val seekTouchHeight = HeroTrailerPlaybackConfig.SEEK_BAR_TOUCH_HEIGHT_DP.dp
    val controlInset = 10.dp
    val latestDuration = rememberUpdatedState(durationMs)
    val latestOnSeek = rememberUpdatedState(onSeek)
    val latestOnSeekingChanged = rememberUpdatedState(onSeekingChanged)
    var dragFraction by remember { mutableFloatStateOf(-1f) }

    val muteDescription = stringResource(
        if (muted) Res.string.hero_trailer_unmute else Res.string.hero_trailer_mute,
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = controlInset, bottom = controlInset + seekTouchHeight / 2)
            .size(36.dp)
            .clip(CircleShape)
            .background(controlBackground)
            .clickable(role = Role.Button, onClickLabel = muteDescription) {
                // The frame stays composed while faded out; only react while it is visible.
                if (visibility() > 0.5f) onMuteToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
            contentDescription = muteDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }

    // Progress bar. The touch area is taller than the line so it is easy to grab; seeking only
    // works once the controls are showing, so a stray touch on the bare video cannot jump it.
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(seekTouchHeight)
            .pointerInput(controlsVisible) {
                if (!controlsVisible) return@pointerInput
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    val duration = latestDuration.value()
                    if (duration > 0L) latestOnSeek.value((fraction * duration).toLong())
                }
            }
            .pointerInput(controlsVisible) {
                if (!controlsVisible) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        latestOnSeekingChanged.value(true)
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        val duration = latestDuration.value()
                        if (duration > 0L && dragFraction >= 0f) latestOnSeek.value((dragFraction * duration).toLong())
                        dragFraction = -1f
                        latestOnSeekingChanged.value(false)
                    },
                    onDragCancel = {
                        dragFraction = -1f
                        latestOnSeekingChanged.value(false)
                    },
                    onHorizontalDrag = { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                )
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        val lineHeight = if (controlsVisible) 3.5.dp else HeroTrailerPlaybackConfig.PROGRESS_LINE_HEIGHT_DP.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(lineHeight)
                .align(Alignment.BottomStart)
                .background(Color.White.copy(alpha = 0.25f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = currentFraction(dragFraction, positionMs(), durationMs())
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .background(Color.White),
            )
        }
        if (controlsVisible) {
            val thumbSize = 14.dp
            val density = LocalDensity.current
            var trackWidthPx by remember { mutableFloatStateOf(0f) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumbSize)
                    .align(Alignment.BottomStart)
                    .graphicsLayer { trackWidthPx = size.width },
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            val fraction = currentFraction(dragFraction, positionMs(), durationMs())
                            val thumbPx = with(density) { thumbSize.roundToPx() }
                            // The thumb box sits on the bottom edge like the line does, so push it
                            // down by the difference in half-heights to centre it on the line.
                            val centerShiftPx = with(density) { ((thumbSize - lineHeight) / 2).roundToPx() }
                            IntOffset((fraction * trackWidthPx - thumbPx / 2f).toInt(), centerShiftPx)
                        }
                        .size(thumbSize)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
    }
}

private fun currentFraction(dragFraction: Float, positionMs: Long, durationMs: Long): Float =
    when {
        dragFraction >= 0f -> dragFraction
        durationMs > 0L -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }

/** Small "Trailer" badge on the poster when the trailer does not autoplay. Tap to play it. */
@Composable
internal fun HeroTrailerPlayBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(Res.string.hero_trailer_play_badge)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.Black.copy(alpha = HeroTrailerPlaybackConfig.CONTROL_BACKGROUND_ALPHA))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(Res.string.hero_trailer_label),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** Opens the playing hero trailer in the full-screen trailer player. */
@Composable
internal fun HeroTrailerFullscreenButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(Res.string.hero_trailer_fullscreen)
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = HeroTrailerPlaybackConfig.CONTROL_BACKGROUND_ALPHA))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Fullscreen,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Spinner shown while the trailer loads after a seek or rebuffers. */
@Composable
private fun TrailerLoadingSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(32.dp),
        color = Color.White,
        strokeWidth = 3.dp,
    )
}
