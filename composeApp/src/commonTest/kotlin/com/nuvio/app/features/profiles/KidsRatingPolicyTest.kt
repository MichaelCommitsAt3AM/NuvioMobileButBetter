package com.nuvio.app.features.profiles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class KidsRatingPolicyTest {
    private fun profile(config: String) = NuvioProfile(
        userId = "test-parent", profileIndex = 2, kidsEnabled = true,
        kidsMaxRating = "G", kidsServiceUrl = "https://kids.example", kidsConfigId = config,
    )

    @Test fun kidsBrowsingUsesOnlyTheConfiguredCatalogAndFailsClosedWithoutConfiguration() {
        val kids = profile("catalog")
        val managed = com.nuvio.app.features.addons.ManagedAddon(kids.kidsManifestUrl()!!)
        val other = com.nuvio.app.features.addons.ManagedAddon("https://other.example/manifest.json")
        val addons = listOf(other, managed)
        assertEquals(listOf(managed), addons.forKidsCatalogs(kids))
        assertTrue(addons.forKidsCatalogs(kids.copy(kidsConfigId = null)).isEmpty())
        assertEquals(addons, addons.forKidsCatalogs(kids.copy(kidsEnabled = false)))
    }

    @Test fun checksAreBatchedAndOnlyExplicitlyAllowedTitlesSurvive() = runBlocking {
        val refs = (1..401).map { KidsProfileRepository.Ref("movie", "tt$it") }.toSet()
        val sizes = mutableListOf<Int>()
        val result = KidsProfileRepository.check(profile("batch"), refs) { _, batch ->
            sizes += batch.size
            batch.filter { it.id == "tt1" || it.id == "tt401" }.toSet()
        }
        assertEquals(listOf(200, 200, 1), sizes)
        assertEquals(setOf(KidsProfileRepository.Ref("movie", "tt1"), KidsProfileRepository.Ref("movie", "tt401")), result)
    }

    @Test fun unavailableServiceAndUnknownContentTypesFailClosed() = runBlocking {
        val refs = setOf(KidsProfileRepository.Ref("movie", "tt1"), KidsProfileRepository.Ref("cloud", "file"))
        assertTrue(KidsProfileRepository.check(profile("offline"), refs) { _, _ -> error("Unavailable") }.isEmpty())
    }

    @Test fun ratingAndAccountChangesNeverReuseAnotherPolicyVerdict() = runBlocking {
        val original = profile("cache")
        val refs = setOf(KidsProfileRepository.Ref("series", "tt123"))
        assertEquals(refs, KidsProfileRepository.check(original, refs) { _, batch -> batch.toSet() })
        assertEquals(refs, KidsProfileRepository.check(original, refs) { _, _ -> error("must use cache") })
        for (changed in listOf(original.copy(kidsMaxRating = "PG"), original.copy(userId = "another-parent"), original.copy(kidsConfigId = "other-config"))) {
            assertTrue(KidsProfileRepository.check(changed, refs) { _, _ -> emptySet() }.isEmpty())
        }
    }

    @Test fun cancellationIsNotTreatedAsARejectedTitle() = runBlocking<Unit> {
        assertFailsWith<CancellationException> {
            KidsProfileRepository.check(profile("cancelled"), setOf(KidsProfileRepository.Ref("movie", "tt1"))) { _, _ -> throw CancellationException() }
        }
    }

    @Test fun serviceAddressRejectsCredentialsPathsAndInsecureRemoteServers() {
        assertEquals("https://kids.example", KidsProfileRepository.normalizeService("https://kids.example/"))
        for (url in listOf("http://kids.example", "https://user:password@kids.example", "https://kids.example/config", "https://kids.example?secret=value")) {
            assertFails { KidsProfileRepository.normalizeService(url) }
        }
    }
}
