package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddonRefreshRulesTest {

    private val url = "https://example.test/manifest.json"

    @Test
    fun `previously failed addon is refetched after a sync marks it refreshing`() {
        // First fetch on launch failed (e.g. network not up yet).
        val failed = ManagedAddon(manifestUrl = url, isRefreshing = false, errorMessage = "timeout")

        // Sync pull re-applies the server list: the addon now shows "Refreshing"...
        val pending = failed.toPendingAddon(manifestUrl = url, enabled = true)
        assertTrue(pending.isRefreshing)

        // ...so a fetch must actually be started, or it stays stuck on that badge.
        assertEquals(listOf(url), manifestUrlsNeedingRefresh(listOf(pending), inFlightUrls = emptySet()))
    }

    @Test
    fun `addon with a fetch already in flight is not refetched`() {
        val pending = ManagedAddon(manifestUrl = url, isRefreshing = true)

        assertEquals(emptyList(), manifestUrlsNeedingRefresh(listOf(pending), inFlightUrls = setOf(url)))
    }

    @Test
    fun `new addon from the server is fetched`() {
        val added = null.toPendingAddon(manifestUrl = url, enabled = true)

        assertEquals(listOf(url), manifestUrlsNeedingRefresh(listOf(added), inFlightUrls = emptySet()))
    }

    @Test
    fun `loaded and disabled addons are not fetched`() {
        val loaded = ManagedAddon(
            manifestUrl = url,
            manifest = AddonManifest(
                id = "a",
                name = "A",
                description = "",
                version = "1",
                resources = emptyList(),
                types = emptyList(),
                transportUrl = url,
            ),
        )
        val disabled = ManagedAddon(manifestUrl = "https://other.test/manifest.json", enabled = false)

        assertEquals(emptyList(), manifestUrlsNeedingRefresh(listOf(loaded, disabled), inFlightUrls = emptySet()))
    }
}
