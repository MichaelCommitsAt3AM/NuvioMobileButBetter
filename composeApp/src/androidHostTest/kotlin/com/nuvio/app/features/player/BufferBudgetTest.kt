package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BufferBudgetTest {
    private val mb = 1024 * 1024

    private fun target(heapMb: Long) = BufferBudget.targetBufferBytes(heapMb * mb, isLowRamDevice = false)

    @Test
    fun targetScalesWithHeapWithinBounds() {
        assertEquals((256L * mb * BufferBudget.HEAP_SHARE).toInt(), target(256))
        assertEquals(BufferBudget.MAX_TARGET_BYTES, target(2048))
        assertEquals(BufferBudget.MIN_TARGET_BYTES, target(48))
        assertEquals((256L * mb * BufferBudget.LOW_RAM_HEAP_SHARE).toInt(), BufferBudget.targetBufferBytes(256L * mb, isLowRamDevice = true))
    }

    @Test
    fun typicalContentKeepsAtLeastTenSecondsBack() {
        for (mbps in listOf(5, 15, 25, 40)) {
            val seconds = BufferBudget.backBufferSeconds(mbps * 1_000_000, target(256), isLowRamDevice = false)
            assertTrue(seconds >= BufferBudget.MIN_BACK_BUFFER_SECONDS, "$mbps Mbps got only $seconds s back")
        }
    }

    @Test
    fun lowBitrateKeepsTheFullBackWindow() {
        assertEquals(BufferBudget.MAX_BACK_BUFFER_SECONDS, BufferBudget.backBufferSeconds(5_000_000, target(256), isLowRamDevice = false))
    }

    @Test
    fun remuxOnSmallHeapShrinksBackButKeepsTheForwardFloor() {
        val target = target(256)
        val seconds = BufferBudget.backBufferSeconds(60_000_000, target, isLowRamDevice = false)
        val backBytes = seconds * 60_000_000 / 8
        assertTrue(seconds < BufferBudget.MIN_BACK_BUFFER_SECONDS)
        assertTrue(seconds > 9.0, "remux should still get close to 10 s back, got $seconds")
        assertTrue(target - backBytes >= BufferBudget.MIN_FORWARD_BYTES - 1)
    }

    @Test
    fun remuxOnLargeHeapGetsTheFullWindow() {
        assertEquals(BufferBudget.MIN_BACK_BUFFER_SECONDS, BufferBudget.backBufferSeconds(60_000_000, target(512), isLowRamDevice = false))
    }

    @Test
    fun budgetTooSmallForAnyBackBufferGivesZero() {
        assertEquals(0.0, BufferBudget.backBufferSeconds(5_000_000, BufferBudget.MIN_FORWARD_BYTES, isLowRamDevice = false))
    }

    @Test
    fun lowRamDevicesKeepNoBackBuffer() {
        assertEquals(0.0, BufferBudget.backBufferSeconds(5_000_000, 64 * mb, isLowRamDevice = true))
    }

    @Test
    fun estimatesBitrateFromFileSizeAndDuration() {
        // 4.5 GB over 50 minutes = 12 Mbps.
        assertEquals(12_000_000, BufferBudget.estimateBitrate(4_500_000_000L, 3_000_000L))
    }

    @Test
    fun rejectsUnknownOrImplausibleEstimates() {
        assertNull(BufferBudget.estimateBitrate(-1L, 3_000_000L))
        assertNull(BufferBudget.estimateBitrate(4_500_000_000L, -9_223_372_036_854_775_807L))
        assertNull(BufferBudget.estimateBitrate(1_000_000L, 30_000L))
        assertNull(BufferBudget.estimateBitrate(1_000L, 3_000_000L))
    }
}
