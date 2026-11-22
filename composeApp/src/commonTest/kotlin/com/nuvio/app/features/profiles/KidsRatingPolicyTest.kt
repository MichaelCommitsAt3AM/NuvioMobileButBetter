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
            batch.associateWith { it.id == "tt1" || it.id == "tt401" }
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
        assertEquals(refs, KidsProfileRepository.check(original, refs) { _, batch -> batch.associateWith { true } })
        assertEquals(refs, KidsProfileRepository.check(original, refs) { _, _ -> error("must use cache") })
        for (changed in listOf(original.copy(kidsMaxRating = "PG"), original.copy(userId = "another-parent"), original.copy(kidsConfigId = "other-config"))) {
            assertTrue(KidsProfileRepository.check(changed, refs) { _, batch -> batch.associateWith { false } }.isEmpty())
        }
    }

    @Test fun outagesAreReportedApartFromRatingDenials() = runBlocking {
        val undecided = KidsProfileRepository.Ref("movie", "tt7")
        val denied = KidsProfileRepository.Ref("movie", "tt8")
        val result = KidsProfileRepository.checkDetailed(profile("outage"), setOf(undecided, denied)) { _, _ -> mapOf(denied to false) }
        assertEquals(emptySet(), result.allowed)
        assertEquals(setOf(undecided), result.unavailable)
        val failed = KidsProfileRepository.checkDetailed(profile("outage-request"), setOf(denied)) { _, _ -> error("offline") }
        assertEquals(setOf(denied), failed.unavailable)
    }

    @Test fun batchesAreRequestedConcurrently() = runBlocking {
        val refs = (1..400).map { KidsProfileRepository.Ref("movie", "tt$it") }.toSet()
        val bothStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        var started = 0
        val result = kotlinx.coroutines.withTimeout(5_000) {
            KidsProfileRepository.check(profile("parallel"), refs) { _, batch ->
                if (++started == 2) bothStarted.complete(Unit)
                bothStarted.await()
                batch.associateWith { true }
            }
        }
        assertEquals(refs, result)
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
