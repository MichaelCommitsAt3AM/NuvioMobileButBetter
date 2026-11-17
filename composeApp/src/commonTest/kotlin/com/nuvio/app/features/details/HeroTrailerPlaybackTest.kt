package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeroTrailerPlaybackTest {
    private val pause = HeroTrailerPlaybackConfig.SCROLL_PAUSE_FRACTION
    private val resume = HeroTrailerPlaybackConfig.SCROLL_RESUME_FRACTION

    @Test
    fun `ready at the top starts playing`() {
        assertEquals(HeroTrailerPlaybackState.Playing, HeroTrailerPlaybackState.Idle.onReady(0f))
    }

    @Test
    fun `ready while already scrolled past the threshold starts paused`() {
        assertEquals(HeroTrailerPlaybackState.PausedByScroll, HeroTrailerPlaybackState.Idle.onReady(pause + 0.1f))
    }

    @Test
    fun `scrolling past the threshold pauses and scrolling back resumes`() {
        val paused = HeroTrailerPlaybackState.Playing.onScroll(pause + 0.01f)
        assertEquals(HeroTrailerPlaybackState.PausedByScroll, paused)
        assertEquals(HeroTrailerPlaybackState.Playing, paused.onScroll(resume - 0.01f))
    }

    @Test
    fun `hysteresis keeps it paused between the resume and pause points`() {
        val between = (pause + resume) / 2f
        assertEquals(HeroTrailerPlaybackState.Playing, HeroTrailerPlaybackState.Playing.onScroll(between))
        assertEquals(HeroTrailerPlaybackState.PausedByScroll, HeroTrailerPlaybackState.PausedByScroll.onScroll(between))
    }

    @Test
    fun `an ended trailer is not restarted by scrolling`() {
        val ended = HeroTrailerPlaybackState.Playing.onEnded()
        assertEquals(HeroTrailerPlaybackState.Ended, ended.onScroll(0f))
        assertEquals(HeroTrailerPlaybackState.Ended, ended.onReady(0f))
    }

    @Test
    fun `only playing shows the trailer and idle or playing want playback`() {
        assertTrue(HeroTrailerPlaybackState.Playing.showsTrailer)
        assertFalse(HeroTrailerPlaybackState.PausedByScroll.showsTrailer)
        assertTrue(HeroTrailerPlaybackState.Idle.wantsPlayback)
        assertFalse(HeroTrailerPlaybackState.PausedByScroll.wantsPlayback)
        assertFalse(HeroTrailerPlaybackState.Ended.wantsPlayback)
    }
}
