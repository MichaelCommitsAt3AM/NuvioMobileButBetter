package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun fitSwitchesToAutoOnItsOwnOnceBarsAreFound() {
        val runtime = seriesRuntime(season = 1, episode = 1).onPhoneScreen()
        runtime.resetIdentityStateIfNeeded()
        assertEquals(PlayerResizeMode.Fit, runtime.resizeMode)

        runtime.reportBars(thinBars)
        assertEquals(PlayerResizeMode.Auto, runtime.resizeMode)

        // ...and the season now opens straight on Auto, with those bars.
        runtime.enterEpisode(season = 1, episode = 2)
        assertEquals(PlayerResizeMode.Auto, runtime.resizeMode)
        assertEquals(thinBars, runtime.effectiveAutoBars(frameWidth = 1920, frameHeight = 1080))
    }

    @Test
    fun autoSwitchIsOffWhenTheSettingIs() {
        val runtime = seriesRuntime(season = 1, episode = 1).onPhoneScreen()
        runtime.playerSettingsUiState = PlayerSettingsUiState(autoSwitchToAutoAspect = false)
        runtime.resetIdentityStateIfNeeded()

        runtime.reportBars(thinBars)
        assertEquals(PlayerResizeMode.Fit, runtime.resizeMode)
    }

    @Test
    fun autoSwitchLeavesFillAndZoomAlone() {
        val runtime = seriesRuntime(season = 1, episode = 1).onPhoneScreen()
        runtime.resetIdentityStateIfNeeded()
        runtime.resizeMode = PlayerResizeMode.Zoom

        runtime.reportBars(thinBars)
        assertEquals(PlayerResizeMode.Zoom, runtime.resizeMode)
    }

    @Test
    fun barsTooWideToZoomDoNotSwitch() {
        val runtime = seriesRuntime(season = 1, episode = 1).onPhoneScreen()
        runtime.resetIdentityStateIfNeeded()

        // A scope picture wider than the phone: Fit already serves it, so Auto isn't offered.
        runtime.reportBars(bars)
        assertEquals(PlayerResizeMode.Fit, runtime.resizeMode)
    }

    @Test
    fun leavingAutoByHandStopsTheAutoSwitchForTheSeason() {
        val runtime = seriesRuntime(season = 1, episode = 1).onPhoneScreen()
        runtime.resetIdentityStateIfNeeded()
        runtime.reportBars(thinBars)
        assertEquals(PlayerResizeMode.Auto, runtime.resizeMode)

        runtime.cycleResizeMode()
        assertNotEquals(PlayerResizeMode.Auto, runtime.resizeMode)
        assertTrue(PlayerAutoAspectMemory.isDeclined(runtime.autoAspectScope))

        runtime.resizeMode = PlayerResizeMode.Fit
        runtime.enterEpisode(season = 1, episode = 2)
        runtime.reportBars(thinBars)
        assertEquals(PlayerResizeMode.Fit, runtime.resizeMode)

        // Another season is untouched.
        runtime.enterEpisode(season = 2, episode = 1)
        runtime.reportBars(thinBars)
        assertEquals(PlayerResizeMode.Auto, runtime.resizeMode)
    }

    @Test
    fun choosingAutoAgainClearsTheDecline() {
        val runtime = seriesRuntime(season = 1, episode = 1)
        PlayerAutoAspectMemory.decline(runtime.autoAspectScope)
        PlayerAutoAspectMemory.setRemembered(runtime.autoAspectScope, true)

        assertFalse(PlayerAutoAspectMemory.isDeclined(runtime.autoAspectScope))
        runtime.enterEpisode(season = 1, episode = 2)
        assertEquals(PlayerResizeMode.Auto, runtime.resizeMode)
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

    /** ~2:1 picture in a 16:9 frame: narrower than a 20:9 phone, so Auto zooms it (~1.14x). */
    private val thinBars = PlayerVideoBars(topFraction = 0.06f, bottomFraction = 0.06f)

    /** A 20:9 phone in landscape, with a scope the gesture feedback can launch on. */
    private fun PlayerScreenRuntime.onPhoneScreen() = apply {
        layoutSize = IntSize(2400, 1080)
        scope = CoroutineScope(Dispatchers.Unconfined)
    }

    /** What the player does with each engine snapshot. */
    private fun PlayerScreenRuntime.reportBars(bars: PlayerVideoBars) {
        playbackSnapshot = PlayerPlaybackSnapshot(videoWidth = 1920, videoHeight = 1080)
        onVideoBarsReported(bars)
        switchToAutoIfBarsFound()
    }

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
