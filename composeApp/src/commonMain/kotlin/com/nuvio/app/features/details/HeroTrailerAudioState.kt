package com.nuvio.app.features.details

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object HeroTrailerAudioState {
    private val _muted = MutableStateFlow(true)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    fun toggleMuted() {
        _muted.value = !_muted.value
    }
}

/**
 * Fork-only: titles whose hero trailer already played to the end (or failed) during the
 * current details flow. Returning from the streams screen or player must not replay it;
 * leaving the details flow clears the memory so the trailer plays again next visit.
 */
object HeroTrailerPlaybackMemory {
    private val finished = HashSet<String>()

    fun isFinished(metaId: String): Boolean = metaId in finished

    fun markFinished(metaId: String) {
        finished.add(metaId)
    }

    fun clear() {
        finished.clear()
    }
}
