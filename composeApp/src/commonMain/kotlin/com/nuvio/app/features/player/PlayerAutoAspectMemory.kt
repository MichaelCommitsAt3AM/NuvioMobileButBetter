package com.nuvio.app.features.player

import kotlin.math.abs

/**
 * Where a remembered Auto was left, plus the bars last measured there so the next episode can
 * zoom straight away instead of waiting ~10s for its own measurement. [bars] is null until an
 * episode in this scope has had its bars detected while on Auto.
 */
internal data class RememberedAutoAspect(
    val bars: PlayerVideoBars? = null,
    /** Width / height of the frame [bars] were measured on. */
    val frameAspect: Float = 0f,
) {
    /**
     * The remembered bars, if they belong to a frame shaped like this one. A different release
     * (already cropped to scope, 4:3, ...) gets nothing and waits for its own measurement.
     */
    fun barsFor(frameWidth: Int, frameHeight: Int): PlayerVideoBars? {
        if (bars == null || frameAspect <= 0f || frameWidth <= 0 || frameHeight <= 0) return null
        val aspect = frameWidth.toFloat() / frameHeight
        return bars.takeIf { abs(aspect - frameAspect) / frameAspect <= FRAME_ASPECT_TOLERANCE }
    }

    private companion object {
        const val FRAME_ASPECT_TOLERANCE = 0.02f
    }
}

/**
 * Remembers, on this device, where the user left the player on Auto: per season for series (the
 * episodes of a season are nearly always framed the same) and per title otherwise. Anything not
 * remembered opens on the saved mode (Fit by default). Local only - deliberately left out of the
 * settings sync payload, since whether Auto helps depends on this device's screen.
 *
 * Stored as one string per scope: `scope`, or `scope|top|bottom|frameAspect` once bars are known.
 */
internal object PlayerAutoAspectMemory {
    private const val SEPARATOR = '|'

    fun scopeKey(parentMetaType: String, parentMetaId: String, seasonNumber: Int?): String =
        if (seasonNumber != null) "$parentMetaId:s$seasonNumber" else "$parentMetaType:$parentMetaId"

    fun recall(scope: String): RememberedAutoAspect? =
        entries().firstOrNull { it.scope() == scope }?.let(::parse)

    fun isRemembered(scope: String): Boolean = recall(scope) != null

    fun setRemembered(scope: String, remembered: Boolean) {
        if (remembered && isRemembered(scope)) return
        write(scope, if (remembered) scope else null)
    }

    /** Stores the bars measured on Auto so later episodes of [scope] can zoom immediately. */
    fun rememberBars(scope: String, bars: PlayerVideoBars, frameWidth: Int, frameHeight: Int) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        val frameAspect = frameWidth.toFloat() / frameHeight
        write(scope, listOf(scope, bars.topFraction, bars.bottomFraction, frameAspect).joinToString(SEPARATOR.toString()))
    }

    private fun entries(): Set<String> = PlayerSettingsStorage.loadAutoAspectScopes().orEmpty()

    private fun write(scope: String, entry: String?) {
        val current = entries()
        val updated = current.filterNot { it.scope() == scope }.toSet() + listOfNotNull(entry)
        if (updated != current) PlayerSettingsStorage.saveAutoAspectScopes(updated)
    }

    private fun String.scope(): String = substringBefore(SEPARATOR)

    private fun parse(entry: String): RememberedAutoAspect {
        val parts = entry.split(SEPARATOR)
        if (parts.size != 4) return RememberedAutoAspect()
        val top = parts[1].toFloatOrNull()
        val bottom = parts[2].toFloatOrNull()
        val frameAspect = parts[3].toFloatOrNull()
        if (top == null || bottom == null || frameAspect == null) return RememberedAutoAspect()
        return RememberedAutoAspect(PlayerVideoBars(topFraction = top, bottomFraction = bottom), frameAspect)
    }
}
