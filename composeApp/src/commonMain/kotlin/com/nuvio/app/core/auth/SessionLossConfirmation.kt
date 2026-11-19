package com.nuvio.app.core.auth

import kotlinx.coroutines.delay

internal const val SESSION_LOSS_REFRESH_ATTEMPTS = 3
internal const val SESSION_LOSS_RETRY_DELAY_MS = 1_500L

/**
 * Decides whether a "not authenticated" signal from the auth client is a real, permanent loss of
 * session, or a transient blip (typically a token refresh failing while the network is still
 * waking up after the app returns from the background).
 *
 * [refreshRejected] returns `true` when the server rejected the refresh token, `false` when the
 * refresh succeeded, and `null` for a transient failure (network, timeout, 5xx).
 *
 * Returns `true` only when the session is definitively gone. Inconclusive results keep the cached
 * session, matching how [AuthRepository] already treats a failed remote validation.
 */
internal suspend fun confirmSessionLoss(
    hasSession: () -> Boolean,
    refreshRejected: suspend () -> Boolean?,
    attempts: Int = SESSION_LOSS_REFRESH_ATTEMPTS,
    retryDelayMs: Long = SESSION_LOSS_RETRY_DELAY_MS,
): Boolean {
    // The client dropped the session itself (invalid refresh token, explicit clear): it's gone.
    if (!hasSession()) return true
    repeat(attempts) { attempt ->
        when (refreshRejected()) {
            true -> return true
            false -> return false
            null -> if (attempt < attempts - 1) delay(retryDelayMs)
        }
    }
    return false
}
