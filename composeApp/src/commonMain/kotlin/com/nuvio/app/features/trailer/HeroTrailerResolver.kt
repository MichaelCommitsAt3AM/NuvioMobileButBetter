package com.nuvio.app.features.trailer

import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Fork-only: resolves the trailer behind the details hero with a short-lived cache, at most
 * [MaxConcurrentExtractions] lookups at once, and a [TrailerResolveGuard] back-off, so
 * flicking through many titles does not flood YouTube. Trailers the user opens by hand
 * keep using [TrailerPlaybackResolver] directly.
 */
object HeroTrailerResolver {
    private val log = Logger.withTag("HeroTrailer")
    private val cacheMutex = Mutex()
    private var cache = emptyMap<String, CachedSource>()
    private val guard = TrailerResolveGuard()
    private val permits = Semaphore(MaxConcurrentExtractions)

    suspend fun resolve(youtubeUrl: String): TrailerPlaybackSource? {
        if (youtubeUrl.isBlank()) return null
        // Cheap checks first, without taking a permit.
        when (val state = lookup(youtubeUrl)) {
            is Lookup.Cached -> { log.d { "cache hit" }; return state.source }
            Lookup.Skip -> { log.d { "skipped by guard (recent failure or cooldown)" }; return null }
            Lookup.Miss -> Unit
        }
        // A burst of title changes queues here (and drops out when the caller is cancelled)
        // instead of firing a request storm.
        return permits.withPermit {
            // Another caller may have resolved this title, or tripped the guard, while we waited.
            when (val state = lookup(youtubeUrl)) {
                is Lookup.Cached -> return@withPermit state.source
                Lookup.Skip -> return@withPermit null
                Lookup.Miss -> Unit
            }
            val source = runCatching { TrailerPlaybackResolver.resolveFromYouTubeUrl(youtubeUrl) }
                .onFailure { log.w(it) { "extraction threw" } }
                .getOrNull()
            log.d { "extraction done ok=${source != null} separateAudio=${!source?.audioUrl.isNullOrBlank()}" }
            cacheMutex.withLock {
                when {
                    source == null -> guard.recordFailure(youtubeUrl)
                    source.isDegraded() -> {
                        guard.recordDegraded()
                        cache = cache + (youtubeUrl to CachedSource(source, TimeSource.Monotonic.markNow(), DegradedCacheTtl))
                    }
                    else -> {
                        guard.recordSuccess()
                        cache = cache + (youtubeUrl to CachedSource(source, TimeSource.Monotonic.markNow(), CacheTtl))
                    }
                }
            }
            source
        }
    }

    private suspend fun lookup(key: String): Lookup = cacheMutex.withLock {
        cache = cache.filterValues { it.cachedAt.elapsedNow() < it.ttl }
        val cached = cache[key]
        when {
            cached != null -> Lookup.Cached(cached.source)
            guard.shouldSkip(key) -> Lookup.Skip
            else -> Lookup.Miss
        }
    }

    /**
     * Separate video and audio URLs mean the good (adaptive) path worked. A single combined
     * stream is the 360p-class fallback YouTube's bot checks leave behind.
     */
    private fun TrailerPlaybackSource.isDegraded(): Boolean = audioUrl.isNullOrBlank()

    private sealed interface Lookup {
        data class Cached(val source: TrailerPlaybackSource) : Lookup
        data object Skip : Lookup
        data object Miss : Lookup
    }

    private data class CachedSource(val source: TrailerPlaybackSource, val cachedAt: TimeMark, val ttl: Duration)

    private const val MaxConcurrentExtractions = 2
    private val CacheTtl = 10.minutes

    // A degraded source still plays, but is retried soon so quality recovers once YouTube relaxes.
    private val DegradedCacheTtl = 2.minutes
}
