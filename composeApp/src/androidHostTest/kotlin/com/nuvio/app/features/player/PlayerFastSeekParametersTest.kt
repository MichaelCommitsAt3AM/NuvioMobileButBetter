package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerFastSeekParametersTest {

    @Test
    fun `forward double-tap caps the backward tolerance at half the distance`() {
        val params = fastSeekParameters(fromMs = 50_000L, targetMs = 52_000L)

        assertEquals(1_000_000L, params.toleranceBeforeUs)
        assertEquals(PlayerFastSeekToleranceMs * 1_000L, params.toleranceAfterUs)
    }

    @Test
    fun `backward seek caps the forward tolerance at half the distance`() {
        val params = fastSeekParameters(fromMs = 50_000L, targetMs = 40_000L)

        assertEquals(PlayerFastSeekToleranceMs * 1_000L, params.toleranceBeforeUs)
        assertEquals(PlayerFastSeekToleranceMs * 1_000L, params.toleranceAfterUs)
    }

    @Test
    fun `seek to the current position may only move forward`() {
        val params = fastSeekParameters(fromMs = 50_000L, targetMs = 50_000L)

        assertEquals(0L, params.toleranceBeforeUs)
        assertEquals(PlayerFastSeekToleranceMs * 1_000L, params.toleranceAfterUs)
    }
}
