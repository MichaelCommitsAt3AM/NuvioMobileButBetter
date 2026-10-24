package com.nuvio.app.features.tmdb

import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KidsTmdbProfileStorageTest {
    @Test fun configuresTheChildWhileTheParentRemainsActiveAndPreservesOptOut() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("nuvio_tmdb_settings", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        TmdbSettingsStorage.initialize(context)
        try {
            TmdbSettingsStorage.saveKidsProfileSettings(1, "parent-key", false, useCredits = true, useProductions = true, useNetworks = true)
            TmdbSettingsStorage.saveKidsProfileSettings(2, null, false, useCredits = true, useProductions = true, useNetworks = true)
            TmdbSettingsRepository.configureKidsProfile(2, "  validated-child-key  ", enableByDefault = true)
            // The validated key is held by KidsScreen for enrichment; it is not copied to this device.
            assertNull(preferences.getString("tmdb_api_key_2", null))
            assertEquals(true, TmdbSettingsStorage.loadEnabledForProfile(2))
            assertEquals(false, TmdbSettingsStorage.loadUseCreditsForProfile(2))
            assertEquals(false, TmdbSettingsStorage.loadUseProductionsForProfile(2))
            assertEquals(false, TmdbSettingsStorage.loadUseNetworksForProfile(2))
            assertEquals(true, TmdbSettingsStorage.loadUseProductionsForProfile(1))
            assertEquals(true, TmdbSettingsStorage.loadUseNetworksForProfile(1))
            assertEquals(true, TmdbSettingsStorage.loadUseCreditsForProfile(1))
            assertEquals("parent-key", preferences.getString("tmdb_api_key_1", null))
            assertEquals(false, TmdbSettingsStorage.loadEnabledForProfile(1))

            TmdbSettingsStorage.saveKidsProfileSettings(2, null, false, useCredits = true, useProductions = true, useNetworks = true)
            TmdbSettingsRepository.configureKidsProfile(2, "replacement-key", enableByDefault = false)
            assertEquals(false, TmdbSettingsStorage.loadEnabledForProfile(2))
            assertNull(preferences.getString("tmdb_api_key_2", null))
            assertEquals(true, TmdbSettingsStorage.loadUseCreditsForProfile(2))
            assertEquals(true, TmdbSettingsStorage.loadUseProductionsForProfile(2))
            assertEquals(true, TmdbSettingsStorage.loadUseNetworksForProfile(2))

            TmdbSettingsRepository.configureKidsProfile(2, "", enableByDefault = true)
            assertEquals(false, TmdbSettingsStorage.loadEnabledForProfile(2))
            assertNull(preferences.getString("tmdb_api_key_2", null))
            assertEquals(true, TmdbSettingsStorage.loadUseCreditsForProfile(2))
            assertEquals(true, TmdbSettingsStorage.loadUseProductionsForProfile(2))
            assertEquals(true, TmdbSettingsStorage.loadUseNetworksForProfile(2))
            assertEquals("parent-key", preferences.getString("tmdb_api_key_1", null))
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
