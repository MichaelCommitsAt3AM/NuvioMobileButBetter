package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KidsProfileEditPolicyTest {
    private val configured = NuvioProfile(
        profileIndex = 2, name = "Kids", kidsEnabled = true, kidsMaxRating = "PG",
        kidsConfigId = "a".repeat(43), kidsServiceUrl = "https://kids-screen.vercel.app",
    )

    @Test fun cosmeticEditsReuseTheSavedConfigurationWithoutAnyLocalKey() {
        assertFalse(kidsConfigurationNeedsUpdate(configured.copy(name = "New name", avatarId = "avatar"), true, "PG", ""))
        assertFalse(kidsConfigurationNeedsUpdate(configured, true, "PG", "  "))
    }

    @Test fun ratingAndReplacementKeyChangesStillUpdateKidsConfiguration() {
        assertTrue(kidsConfigurationNeedsUpdate(configured, true, "G", ""))
        assertTrue(kidsConfigurationNeedsUpdate(configured, true, "PG", "replacement-key"))
        assertTrue(kidsConfigurationNeedsUpdate(configured, false, "PG", ""))
    }

    @Test fun newAndIncompleteKidsProfilesMustFinishSetup() {
        assertTrue(kidsConfigurationNeedsUpdate(null, true, "G", ""))
        assertTrue(kidsConfigurationNeedsUpdate(configured.copy(kidsEnabled = false), true, "PG", ""))
        assertTrue(kidsConfigurationNeedsUpdate(configured.copy(kidsConfigId = null), true, "PG", ""))
        assertFalse(kidsConfigurationNeedsUpdate(null, false, "G", ""))
        assertFalse(kidsConfigurationNeedsUpdate(configured.copy(kidsEnabled = false), false, "PG", ""))
    }
}
