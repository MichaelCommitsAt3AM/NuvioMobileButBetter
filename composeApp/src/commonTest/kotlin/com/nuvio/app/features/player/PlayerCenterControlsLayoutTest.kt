package com.nuvio.app.features.player

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerCenterControlsLayoutTest {
    private val metrics = PlayerLayoutMetrics.fromWidth(600.dp)

    @Test
    fun sourceDetailsDoNotDisplaceCenterControls() {
        val withoutSource = playerCenterControlsBottomPadding(
            metrics = metrics,
            useLegacyLayout = false,
            timelineHeight = 160.dp,
            containerHeight = 300.dp,
        )
        for (sourceHeight in listOf(20.dp, 60.dp, 100.dp)) {
            assertEquals(
                withoutSource,
                playerCenterControlsBottomPadding(
                    metrics = metrics,
                    useLegacyLayout = false,
                    timelineHeight = 160.dp + sourceHeight,
                    containerHeight = 300.dp,
                    sourceLineHeight = sourceHeight,
                ),
            )
        }
    }

    @Test
    fun ampleSpaceKeepsMinimumLift() {
        assertEquals(
            metrics.centerLift,
            playerCenterControlsBottomPadding(metrics, false, 160.dp, 800.dp, 60.dp),
        )
    }

    @Test
    fun legacyLayoutIgnoresTimelineMeasurements() {
        assertEquals(
            metrics.centerLift,
            playerCenterControlsBottomPadding(metrics, true, 300.dp, 200.dp, 60.dp),
        )
    }
}
