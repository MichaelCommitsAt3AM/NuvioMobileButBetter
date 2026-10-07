package com.nuvio.app.features.player

import android.app.Application
import androidx.media3.exoplayer.analytics.PlayerId
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerBufferCompatibilityTest {
    @AfterTest
    fun resetNativeMode() {
        NuvioExoPlayerPerformanceHelper.enabled = false
    }

    @Test
    fun defaultHeapModePreservesTheForkBitrateAwareRewindWindow() {
        NuvioExoPlayerPerformanceHelper.enabled = false
        val control = assertIs<DynamicBackBufferLoadControl>(
            NuvioExoPlayerPerformanceHelper.buildLoadControl(RuntimeEnvironment.getApplication()),
        )
        control.updateEstimatedBitrate(4_500_000_000L, 3_000_000L)
        assertEquals(15_000_000L, control.getBackBufferDurationUs(PlayerId.UNSET))
    }

    @Test
    fun explicitHeapSettingsUseTheRequestedRewindDuration() {
        NuvioExoPlayerPerformanceHelper.enabled = false
        val control = NuvioExoPlayerPerformanceHelper.buildLoadControl(
            RuntimeEnvironment.getApplication(),
            PlaybackBufferSettings(enabled = true, backBufferDurationMs = 20_000),
        )
        assertFalse(control is DynamicBackBufferLoadControl)
        assertEquals(20_000_000L, control.getBackBufferDurationUs(PlayerId.UNSET))
    }

    @Test
    fun nativeModeUsesUpstreamAllocatorAndRespectsItsBackBufferCeiling() {
        NuvioExoPlayerPerformanceHelper.enabled = true
        val control = NuvioExoPlayerPerformanceHelper.buildLoadControl(
            RuntimeEnvironment.getApplication(),
            PlaybackBufferSettings(enabled = true, minBufferMs = 30_000, backBufferDurationMs = 30_000),
        )
        assertFalse(control is DynamicBackBufferLoadControl)
        assertEquals(15_000_000L, control.getBackBufferDurationUs(PlayerId.UNSET))
    }
}
