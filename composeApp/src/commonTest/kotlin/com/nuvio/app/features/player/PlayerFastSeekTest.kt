package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.intervalsAtSeekPositions
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerFastSeekTest {

    @Test
    fun `forward seek never lands behind its start`() {
        assertEquals(57_000L..63_000L, fastSeekLandingRangeMs(fromMs = 50_000L, targetMs = 60_000L))
        assertEquals(51_000L..55_000L, fastSeekLandingRangeMs(fromMs = 50_000L, targetMs = 52_000L))
    }

    @Test
    fun `backward seek never lands ahead of its start`() {
        assertEquals(37_000L..43_000L, fastSeekLandingRangeMs(fromMs = 50_000L, targetMs = 40_000L))
        assertEquals(45_000L..49_000L, fastSeekLandingRangeMs(fromMs = 50_000L, targetMs = 48_000L))
    }

    @Test
    fun `long seek gets the full tolerance on both sides`() {
        assertEquals(
            (600_000L - PlayerFastSeekToleranceMs)..(600_000L + PlayerFastSeekToleranceMs),
            fastSeekLandingRangeMs(fromMs = 0L, targetMs = 600_000L),
        )
    }

    @Test
    fun `exact destination marks only the interval it is inside`() {
        val intro = interval(startSec = 60.0, endSec = 90.0)
        val intervals = listOf(intro)

        assertEquals(listOf(intro), intervals.intervalsAtSeekPositions(fromMs = 0L, toMs = 70_000L))
        assertEquals(emptyList(), intervals.intervalsAtSeekPositions(fromMs = 0L, toMs = 58_000L))
    }

    @Test
    fun `fast destination marks an interval the seek may snap into`() {
        // Seeking back to just after the intro can snap to a keyframe inside it; that intro must
        // not then auto-skip the user forward again.
        val intro = interval(startSec = 60.0, endSec = 90.0)
        val fromMs = 120_000L
        val toMs = 91_000L

        assertEquals(
            listOf(intro),
            listOf(intro).intervalsAtSeekPositions(
                fromMs = fromMs,
                toMs = toMs,
                toLandingRangeMs = fastSeekLandingRangeMs(fromMs, toMs),
            ),
        )
    }

    @Test
    fun `fast destination ignores intervals outside the landing range`() {
        val intro = interval(startSec = 60.0, endSec = 90.0)
        val fromMs = 120_000L
        val toMs = 94_000L

        assertEquals(
            emptyList(),
            listOf(intro).intervalsAtSeekPositions(
                fromMs = fromMs,
                toMs = toMs,
                toLandingRangeMs = fastSeekLandingRangeMs(fromMs, toMs),
            ),
        )
    }

    private fun interval(startSec: Double, endSec: Double) =
        SkipInterval(startTime = startSec, endTime = endSec, type = "intro", provider = "test")
}
