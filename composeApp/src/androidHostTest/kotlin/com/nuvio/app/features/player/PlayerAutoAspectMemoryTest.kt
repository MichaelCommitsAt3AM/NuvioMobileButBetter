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
    fun forgettingAutoReturnsTheSeasonToFit() {
        val runtime = seriesRuntime(season = 1, episode = 1)
        PlayerAutoAspectMemory.setRemembered(runtime.autoAspectScope, true)
        PlayerAutoAspectMemory.setRemembered(runtime.autoAspectScope, false)

        runtime.enterEpisode(season = 1, episode = 2)
        assertEquals(PlayerResizeMode.Fit, runtime.resizeMode)
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
