package com.nuvio.app.features.player

/**
 * Remembers, on this device, where the user left the player on Auto: per season for series (the
 * episodes of a season are nearly always framed the same) and per title otherwise. Anything not
 * remembered opens on the saved mode (Fit by default). Local only - deliberately left out of the
 * settings sync payload, since whether Auto helps depends on this device's screen.
 */
internal object PlayerAutoAspectMemory {
    fun scopeKey(parentMetaType: String, parentMetaId: String, seasonNumber: Int?): String =
        if (seasonNumber != null) "$parentMetaId:s$seasonNumber" else "$parentMetaType:$parentMetaId"

    fun isRemembered(scope: String): Boolean =
        PlayerSettingsStorage.loadAutoAspectScopes().orEmpty().contains(scope)

    fun setRemembered(scope: String, remembered: Boolean) {
        val current = PlayerSettingsStorage.loadAutoAspectScopes().orEmpty()
        val updated = if (remembered) current + scope else current - scope
        if (updated != current) PlayerSettingsStorage.saveAutoAspectScopes(updated)
    }
}
