package com.nuvio.app.features.details.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaReleaseState
import com.nuvio.app.features.details.releaseState
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_release_state_in_cinemas
import nuvio.composeapp.generated.resources.details_release_state_released
import nuvio.composeapp.generated.resources.details_release_state_unreleased
import org.jetbrains.compose.resources.stringResource

/**
 * The right-hand part of the age-rating badge (e.g. "R | RELEASED"), or null when TMDB
 * enrichment is off or the loaded details can't tell.
 */
@Composable
internal fun detailReleaseStateLabel(meta: MetaDetails): String? {
    val state = remember(meta) { meta.releaseState(CurrentDateProvider.todayIsoDate()) } ?: return null
    val label = when (state) {
        MetaReleaseState.UNRELEASED -> stringResource(Res.string.details_release_state_unreleased)
        MetaReleaseState.IN_CINEMAS -> stringResource(Res.string.details_release_state_in_cinemas)
        MetaReleaseState.RELEASED -> stringResource(Res.string.details_release_state_released)
    }
    return label.uppercase()
}
