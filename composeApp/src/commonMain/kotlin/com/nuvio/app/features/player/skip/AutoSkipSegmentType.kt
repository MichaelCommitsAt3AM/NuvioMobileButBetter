package com.nuvio.app.features.player.skip

enum class AutoSkipSegmentType(val storedValue: String) {
    INTRO("intro"),
    RECAP("recap"),
    OUTRO("outro"),
    MOVIE_CREDITS("movie-credits");

    companion object {
        fun fromStoredValue(value: String): AutoSkipSegmentType? =
            entries.firstOrNull { it.storedValue == value }

        fun fromSkipIntervalType(type: String): AutoSkipSegmentType? = when (type.trim().lowercase()) {
            "op", "opening", "mixed-op", "intro" -> INTRO
            "recap" -> RECAP
            "ed", "ending", "mixed-ed", "outro", "credits" -> OUTRO
            "movie-credits" -> MOVIE_CREDITS
            else -> null
        }
    }
}

internal fun SkipInterval.shouldAutoSkip(selectedTypes: Set<AutoSkipSegmentType>): Boolean =
    startTime.isFinite() && endTime.isFinite() && startTime >= 0 && endTime > startTime &&
        AutoSkipSegmentType.fromSkipIntervalType(type) in selectedTypes

/**
 * Intervals the user seeked out of or into. [toLandingRangeMs] widens the destination to every
 * position a keyframe-snapped seek to [toMs] may actually land on.
 */
internal fun List<SkipInterval>.intervalsAtSeekPositions(
    fromMs: Long,
    toMs: Long,
    toLandingRangeMs: LongRange = toMs..toMs,
): List<SkipInterval> =
    filter { interval ->
        AutoSkipSegmentType.fromSkipIntervalType(interval.type) != null &&
            (fromMs.isInside(interval) || toLandingRangeMs.overlaps(interval))
    }

private fun Long.isInside(interval: SkipInterval): Boolean {
    val seconds = this / 1000.0
    return seconds >= interval.startTime && seconds < interval.endTime
}

private fun LongRange.overlaps(interval: SkipInterval): Boolean =
    first / 1000.0 < interval.endTime && last / 1000.0 >= interval.startTime
