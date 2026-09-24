package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import androidx.compose.ui.Modifier
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerAutoAspectMemoryTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
    }

    @Test
    fun opensOnFitWhenNothingIsRemembered() {
        val runtime = seriesRuntime(season = 1, episode = 1)
        runtime.resetIdentityStateIfNeeded()
        assertEquals(PlayerResizeMode.Fit, runtime.resizeMode)
    }

    @Test
    fun autoCarriesAcrossTheSeasonButNotIntoTheNext() {
        val runtime = seriesRuntime(season = 1, episode = 1)
        runtime.resetIdentityStateIfNeeded()
        PlayerAutoAspectMemory.setRemembered(runtime.autoAspectScope, true)

        runtime.enterEpisode(season = 1, episode = 2)
        assertEquals(PlayerResizeMode.Auto, runtime.resizeMode)

        runtime.enterEpisode(season = 2, episode = 1)
        assertEquals(PlayerResizeMode.Fit, runtime.resizeMode)

        runtime.enterEpisode(season = 1, episode = 3)
        assertEquals(PlayerResizeMode.Auto, runtime.resizeMode)
    }

    @Test
    fun rememberedAutoSurvivesANewPlayerSession() {
        PlayerAutoAspectMemory.setRemembered(seriesRuntime(season = 1, episode = 1).autoAspectScope, true)

        // A fresh runtime reading storage again stands in for reopening the app.
        val reopened = seriesRuntime(season = 1, episode = 4)
        reopened.resetIdentityStateIfNeeded()
        assertEquals(PlayerResizeMode.Auto, reopened.resizeMode)
    }

    @Test
    fun rememberedAutoStaysSelectedWhileTheNewEpisodeHasNoBarsYet() {
        val runtime = seriesRuntime(season = 1, episode = 1)
        PlayerAutoAspectMemory.setRemembered(runtime.autoAspectScope, true)
        runtime.resetIdentityStateIfNeeded()
        runtime.onVideoBarsReported(PlayerVideoBars(topFraction = 0.1f, bottomFraction = 0.1f))

        runtime.enterEpisode(season = 1, episode = 2)
        runtime.onVideoBarsReported(null)
        assertEquals(PlayerResizeMode.Auto, runtime.resizeMode)
    }

    @Test
    fun barsMeasuredOnAutoZoomTheNextEpisodeImmediately() {
        val runtime = seriesRuntime(season = 1, episode = 1)
        PlayerAutoAspectMemory.setRemembered(runtime.autoAspectScope, true)
        runtime.resetIdentityStateIfNeeded()
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(videoWidth = 1920, videoHeight = 1080)
        runtime.onVideoBarsReported(bars)

        runtime.enterEpisode(season = 1, episode = 2)
        assertEquals(PlayerResizeMode.Auto, runtime.resizeMode)
        assertNull(runtime.autoBars)
        assertEquals(bars, runtime.effectiveAutoBars(frameWidth = 1920, frameHeight = 1080))
        // Same shape at another resolution still matches: bars are fractions of the frame.
        assertEquals(bars, runtime.effectiveAutoBars(frameWidth = 1280, frameHeight = 720))
    }

    @Test
    fun rememberedBarsSkipAFrameOfAnotherShape() {
        val runtime = seriesRuntime(season = 1, episode = 1)
        PlayerAutoAspectMemory.rememberBars(runtime.autoAspectScope, bars, frameWidth = 1920, frameHeight = 1080)

        runtime.enterEpisode(season = 1, episode = 2)
        assertEquals(PlayerResizeMode.Auto, runtime.resizeMode)
        assertNull(runtime.effectiveAutoBars(frameWidth = 1920, frameHeight = 800))
    }

    @Test
    fun aPictureWithNoBarsDropsRememberedBarsButKeepsAuto() {
        val runtime = seriesRuntime(season = 1, episode = 1)
        PlayerAutoAspectMemory.rememberBars(runtime.autoAspectScope, bars, frameWidth = 1920, frameHeight = 1080)

        runtime.enterEpisode(season = 1, episode = 2)
        runtime.onVideoBarsAbsent()
        assertNull(runtime.effectiveAutoBars(frameWidth = 1920, frameHeight = 1080))
        assertEquals(PlayerResizeMode.Auto, runtime.resizeMode)
    }

    @Test
    fun theEpisodesOwnBarsWinOverRememberedOnes() {
        val runtime = seriesRuntime(season = 1, episode = 1)
        PlayerAutoAspectMemory.rememberBars(runtime.autoAspectScope, bars, frameWidth = 1920, frameHeight = 1080)
        val ownBars = PlayerVideoBars(topFraction = 0.05f, bottomFraction = 0.05f)

        runtime.enterEpisode(season = 1, episode = 2)
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(videoWidth = 1920, videoHeight = 1080)
        runtime.onVideoBarsReported(ownBars)
        assertEquals(ownBars, runtime.effectiveAutoBars(frameWidth = 1920, frameHeight = 1080))

        // ...and become what the following episode starts from.
        runtime.enterEpisode(season = 1, episode = 3)
        assertEquals(ownBars, runtime.effectiveAutoBars(frameWidth = 1920, frameHeight = 1080))
    }

    @Test
    fun forgettingAutoReturnsTheSeasonToFit() {
        val runtime = seriesRuntime(season = 1, episode = 1)
        PlayerAutoAspectMemory.setRemembered(runtime.autoAspectScope, true)
        PlayerAutoAspectMemory.setRemembered(runtime.autoAspectScope, false)

        runtime.enterEpisode(season = 1, episode = 2)
        assertEquals(PlayerResizeMode.Fit, runtime.resizeMode)
    }

    private val bars = PlayerVideoBars(topFraction = 0.12f, bottomFraction = 0.12f)

    private fun PlayerScreenRuntime.enterEpisode(season: Int, episode: Int) {
        activeSeasonNumber = season
        activeEpisodeNumber = episode
        activeVideoId = "tt1234567:$season:$episode"
        resetIdentityStateIfNeeded()
    }

    private fun seriesRuntime(season: Int, episode: Int) = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Title",
            sourceUrl = "https://example.com/video.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Provider",
            streamTitle = "Source",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = null,
            episodeThumbnail = null,
            contentType = "series",
            videoId = "tt1234567:$season:$episode",
            parentMetaId = "tt1234567",
            parentMetaType = "series",
            providerAddonId = null,
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    )
}
