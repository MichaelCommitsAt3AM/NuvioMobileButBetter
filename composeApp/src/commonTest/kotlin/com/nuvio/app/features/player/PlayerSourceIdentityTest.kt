package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlayerSourceIdentityTest {
    @Test
    fun resolvedDebridPlaybackKeepsOriginalSourceIdentity() {
        val selected = StreamItem(
            name = "1080p", addonName = "Provider", addonId = "provider",
            clientResolve = StreamClientResolve(service = "debrid", filename = "selected.mkv"),
        )
        val other = selected.copy(clientResolve = selected.clientResolve!!.copy(filename = "other.mkv"))
        val active = initialPlayerSourceIdentityKey(selected, "https://cdn.example/resolved", null, null)
        assertEquals(selected.playerSourceIdentityKey(), active)
        assertNotEquals(other.playerSourceIdentityKey(), active)
    }

    @Test
    fun directStreamsUseTheSameIdentityAsTheReloadedSourcesList() {
        val selected = StreamItem(
            name = "1080p", addonName = "Provider", addonId = "provider", url = "https://example/movie",
            behaviorHints = StreamBehaviorHints(videoHash = "video-hash", filename = "movie.mkv"),
        )
        assertEquals(
            selected.playerSourceIdentityKey(),
            initialPlayerSourceIdentityKey(selected, selected.url!!, null, null),
        )
        assertNotEquals(
            selected.copy(behaviorHints = StreamBehaviorHints(videoHash = "other-hash")).playerSourceIdentityKey(),
            initialPlayerSourceIdentityKey(selected, selected.url, null, null),
        )
    }

    @Test
    fun olderCallersKeepTorrentAndDirectUrlFallbacks() {
        assertEquals("torrent:abc:2", initialPlayerSourceIdentityKey(null, "torrent://ABC", " ABC ", 2))
        assertEquals("url:https://example/movie", initialPlayerSourceIdentityKey(null, "https://example/movie", null, null))
    }

    @Test
    fun currentSourceUsesIdentitySoSameNameAlternativesStayDistinct() {
        val selected = StreamItem(
            name = "1080p", addonName = "Provider", addonId = "provider",
            clientResolve = StreamClientResolve(service = "debrid", filename = "selected.mkv"),
        )
        val other = selected.copy(clientResolve = selected.clientResolve!!.copy(filename = "other.mkv"))
        val active = initialPlayerSourceIdentityKey(selected, "https://cdn.example/resolved", null, null)

        val current = PlayerCurrentStream(url = "https://cdn.example/resolved", name = "1080p", identityKey = active)
        assertEquals(1, listOf(other, selected).currentPlayerStreamIndex(current))
        assertEquals(-1, listOf(other).currentPlayerStreamIndex(current))
    }

    @Test
    fun legacyStreamsFallBackToDirectUrlAndTorrentHash() {
        val direct = StreamItem(
            name = "Direct", addonName = "Provider", addonId = "provider", url = "https://example/movie",
        )
        assertEquals(0, listOf(direct).currentPlayerStreamIndex(PlayerCurrentStream(url = "https://example/movie")))
        assertEquals(-1, listOf(direct).currentPlayerStreamIndex(PlayerCurrentStream(url = "https://example/other")))

        val torrent = StreamItem(
            name = "Torrent", addonName = "Provider", addonId = "provider", infoHash = "ABC", fileIdx = 2,
        )
        val otherFile = torrent.copy(fileIdx = 1)
        val current = PlayerCurrentStream(infoHash = "abc", fileIdx = 2, identityKey = torrent.playerSourceIdentityKey())
        assertEquals(1, listOf(otherFile, torrent).currentPlayerStreamIndex(current))
        assertEquals(-1, listOf(otherFile).currentPlayerStreamIndex(current))
        assertEquals(1, listOf(otherFile, torrent).currentPlayerStreamIndex(current.copy(identityKey = null)))
    }
}
