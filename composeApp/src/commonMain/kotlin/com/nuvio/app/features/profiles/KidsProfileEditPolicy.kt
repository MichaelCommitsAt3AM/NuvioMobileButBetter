package com.nuvio.app.features.profiles

/** Cosmetic edits keep the existing KidsScreen configuration, including on a second device. */
internal fun kidsConfigurationNeedsUpdate(
    saved: NuvioProfile?,
    enabled: Boolean,
    maxRating: String,
    apiKey: String,
): Boolean = enabled != (saved?.kidsEnabled == true) ||
    (enabled && (saved?.kidsConfigId.isNullOrBlank() || maxRating != saved?.kidsMaxRating || apiKey.isNotBlank()))
