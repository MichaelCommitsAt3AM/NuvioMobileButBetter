package com.nuvio.app.features.details.components

import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
import android.widget.FrameLayout
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import com.nuvio.app.features.details.HeroTrailerPlaybackConfig
import com.nuvio.app.features.player.PlatformPlaybackDataSourceFactory
import com.nuvio.app.features.player.PlayerDiskCache
import com.nuvio.app.features.trailer.LetterboxDetector
import com.nuvio.app.features.trailer.LetterboxSampler
import com.nuvio.app.features.trailer.LetterboxTracker
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestPlayWhenReady = rememberUpdatedState(playWhenReady)
    val latestOnReady = rememberUpdatedState(onReady)
    val latestOnEnded = rememberUpdatedState(onEnded)
    val latestOnError = rememberUpdatedState(onError)
    val latestOnProgress = rememberUpdatedState(onProgress)
    val latestOnBuffering = rememberUpdatedState(onBufferingChanged)
    var playerContainer by remember { mutableStateOf<HeroTrailerTextureContainer?>(null) }

    val dataSourceFactory = remember(context) {
        PlatformPlaybackDataSourceFactory.create(
            context = context,
            defaultRequestHeaders = emptyMap(),
            defaultResponseHeaders = emptyMap(),
            useYoutubeChunkedPlayback = true,
        )
    }
    val trailerKey = "$sourceUrl|${sourceAudioUrl.orEmpty()}"
    // The player outlives this composable: scrolling the hero off screen hands it back to the
    // pool, and scrolling back returns the same buffered player at the same position.
    val exoPlayer = remember(trailerKey, dataSourceFactory) {
        HeroTrailerPlayerPool.acquire(trailerKey) {
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    if (!sourceAudioUrl.isNullOrBlank()) {
                        setMediaSource(
                            MergingMediaSource(
                                mediaSourceFactory.createMediaSource(MediaItem.fromUri(sourceUrl)),
                                mediaSourceFactory.createMediaSource(MediaItem.fromUri(sourceAudioUrl)),
                            ),
                        )
                    } else {
                        setMediaItem(MediaItem.fromUri(sourceUrl))
                    }
                    repeatMode = Player.REPEAT_MODE_OFF
                    // The sound eases in once playback is running (see the fade effect below).
                    volume = 0f
                    if (startPositionMs > 0L) seekTo(startPositionMs)
                    prepare()
                }
        }
    }
    var hasRenderedFirstFrame by remember(exoPlayer) { mutableStateOf(false) }
    var letterboxZoom by remember(exoPlayer) { mutableFloatStateOf(1f) }
    val letterboxZoomState = animateFloatAsState(
        targetValue = letterboxZoom,
        animationSpec = tween(durationMillis = 400),
        label = "heroTrailerLetterboxZoom",
    )

    DisposableEffect(exoPlayer, lifecycleOwner) {
        fun detachVideoSurface() {
            playerContainer?.detachPlayer(exoPlayer)
            playerContainer?.alpha = 0f
        }

        val listener = object : Player.Listener {
            private var readyReported = false
            private var endedReported = false

            override fun onPlaybackStateChanged(playbackState: Int) {
                latestOnBuffering.value(playbackState == Player.STATE_BUFFERING)
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (!readyReported) {
                            readyReported = true
                            Logger.withTag("HeroTrailer").d { "player ready" }
                            latestOnReady.value()
                        }
                    }
                    Player.STATE_ENDED -> {
                        if (!endedReported) {
                            endedReported = true
                            Logger.withTag("HeroTrailer").d { "player ended" }
                            latestOnEnded.value()
                        }
                    }
                    else -> Unit
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                playerContainer?.setVideoSize(videoSize)
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
            }

            override fun onPlayerError(error: PlaybackException) {
                Logger.withTag("HeroTrailer").w(error) { "player error: ${error.errorCodeName}" }
                detachVideoSurface()
                latestOnError.value()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (latestPlayWhenReady.value && exoPlayer.playbackState != Player.STATE_ENDED) {
                        playerContainer?.attachPlayer(exoPlayer)
                        playerContainer?.alpha = 1f
                        exoPlayer.play()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    exoPlayer.pause()
                    detachVideoSurface()
                }
                else -> Unit
            }
        }
        exoPlayer.addListener(listener)
        // A pooled player may already be buffered (or finished), in which case no state change
        // will fire for this new listener, so report where it stands.
        listener.onPlaybackStateChanged(exoPlayer.playbackState)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.removeListener(listener)
            detachVideoSurface()
            // Keep the player for a while instead of releasing it, so scrolling back is instant.
            HeroTrailerPlayerPool.release(trailerKey)
            playerContainer = null
        }
    }

    LaunchedEffect(exoPlayer, playWhenReady) {
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            return@LaunchedEffect
        }
        exoPlayer.playWhenReady = playWhenReady
        if (playWhenReady) {
            playerContainer?.attachPlayer(exoPlayer)
            playerContainer?.alpha = 1f
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    // Fork-only: ease the sound in whenever playback starts or resumes instead of snapping on.
    LaunchedEffect(exoPlayer, muted, playWhenReady) {
        // Only claim audio focus once the user unmutes, so a muted trailer never interrupts music.
        exoPlayer.setAudioAttributes(AudioAttributes.DEFAULT, !muted)
        if (muted) {
            exoPlayer.volume = 0f
            return@LaunchedEffect
        }
        if (!playWhenReady) return@LaunchedEffect
        exoPlayer.volume = 0f
        val steps = SOUND_FADE_IN_MS / SOUND_FADE_STEP_MS
        for (step in 1..steps) {
            delay(SOUND_FADE_STEP_MS)
            exoPlayer.volume = step.toFloat() / steps
        }
        exoPlayer.volume = 1f
    }

    // Fork-only: jump to the position the user dragged the progress bar to.
    LaunchedEffect(exoPlayer, seekRequest?.token) {
        seekRequest?.let { exoPlayer.seekTo(it.positionMs) }
    }

    // Fork-only: report position and duration for the remaining-time pill and progress line.
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(HeroTrailerPlaybackConfig.PROGRESS_INTERVAL_MS)
            val duration = exoPlayer.duration
            if (duration > 0L && exoPlayer.playbackState != Player.STATE_IDLE) {
                latestOnProgress.value(exoPlayer.currentPosition.coerceAtLeast(0L), duration)
            }
        }
    }

    LaunchedEffect(exoPlayer, hasRenderedFirstFrame) {
        letterboxZoom = 1f
        if (!hasRenderedFirstFrame || !LETTERBOX_ZOOM_ENABLED) return@LaunchedEffect
        val tracker = LetterboxTracker()
        val sampler = LetterboxSampler()
        try {
            while (true) {
                delay(LetterboxDetector.SAMPLE_INTERVAL_MS)
                if (LetterboxDetector.isSampleWindowOver(exoPlayer.currentPosition, exoPlayer.duration)) break
                if (!exoPlayer.isPlaying) continue
                val textureView = playerContainer?.textureView ?: continue
                val bar = sampler.sample(textureView) ?: continue
                letterboxZoom = tracker.onSample(bar) ?: continue
                break
            }
        } finally {
            sampler.release()
        }
    }

    AndroidView(
        modifier = modifier
            .clipToBounds()
            .graphicsLayer {
                scaleX = letterboxZoomState.value
                scaleY = letterboxZoomState.value
            },
        factory = { viewContext ->
            HeroTrailerTextureContainer(viewContext).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                attachPlayer(exoPlayer)
                playerContainer = this
            }
        },
        update = { container ->
            playerContainer = container
            if (playWhenReady) {
                container.attachPlayer(exoPlayer)
            }
        },
    )
}

private class HeroTrailerTextureContainer(
    context: Context,
) : FrameLayout(context) {
    val textureView = TextureView(context)
    private val textureTransform = Matrix()
    private var videoAspectRatio = 16f / 9f
    private var attachedPlayer: ExoPlayer? = null

    init {
        clipChildren = true
        clipToPadding = true
        isFocusable = false
        addView(
            textureView,
            LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        textureView.isFocusable = false
        textureView.isClickable = false
    }

    fun attachPlayer(player: ExoPlayer) {
        if (attachedPlayer === player) return
        attachedPlayer?.clearVideoTextureView(textureView)
        attachedPlayer = player
        player.setVideoTextureView(textureView)
    }

    fun detachPlayer(player: ExoPlayer) {
        if (attachedPlayer === player) {
            player.clearVideoTextureView(textureView)
            attachedPlayer = null
        }
    }

    fun setVideoSize(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        videoAspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        updateTextureTransform()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateTextureTransform()
    }

    private fun updateTextureTransform() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || videoAspectRatio <= 0f) return

        val viewAspectRatio = viewWidth / viewHeight
        textureTransform.reset()
        if (viewAspectRatio > videoAspectRatio) {
            // Fork-only: fit (never crop) so the trailer keeps its original aspect ratio.
            val scaleX = videoAspectRatio / viewAspectRatio
            textureTransform.setScale(scaleX, 1f, viewWidth / 2f, viewHeight / 2f)
        } else {
            val scaleY = viewAspectRatio / videoAspectRatio
            textureTransform.setScale(1f, scaleY, viewWidth / 2f, viewHeight / 2f)
        }
        textureView.setTransform(textureTransform)
    }
}

private const val SOUND_FADE_IN_MS = 900L
private const val SOUND_FADE_STEP_MS = 30L

// Fork-only: the trailer is shown whole, with its own bezels, never zoomed to hide them.
private const val LETTERBOX_ZOOM_ENABLED = false
