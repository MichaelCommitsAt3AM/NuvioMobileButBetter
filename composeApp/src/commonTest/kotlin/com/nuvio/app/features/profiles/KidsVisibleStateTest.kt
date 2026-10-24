package com.nuvio.app.features.profiles

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class KidsVisibleStateTest {
    private data class Content(val id: String = "", val text: String = "")
    private val empty = Content()
    private val child = NuvioProfile(userId = "account", profileIndex = 2, kidsEnabled = true)

    @Test fun keepsOnlyTheVerifiedVersionVisibleWhileEnrichmentIsChecked() = runTest {
        val source = MutableStateFlow(Content("movie", "base"))
        val profiles = MutableStateFlow(ProfileState(activeProfile = child))
        var gate: CompletableDeferred<Unit>? = null
        val visible = kidsVisibleState(source, backgroundScope, empty, profiles,
            sameContent = { old, next -> old.id == next.id },
        ) { value -> gate?.await(); value.copy(text = "verified ${value.text}") }
        val seen = mutableListOf<Content>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { visible.collect { seen += it } }
        runCurrent()
        assertEquals(Content("movie", "verified base"), visible.value)
        seen.clear()
        gate = CompletableDeferred()
        source.value = Content("movie", "enriched")
        runCurrent()
        assertEquals(Content("movie", "verified base"), visible.value)
        assertFalse(seen.any { it == empty || it.text == "enriched" })
        gate!!.complete(Unit)
        runCurrent()
        assertEquals(Content("movie", "verified enriched"), visible.value)
        assertFalse(seen.contains(empty))
    }

    @Test fun newTitlesAndPolicyOrProfileChangesClearBeforeVerification() = runTest {
        val source = MutableStateFlow(Content("first", "base"))
        val profiles = MutableStateFlow(ProfileState(activeProfile = child))
        var gate: CompletableDeferred<Unit>? = null
        val visible = kidsVisibleState(source, backgroundScope, empty, profiles,
            sameContent = { old, next -> old.id == next.id },
        ) { value -> gate?.await(); value.copy(text = "verified") }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { visible.collect {} }
        runCurrent()
        gate = CompletableDeferred()
        source.value = Content("second", "unverified")
        runCurrent()
        assertEquals(empty, visible.value)
        gate!!.complete(Unit)
        runCurrent()
        assertEquals(Content("second", "verified"), visible.value)
        gate = CompletableDeferred()
        profiles.value = ProfileState(activeProfile = child.copy(kidsMaxRating = "PG"))
        runCurrent()
        assertEquals(empty, visible.value)
        gate!!.complete(Unit)
        runCurrent()
        gate = CompletableDeferred()
        profiles.value = ProfileState(activeProfile = child.copy(userId = "other-account"))
        runCurrent()
        assertEquals(empty, visible.value)
    }

    @Test fun cancelledEnrichmentCannotOverwriteTheNextTitle() = runTest {
        val source = MutableStateFlow(Content("first", "base"))
        val profiles = MutableStateFlow(ProfileState(activeProfile = child))
        val oldResponse = CompletableDeferred<Unit>()
        val visible = kidsVisibleState(source, backgroundScope, empty, profiles,
            sameContent = { old, next -> old.id == next.id },
        ) { value ->
            if (value.text == "pending") oldResponse.await()
            value.copy(text = "verified")
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { visible.collect {} }
        runCurrent()
        source.value = Content("first", "pending")
        runCurrent()
        source.value = Content("second", "base")
        runCurrent()
        oldResponse.complete(Unit)
        runCurrent()
        assertEquals(Content("second", "verified"), visible.value)
    }

    @Test fun otherKidsConsumersStillClearUnlessTheyOptIntoRetention() = runTest {
        val source = MutableStateFlow(Content("movie", "base"))
        val profiles = MutableStateFlow(ProfileState(activeProfile = child))
        var gate: CompletableDeferred<Unit>? = null
        val visible = kidsVisibleState(source, backgroundScope, empty, profiles) { value -> gate?.await(); value }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { visible.collect {} }
        runCurrent()
        gate = CompletableDeferred()
        source.value = Content("movie", "update")
        runCurrent()
        assertEquals(empty, visible.value)
    }
}
