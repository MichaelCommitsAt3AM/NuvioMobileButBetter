package com.nuvio.app.core.auth

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionLossConfirmationTest {

    private fun confirm(
        hasSession: Boolean = true,
        outcomes: List<Boolean?>,
        calls: IntArray = IntArray(1),
    ): Boolean = runBlocking {
        confirmSessionLoss(
            hasSession = { hasSession },
            refreshRejected = { outcomes[minOf(calls[0]++, outcomes.lastIndex)] },
            attempts = 3,
            retryDelayMs = 0,
        )
    }

    @Test
    fun missingSession_isConfirmedLostWithoutRefreshing() {
        val calls = IntArray(1)
        assertTrue(confirm(hasSession = false, outcomes = listOf(false), calls = calls))
        assertEquals(0, calls[0])
    }

    @Test
    fun successfulRefresh_isNotALoss() {
        val calls = IntArray(1)
        assertFalse(confirm(outcomes = listOf(false), calls = calls))
        assertEquals(1, calls[0])
    }

    @Test
    fun transientFailureThenSuccess_isNotALoss() {
        val calls = IntArray(1)
        assertFalse(confirm(outcomes = listOf(null, null, false), calls = calls))
        assertEquals(3, calls[0])
    }

    @Test
    fun rejectedRefreshToken_isConfirmedLoss() {
        val calls = IntArray(1)
        assertTrue(confirm(outcomes = listOf(null, true), calls = calls))
        assertEquals(2, calls[0])
    }

    @Test
    fun persistentTransientFailure_keepsSession() {
        val calls = IntArray(1)
        assertFalse(confirm(outcomes = listOf(null), calls = calls))
        assertEquals(3, calls[0])
    }
}
