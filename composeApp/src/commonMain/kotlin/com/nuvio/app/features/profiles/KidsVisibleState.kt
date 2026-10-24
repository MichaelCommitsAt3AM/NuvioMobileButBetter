package com.nuvio.app.features.profiles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> kidsVisibleState(
    source: StateFlow<T>, scope: CoroutineScope, empty: T,
    profiles: StateFlow<ProfileState> = ProfileRepository.state,
    sameContent: ((T, T) -> Boolean)? = null,
    sanitize: suspend (T) -> T,
): StateFlow<T> {
    var verified: Pair<NuvioProfile, T>? = null
    return combine(source, profiles) { value, profile -> value to profile.activeProfile }
    .transformLatest { (value, profile) ->
        if (profile?.kidsEnabled != true) {
            verified = null
            emit(value)
        } else {
            // Retain only an already-sanitized version of the same content.
            // New titles and changes to the profile/policy still clear immediately.
            val previous = verified
            if (previous == null || previous.first != profile || sameContent?.invoke(previous.second, value) != true) emit(empty)
            val filtered = sanitize(value)
            if (profiles.value.activeProfile == profile) {
                verified = profile to filtered
                emit(filtered)
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0), empty)
}
