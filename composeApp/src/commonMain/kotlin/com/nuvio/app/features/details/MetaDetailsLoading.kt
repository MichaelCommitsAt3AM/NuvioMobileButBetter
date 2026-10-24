package com.nuvio.app.features.details

import com.nuvio.app.features.tmdb.TmdbSettings

/**
 * Sections that will be filled by the running TMDB enrichment. Only enabled modules and
 * still-empty sections are included, so a copied add-on value is never replaced by a skeleton.
 */
internal fun pendingTmdbSections(
    meta: MetaDetails,
    settings: TmdbSettings,
    hasAccess: Boolean,
): Set<MetaDetailsSection> {
    if (!settings.enabled || !hasAccess) return emptySet()
    return buildSet {
        if (settings.useDetails && listOf(
                meta.status,
                meta.runtime,
                meta.ageRating,
                meta.country,
                meta.language,
            ).any { it.isNullOrBlank() }
        ) add(MetaDetailsSection.DETAILS)
        if ((settings.useProductions || settings.useNetworks) &&
            meta.productionCompanies.isEmpty() && meta.networks.isEmpty()
        ) add(MetaDetailsSection.PRODUCTION)
        if (settings.useCredits && meta.cast.isEmpty()) add(MetaDetailsSection.CAST)
        if (settings.useTrailers && meta.trailers.isEmpty()) add(MetaDetailsSection.TRAILERS)
        if (settings.useMoreLikeThis && meta.moreLikeThis.isEmpty()) add(MetaDetailsSection.MORE_LIKE_THIS)
    }
}

internal fun MetaScreenSectionKey.enrichmentSection(): MetaDetailsSection? = when (this) {
    MetaScreenSectionKey.PRODUCTION -> MetaDetailsSection.PRODUCTION
    MetaScreenSectionKey.CAST -> MetaDetailsSection.CAST
    MetaScreenSectionKey.TRAILERS -> MetaDetailsSection.TRAILERS
    MetaScreenSectionKey.DETAILS -> MetaDetailsSection.DETAILS
    MetaScreenSectionKey.COLLECTION -> MetaDetailsSection.COLLECTION
    MetaScreenSectionKey.MORE_LIKE_THIS -> MetaDetailsSection.MORE_LIKE_THIS
    else -> null
}
