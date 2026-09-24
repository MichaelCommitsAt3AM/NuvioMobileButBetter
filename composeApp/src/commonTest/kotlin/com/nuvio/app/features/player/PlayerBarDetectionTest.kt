package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlayerBarDetectionTest {

    private fun sample(top: Float, bottom: Float = top, centerLuma: Int = 80) =
        BarSample(top = top, bottom = bottom, centerLuma = centerLuma)

    private fun VideoBarDetector.feed(vararg samples: BarSample) = samples.forEach(::add)

    private fun frame(width: Int, height: Int, barRowsTop: Int, barRowsBottom: Int, picture: Int = 0x808080): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val isBar = y < barRowsTop || y >= height - barRowsBottom
            val argb = if (isBar) 0xFF000000.toInt() else (0xFF000000.toInt() or picture)
            for (x in 0 until width) pixels[y * width + x] = argb
        }
        return pixels
    }

    // ---------- analyzeFrameBars ----------

    @Test
    fun `measures symmetric top and bottom bars`() {
        val result = analyzeFrameBars(frame(100, 200, barRowsTop = 20, barRowsBottom = 20), 100, 200)
        assertEquals(0.10f, result.top, 0.001f)
        assertEquals(0.10f, result.bottom, 0.001f)
    }

    @Test
    fun `frame with no bars measures zero`() {
        val result = analyzeFrameBars(frame(100, 200, 0, 0), 100, 200)
        assertEquals(0f, result.top)
        assertEquals(0f, result.bottom)
    }

    @Test
    fun `one bright pixel in a row ends the bar`() {
        val pixels = frame(100, 200, barRowsTop = 20, barRowsBottom = 20)
        // Put a bright pixel in the 10th row of the top bar.
        pixels[10 * 100 + 50] = 0xFFFFFFFF.toInt()
        val result = analyzeFrameBars(pixels, 100, 200)
        assertEquals(10f / 200f, result.top, 0.001f)
    }

    @Test
    fun `dark scene reports a low center luma`() {
        val result = analyzeFrameBars(frame(100, 200, 0, 0, picture = 0x020202), 100, 200)
        assertTrue(result.centerLuma < 10)
    }

    @Test
    fun `bright picture reports a healthy center luma`() {
        val result = analyzeFrameBars(frame(100, 200, 20, 20, picture = 0x808080), 100, 200)
        assertTrue(result.centerLuma > 100)
    }

    @Test
    fun `undersized or empty input is handled without throwing`() {
        val empty = analyzeFrameBars(IntArray(0), 0, 0)
        assertEquals(0f, empty.top)
        val short = analyzeFrameBars(IntArray(5), 100, 200)
        assertEquals(0f, short.bottom)
    }

    // ---------- VideoBarDetector ----------

    @Test
    fun `five agreeing samples produce a result`() {
        val detector = VideoBarDetector()
        detector.feed(*Array(5) { sample(0.0556f) })
        val result = assertIs<BarDetectionResult.Found>(detector.result)
        assertEquals(0.0556f, result.bars.topFraction, 0.0001f)
    }

    @Test
    fun `fewer than five samples stay pending`() {
        val detector = VideoBarDetector()
        detector.feed(*Array(4) { sample(0.0556f) })
        assertEquals(BarDetectionResult.Pending, detector.result)
    }

    @Test
    fun `dark scene samples are ignored rather than trusted`() {
        val detector = VideoBarDetector()
        // A dark scene makes the whole frame look like bars - it must never count.
        detector.feed(*Array(20) { sample(0.30f, centerLuma = 3) })
        assertEquals(BarDetectionResult.Pending, detector.result)
    }

    @Test
    fun `dark samples in between good ones do not break the window`() {
        val detector = VideoBarDetector()
        detector.feed(
            sample(0.0556f), sample(0.0556f),
            sample(0.30f, centerLuma = 2),
            sample(0.0556f), sample(0.0556f), sample(0.0556f),
        )
        assertIs<BarDetectionResult.Found>(detector.result)
    }

    @Test
    fun `oversized bars are rejected as a fade`() {
        val detector = VideoBarDetector()
        detector.feed(*Array(10) { sample(0.46f) })
        assertEquals(BarDetectionResult.Pending, detector.result)
    }

    @Test
    fun `asymmetric bars are rejected`() {
        val detector = VideoBarDetector()
        detector.feed(*Array(10) { sample(top = 0.10f, bottom = 0.02f) })
        assertEquals(BarDetectionResult.Pending, detector.result)
    }

    @Test
    fun `disagreeing samples do not produce a result`() {
        val detector = VideoBarDetector()
        detector.feed(sample(0.02f), sample(0.10f), sample(0.02f), sample(0.10f), sample(0.02f))
        assertEquals(BarDetectionResult.Pending, detector.result)
    }

    @Test
    fun `the smallest bar in the window is reported`() {
        val detector = VideoBarDetector()
        detector.feed(sample(0.0560f), sample(0.0555f), sample(0.0550f), sample(0.0558f), sample(0.0552f))
        val result = assertIs<BarDetectionResult.Found>(detector.result)
        assertEquals(0.0550f, result.bars.topFraction, 0.0001f)
    }

    @Test
    fun `zero-bar samples are not a result and keep sampling`() {
        val detector = VideoBarDetector()
        detector.feed(*Array(20) { sample(0f) })
        assertEquals(BarDetectionResult.Pending, detector.result)
    }

    @Test
    fun `a steady window of zero-bar samples is flagged without ending detection`() {
        val detector = VideoBarDetector()
        detector.feed(*Array(4) { sample(0f) })
        assertFalse(detector.sawNoBars)
        detector.feed(sample(0f))
        assertTrue(detector.sawNoBars)
        assertEquals(BarDetectionResult.Pending, detector.result)
    }

    @Test
    fun `letterboxed samples never flag no bars`() {
        val detector = VideoBarDetector()
        detector.feed(*Array(5) { sample(0.0556f) })
        assertFalse(detector.sawNoBars)
    }

    @Test
    fun `a 4 by 3 prologue with no top and bottom bars does not stop detection`() {
        val detector = VideoBarDetector()
        // Prologue: pillarboxed, so no top/bottom bars...
        detector.feed(*Array(20) { sample(0f) })
        assertEquals(BarDetectionResult.Pending, detector.result)
        // ...then the real, letterboxed film.
        detector.feed(*Array(5) { sample(0.0556f) })
        assertIs<BarDetectionResult.Found>(detector.result)
    }

    @Test
    fun `gives up after the sampling budget`() {
        val detector = VideoBarDetector(BarDetectorConfig(maxSamples = 10))
        detector.feed(*Array(10) { sample(0f) })
        assertEquals(BarDetectionResult.NoBars, detector.result)
    }

    @Test
    fun `a found result is final`() {
        val detector = VideoBarDetector()
        detector.feed(*Array(5) { sample(0.0556f) })
        val first = detector.result
        detector.feed(*Array(10) { sample(0.30f) })
        assertEquals(first, detector.result)
    }

    @Test
    fun `sampling slows down after the fast phase`() {
        val detector = VideoBarDetector(BarDetectorConfig(fastSamples = 3, fastIntervalMs = 2_000L, slowIntervalMs = 10_000L))
        assertEquals(2_000L, detector.nextIntervalMs())
        detector.feed(sample(0f), sample(0f), sample(0f))
        assertEquals(10_000L, detector.nextIntervalMs())
    }

    @Test
    fun `detector records the last sample and why it was accepted or rejected`() {
        val detector = VideoBarDetector()
        detector.add(sample(0.0556f))
        assertEquals(BarSampleVerdict.Usable, detector.lastVerdict)
        assertEquals(1, detector.windowCount)

        detector.add(sample(0.30f, centerLuma = 2))
        assertEquals(BarSampleVerdict.TooDark, detector.lastVerdict)
        assertEquals(1, detector.windowCount)

        detector.add(sample(0.46f))
        assertEquals(BarSampleVerdict.BarsTooLarge, detector.lastVerdict)

        detector.add(sample(top = 0.10f, bottom = 0.02f))
        assertEquals(BarSampleVerdict.Asymmetric, detector.lastVerdict)
    }

    @Test
    fun `window spread reflects disagreement between samples`() {
        val detector = VideoBarDetector()
        detector.feed(sample(0.02f), sample(0.03f))
        assertEquals(0.01f, detector.windowSpread, 0.0001f)
    }
}
