package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerAspectPolicyTest {

    private fun bars(fraction: Float) = PlayerVideoBars(fraction, fraction)

    // 60px of 1080 - what the spike measured on the real 2:1 title.
    private val twoToOneBar = 60f / 1080f

    private fun assertZoom(expected: Float, actual: Float) {
        assertEquals(expected, actual, 0.005f)
    }

    @Test
    fun `2 to 1 picture in a 16 by 9 frame zooms 1_125 on a 19_5 by 9 phone`() {
        // The reported case: 1920x1080 frame, ~60px baked bars, 2340x1080 viewport.
        assertZoom(1.125f, resolveAutoZoom(bars(twoToOneBar), 1920, 1080, 2340, 1080))
    }

    @Test
    fun `2 to 1 picture zooms the same on a 20 by 9 phone`() {
        // The picture (2.0) is narrower than both viewports, so the zoom is set by the frame alone.
        assertZoom(1.125f, resolveAutoZoom(bars(twoToOneBar), 1920, 1080, 2400, 1080))
    }

    @Test
    fun `4K frame gives the same zoom as 1080p because bars are fractions`() {
        assertZoom(1.125f, resolveAutoZoom(bars(twoToOneBar), 3840, 2160, 2340, 1080))
    }

    @Test
    fun `no bars means no zoom`() {
        assertEquals(1f, resolveAutoZoom(bars(0f), 1920, 1080, 2340, 1080))
        assertEquals(1f, resolveAutoZoom(null, 1920, 1080, 2340, 1080))
    }

    @Test
    fun `bars too thin to be worth offering are ignored`() {
        // 1.85:1 in a 16:9 frame is ~21px per side, ~1.9%.
        assertEquals(1f, resolveAutoZoom(bars(21f / 1080f), 1920, 1080, 2340, 1080))
    }

    @Test
    fun `picture wider than the viewport is not offered`() {
        // 2.39:1 in a 16:9 frame (~138px bars) on a 2.167 viewport: removing the bars would crop
        // the sides, and Fit already handles this well.
        assertEquals(1f, resolveAutoZoom(bars(138f / 1080f), 1920, 1080, 2340, 1080))
        assertEquals(1f, resolveAutoZoom(bars(138f / 1080f), 1920, 1080, 2400, 1080))
    }

    @Test
    fun `viewport narrower than the frame gains nothing`() {
        // A 4:3 tablet already fills the width with the 16:9 frame; the bars cannot be removed
        // without cropping.
        assertEquals(1f, resolveAutoZoom(bars(twoToOneBar), 1920, 1080, 2048, 1536))
    }

    @Test
    fun `zoom never exceeds the amount that keeps the picture inside the viewport width`() {
        // 2.15:1 picture on a 2.167 viewport: nearly exact fit, so the zoom is capped by V.
        val barFraction = (1f - (1920f / 1080f) / 2.15f) / 2f
        val zoom = resolveAutoZoom(bars(barFraction), 1920, 1080, 2340, 1080)
        // Picture width at that zoom must not exceed the viewport width.
        val fittedWidthAtZoom = 1080f * (1920f / 1080f) * zoom
        assertEquals(true, fittedWidthAtZoom <= 2340f * 1.011f)
    }

    @Test
    fun `smaller of the two bars is used so error leaves a sliver not a crop`() {
        val asymmetric = PlayerVideoBars(topFraction = 0.06f, bottomFraction = 0.05f)
        val symmetricSmaller = bars(0.05f)
        assertEquals(
            resolveAutoZoom(symmetricSmaller, 1920, 1080, 2340, 1080),
            resolveAutoZoom(asymmetric, 1920, 1080, 2340, 1080),
        )
    }

    @Test
    fun `invalid dimensions never produce a zoom`() {
        assertEquals(1f, resolveAutoZoom(bars(twoToOneBar), 0, 1080, 2340, 1080))
        assertEquals(1f, resolveAutoZoom(bars(twoToOneBar), 1920, 0, 2340, 1080))
        assertEquals(1f, resolveAutoZoom(bars(twoToOneBar), 1920, 1080, 0, 1080))
        assertEquals(1f, resolveAutoZoom(bars(twoToOneBar), 1920, 1080, 2340, 0))
        assertEquals(1f, resolveAutoZoom(bars(twoToOneBar), -1, -1, -1, -1))
    }

    @Test
    fun `absurd bars that leave almost no picture are ignored`() {
        assertEquals(1f, resolveAutoZoom(bars(0.48f), 1920, 1080, 2340, 1080))
    }

    @Test
    fun `decision explains why Auto is offered`() {
        val decision = evaluateAutoZoom(bars(twoToOneBar), 1920, 1080, 2340, 1080)
        assertEquals(AutoZoomReason.Offered, decision.reason)
        assertEquals(2.0f, decision.pictureAspect, 0.01f)
    }

    @Test
    fun `decision explains each reason it is not offered`() {
        assertEquals(AutoZoomReason.NoBars, evaluateAutoZoom(null, 1920, 1080, 2340, 1080).reason)
        assertEquals(AutoZoomReason.NoBars, evaluateAutoZoom(bars(0f), 1920, 1080, 2340, 1080).reason)
        assertEquals(
            AutoZoomReason.BarsTooThin,
            evaluateAutoZoom(bars(21f / 1080f), 1920, 1080, 2340, 1080).reason,
        )
        assertEquals(
            AutoZoomReason.PictureWiderThanViewport,
            evaluateAutoZoom(bars(138f / 1080f), 1920, 1080, 2340, 1080).reason,
        )
        assertEquals(AutoZoomReason.InvalidInput, evaluateAutoZoom(bars(twoToOneBar), 0, 0, 2340, 1080).reason)
    }
}
