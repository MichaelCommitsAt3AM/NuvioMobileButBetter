package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PlayerSurfaceGestureCallbacks(
    val onSurfaceTap: State<(Offset) -> Unit>,
    val onSurfaceDoubleTap: State<(Offset) -> Unit>,
    val activateHoldToSpeed: State<() -> Unit>,
    val deactivateHoldToSpeed: State<() -> Unit>,
    val showHorizontalSeekPreview: State<(Long, Long) -> Unit>,
    val showBrightnessFeedback: State<(Float) -> Unit>,
    val showVolumeFeedback: State<(PlayerAudioLevel) -> Unit>,
    val clearLiveGestureFeedback: State<() -> Unit>,
    val revealLockedOverlay: State<() -> Unit>,
    val isHoldToSpeedGestureActive: State<Boolean>,
    val gestureSwipeSeekEnabled: State<Boolean>,
    val gestureBrightnessEnabled: State<Boolean>,
    val gestureVolumeEnabled: State<Boolean>,
    val playerControlsLocked: State<Boolean>,
    val currentPositionMs: State<Long>,
    val currentDurationMs: State<Long>,
    val commitHorizontalSeek: State<(Long) -> Unit>,
)

internal fun PlayerScreenRuntime.showGestureFeedback(feedback: GestureFeedbackState) {
    gestureMessageJob?.cancel()
    gestureFeedback = feedback
    gestureMessageJob = scope.launch {
        delay(900)
        gestureFeedback = null
    }
}

internal fun PlayerScreenRuntime.showGestureMessage(message: String) {
    showGestureFeedback(GestureFeedbackState(message = message))
}

internal fun PlayerScreenRuntime.clearLiveGestureFeedback() {
    liveGestureFeedback = null
}

internal fun PlayerScreenRuntime.revealLockedOverlay() {
    controlsVisible = false
    lockedOverlayVisible = true
}

internal fun PlayerScreenRuntime.lockPlayerControls() {
    playerControlsLocked = true
    controlsVisible = false
    lockedOverlayVisible = false
    pausedOverlayVisible = false
    isScrubbingTimeline = false
    scrubbingPositionMs = null
    gestureMessageJob?.cancel()
    gestureFeedback = null
    liveGestureFeedback = null
    renderedGestureFeedback = null
    showAudioModal = false
    showSubtitleModal = false
    showVideoSettingsModal = false
    showSourcesPanel = false
    showEpisodesPanel = false
    episodeStreamsPanelState = EpisodeStreamsPanelState()
    PlayerStreamsRepository.clearEpisodeStreams()
}

internal fun PlayerScreenRuntime.unlockPlayerControls() {
    playerControlsLocked = false
    lockedOverlayVisible = false
    controlsVisible = true
}

internal fun PlayerScreenRuntime.showSeekFeedback(direction: PlayerSeekDirection, amountMs: Long) {
    val seconds = amountMs / 1000L
    if (seconds <= 0L) return
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = if (direction == PlayerSeekDirection.Forward) {
                Res.string.compose_player_seek_feedback_forward
            } else {
                Res.string.compose_player_seek_feedback_backward
            },
            messageArgs = listOf(seconds),
            icon = if (direction == PlayerSeekDirection.Forward) {
                GestureFeedbackIcon.SeekForward
            } else {
                GestureFeedbackIcon.SeekBackward
            },
        ),
    )
}

internal fun PlayerScreenRuntime.showHorizontalSeekPreview(previewPositionMs: Long, baselinePositionMs: Long) {
    val deltaMs = previewPositionMs - baselinePositionMs
    val direction = if (deltaMs < 0L) PlayerSeekDirection.Backward else PlayerSeekDirection.Forward
    liveGestureFeedback = GestureFeedbackState(
        message = formatPlaybackTime(previewPositionMs),
        icon = if (direction == PlayerSeekDirection.Forward) {
            GestureFeedbackIcon.SeekForward
        } else {
            GestureFeedbackIcon.SeekBackward
        },
        secondaryMessageRes = if (deltaMs >= 0L) {
            Res.string.compose_player_seek_delta_forward
        } else {
            Res.string.compose_player_seek_delta_backward
        },
        secondaryMessageArgs = listOf((abs(deltaMs) / 1000f).roundToInt()),
        secondaryMessageColor = if (direction == PlayerSeekDirection.Forward) {
            Color(0xFF6EE7A8)
        } else {
            Color(0xFFFF9A76)
        },
    )
}

internal fun PlayerScreenRuntime.showBrightnessFeedback(level: Float) {
    val percentage = (level.coerceIn(0f, 1f) * 100f).roundToInt()
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = Res.string.compose_player_brightness_level,
            messageArgs = listOf("$percentage%"),
            icon = GestureFeedbackIcon.Brightness,
        ),
    )
}

internal fun PlayerScreenRuntime.showVolumeFeedback(level: PlayerAudioLevel) {
    val percentage = (level.fraction.coerceIn(0f, 1f) * 100f).roundToInt()
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = if (level.isMuted) {
                Res.string.compose_player_muted
            } else {
                Res.string.compose_player_volume_level
            },
            messageArgs = if (level.isMuted) emptyList() else listOf("$percentage%"),
            icon = if (level.isMuted) GestureFeedbackIcon.VolumeMuted else GestureFeedbackIcon.Volume,
            isDanger = level.isMuted,
        ),
    )
}

internal fun PlayerScreenRuntime.togglePlayback() {
    if (playbackSnapshot.isPlaying) {
        shouldPlay = false
        playerController?.pause()
    } else {
        if (playbackSnapshot.isEnded) {
            playerController?.seekTo(0L)
        }
        shouldPlay = true
        playerController?.play()
    }
    controlsVisible = true
}

internal fun PlayerScreenRuntime.seekBy(offsetMs: Long) {
    val fromMs = playbackSnapshot.positionMs
    val targetMs = (fromMs + offsetMs).coerceAtLeast(0L)
        .let { if (playbackSnapshot.durationMs > 0L) it.coerceAtMost(playbackSnapshot.durationMs) else it }
    lastManualSkipSeekPositions = fromMs to targetMs
    playerController?.seekBy(offsetMs, PlayerSeekPrecision.Fast)
    scheduleProgressSyncAfterSeek()
    controlsVisible = true
    when {
        offsetMs > 0L -> showSeekFeedback(PlayerSeekDirection.Forward, offsetMs)
        offsetMs < 0L -> showSeekFeedback(PlayerSeekDirection.Backward, abs(offsetMs))
    }
}

internal fun PlayerScreenRuntime.handleDoubleTapSeek(direction: PlayerSeekDirection) {
    val currentPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    val currentSeekState = accumulatedSeekState
    val nextState = if (currentSeekState?.direction == direction) {
        currentSeekState.copy(amountMs = currentSeekState.amountMs + PlayerDoubleTapSeekStepMs)
    } else {
        PlayerAccumulatedSeekState(
            direction = direction,
            baselinePositionMs = currentPositionMs,
            amountMs = PlayerDoubleTapSeekStepMs,
        )
    }
    accumulatedSeekState = nextState

    val maxDurationMs = playbackSnapshot.durationMs.takeIf { it > 0L }
    val targetPositionMs = when (direction) {
        PlayerSeekDirection.Backward -> {
            (nextState.baselinePositionMs - nextState.amountMs).coerceAtLeast(0L)
        }
        PlayerSeekDirection.Forward -> {
            val unclamped = nextState.baselinePositionMs + nextState.amountMs
            maxDurationMs?.let { unclamped.coerceAtMost(it) } ?: unclamped
        }
    }
    lastManualSkipSeekPositions = currentPositionMs to targetPositionMs
    playerController?.seekTo(targetPositionMs, PlayerSeekPrecision.Fast)
    scheduleProgressSyncAfterSeek()
    showSeekFeedback(direction, nextState.amountMs)

    accumulatedSeekResetJob?.cancel()
    accumulatedSeekResetJob = scope.launch {
        delay(PlayerDoubleTapSeekResetDelayMs)
        accumulatedSeekState = null
    }
}

/** True while the green "Auto is available" dot should show on the resize pill. */
internal val PlayerScreenRuntime.autoOfferPending: Boolean
    get() = autoBars != null && !autoTried && resizeMode != PlayerResizeMode.Auto

/**
 * Records what the engine reported about baked-in bars. Bars are per video: when the engine
 * reports none (new source, or an engine that doesn't measure) the offer is withdrawn. A chosen
 * Auto stays selected - with nothing measured it renders exactly like Fit, and it picks the zoom
 * up as soon as the new video's bars are found.
 */
internal fun PlayerScreenRuntime.onVideoBarsReported(bars: PlayerVideoBars?) {
    if (bars == null && autoBars != null) autoTried = false
    if (bars == autoBars) return
    autoBars = bars
    if (bars != null && resizeMode == PlayerResizeMode.Auto) rememberAutoBars(bars)
}

/**
 * The engine saw a steady picture with no bars, so bars remembered from an earlier episode don't
 * apply to this one: drop them rather than keep cropping it. Auto itself stays selected.
 */
internal fun PlayerScreenRuntime.onVideoBarsAbsent() {
    if (rememberedAutoAspect?.bars != null) rememberedAutoAspect = RememberedAutoAspect()
}

/** The bars Auto zooms with: this video's own once measured, else ones remembered for its frame. */
internal fun PlayerScreenRuntime.effectiveAutoBars(frameWidth: Int, frameHeight: Int): PlayerVideoBars? =
    autoBars ?: rememberedAutoAspect?.barsFor(frameWidth, frameHeight)

private fun PlayerScreenRuntime.rememberAutoBars(bars: PlayerVideoBars) {
    PlayerAutoAspectMemory.rememberBars(
        scope = autoAspectScope,
        bars = bars,
        frameWidth = playbackSnapshot.videoWidth,
        frameHeight = playbackSnapshot.videoHeight,
    )
}

/** Where a chosen Auto is remembered: the current season for series, the title otherwise. */
internal val PlayerScreenRuntime.autoAspectScope: String
    get() = PlayerAutoAspectMemory.scopeKey(
        parentMetaType = parentMetaType,
        parentMetaId = parentMetaId,
        seasonNumber = activeSeasonNumber.takeIf { isSeries },
    )

/**
 * Picks the resize mode for a newly entered episode: Auto if the user left Auto on for this
 * season (or title), otherwise the saved mode. The previous video's own bars are dropped; bars
 * remembered for the season zoom the new episode right away if its frame has the same shape.
 */
internal fun PlayerScreenRuntime.applyRememberedResizeMode() {
    autoBars = null
    autoTried = false
    rememberedAutoAspect = PlayerAutoAspectMemory.recall(autoAspectScope)
    resizeMode = if (rememberedAutoAspect != null) {
        PlayerResizeMode.Auto
    } else {
        playerSettingsUiState.resizeMode
    }
}

internal fun PlayerScreenRuntime.cycleResizeMode() {
    val autoAvailable = autoZoom > 1f
    // While the dot is showing, the next tap goes straight to Auto instead of walking the cycle.
    val nextMode = if (autoOfferPending && autoAvailable) {
        PlayerResizeMode.Auto
    } else {
        resizeMode.next(autoAvailable)
    }
    resizeMode = nextMode
    if (nextMode == PlayerResizeMode.Auto) {
        // Auto is never the saved default (it needs bars detected first); it is remembered for
        // this season/title only, so the next episodes open on it too.
        autoTried = true
        PlayerAutoAspectMemory.setRemembered(autoAspectScope, true)
        autoBars?.let { rememberAutoBars(it) }
    } else {
        PlayerAutoAspectMemory.setRemembered(autoAspectScope, false)
        rememberedAutoAspect = null
        lastSyncedSettingsResizeMode = nextMode
        PlayerSettingsRepository.setResizeMode(nextMode)
    }
    showGestureMessage(
        when (nextMode) {
            PlayerResizeMode.Auto -> resizeModeAutoLabel
            PlayerResizeMode.Fit -> resizeModeFitLabel
            PlayerResizeMode.Fill -> resizeModeFillLabel
            PlayerResizeMode.Zoom -> resizeModeZoomLabel
        },
    )
    controlsVisible = true
}

internal fun PlayerScreenRuntime.cyclePlaybackSpeed() {
    val speeds = listOf(1f, 1.25f, 1.5f, 2f)
    val current = playbackSnapshot.playbackSpeed
    val next = speeds.firstOrNull { it > current + 0.01f } ?: speeds.first()
    playerController?.setPlaybackSpeed(next)
    showGestureMessage(formatPlaybackSpeedLabel(next))
    controlsVisible = true
}

internal fun PlayerScreenRuntime.activateHoldToSpeed() {
    if (!playerSettingsUiState.holdToSpeedEnabled) return
    val controller = playerController ?: return
    if (speedBoostRestoreSpeed != null) return

    val targetSpeed = playerSettingsUiState.holdToSpeedValue
    val currentSpeed = playbackSnapshot.playbackSpeed
    if (abs(currentSpeed - targetSpeed) < 0.01f) return

    isHoldToSpeedGestureActive = true
    speedBoostRestoreSpeed = currentSpeed
    controller.setPlaybackSpeed(targetSpeed)
    liveGestureFeedback = GestureFeedbackState(
        message = formatPlaybackSpeedLabel(targetSpeed),
        icon = GestureFeedbackIcon.Speed,
    )
    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

internal fun PlayerScreenRuntime.deactivateHoldToSpeed() {
    isHoldToSpeedGestureActive = false
    val restoreSpeed = speedBoostRestoreSpeed ?: return
    playerController?.setPlaybackSpeed(restoreSpeed)
    speedBoostRestoreSpeed = null
    liveGestureFeedback = null
}

@Composable
internal fun PlayerScreenRuntime.rememberSurfaceGestureCallbacks(): PlayerSurfaceGestureCallbacks {
    val onSurfaceTap = rememberUpdatedState { offset: Offset ->
        if (playerControlsLocked) {
            revealLockedOverlay()
            return@rememberUpdatedState
        }
        val centerStart = layoutSize.width * PlayerLeftGestureBoundary
        val centerEnd = layoutSize.width * PlayerRightGestureBoundary
        if (controlsVisible && offset.x in centerStart..centerEnd) {
            controlsVisible = false
        } else {
            controlsVisible = !controlsVisible
        }
    }
    val onSurfaceDoubleTap = rememberUpdatedState { offset: Offset ->
        if (playerControlsLocked) {
            revealLockedOverlay()
            return@rememberUpdatedState
        }
        when {
            offset.x < layoutSize.width * PlayerLeftGestureBoundary -> {
                if (playerSettingsUiState.gestureDoubleTapSeekEnabled) {
                    handleDoubleTapSeek(PlayerSeekDirection.Backward)
                } else {
                    controlsVisible = !controlsVisible
                }
            }
            offset.x > layoutSize.width * PlayerRightGestureBoundary -> {
                if (playerSettingsUiState.gestureDoubleTapSeekEnabled) {
                    handleDoubleTapSeek(PlayerSeekDirection.Forward)
                } else {
                    controlsVisible = !controlsVisible
                }
            }
            else -> controlsVisible = !controlsVisible
        }
    }
    return PlayerSurfaceGestureCallbacks(
        onSurfaceTap = onSurfaceTap,
        onSurfaceDoubleTap = onSurfaceDoubleTap,
        activateHoldToSpeed = rememberUpdatedState(::activateHoldToSpeed),
        deactivateHoldToSpeed = rememberUpdatedState(::deactivateHoldToSpeed),
        showHorizontalSeekPreview = rememberUpdatedState(::showHorizontalSeekPreview),
        showBrightnessFeedback = rememberUpdatedState(::showBrightnessFeedback),
        showVolumeFeedback = rememberUpdatedState(::showVolumeFeedback),
        clearLiveGestureFeedback = rememberUpdatedState(::clearLiveGestureFeedback),
        revealLockedOverlay = rememberUpdatedState(::revealLockedOverlay),
        isHoldToSpeedGestureActive = rememberUpdatedState(isHoldToSpeedGestureActive),
        gestureSwipeSeekEnabled = rememberUpdatedState(playerSettingsUiState.gestureSwipeSeekEnabled),
        gestureBrightnessEnabled = rememberUpdatedState(playerSettingsUiState.gestureBrightnessEnabled),
        gestureVolumeEnabled = rememberUpdatedState(playerSettingsUiState.gestureVolumeEnabled),
        playerControlsLocked = rememberUpdatedState(playerControlsLocked),
        currentPositionMs = rememberUpdatedState(playbackSnapshot.positionMs.coerceAtLeast(0L)),
        currentDurationMs = rememberUpdatedState(playbackSnapshot.durationMs),
        commitHorizontalSeek = rememberUpdatedState { targetPositionMs: Long ->
            lastManualSkipSeekPositions = playbackSnapshot.positionMs to targetPositionMs
            playerController?.seekTo(targetPositionMs, PlayerSeekPrecision.Fast)
            scheduleProgressSyncAfterSeek()
        },
    )
}
