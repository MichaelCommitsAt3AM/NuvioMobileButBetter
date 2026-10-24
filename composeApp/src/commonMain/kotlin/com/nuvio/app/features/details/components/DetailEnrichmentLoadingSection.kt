package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.SkeletonBlock
import com.nuvio.app.core.ui.SkeletonPoster
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.nuvioHorizontalScrollBleed
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.skeleton
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsSection
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Same section-specific placeholders as desktop, sized to the mobile cards they replace. */
@Composable
internal fun DetailEnrichmentLoadingSection(
    section: MetaDetailsSection,
    meta: MetaDetails,
    showHeader: Boolean = true,
    horizontalScrollPadding: Dp = 0.dp,
) {
    val title = when (section) {
        MetaDetailsSection.TRAILERS -> stringResource(Res.string.detail_trailers_title)
        MetaDetailsSection.CAST -> stringResource(Res.string.settings_meta_cast)
        MetaDetailsSection.PRODUCTION -> stringResource(Res.string.meta_section_production_title)
        MetaDetailsSection.COLLECTION -> meta.collectionName ?: stringResource(Res.string.meta_section_collection_title)
        MetaDetailsSection.MORE_LIKE_THIS -> stringResource(Res.string.details_more_like_this)
        MetaDetailsSection.DETAILS -> return
    }
    val style = rememberPosterCardStyleUiState()
    DetailSection(
        title = title,
        showHeader = showHeader,
        modifier = Modifier.testTag("details-loading-${section.name}")
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val trailers = trailerSectionSizing(maxWidth.value, style.cornerRadiusDp.dp)
            val cast = castSectionSizing(maxWidth.value)
            val landscape = section == MetaDetailsSection.COLLECTION || style.catalogLandscapeModeEnabled
            val count = when (section) {
                MetaDetailsSection.TRAILERS -> 1
                MetaDetailsSection.PRODUCTION -> 5
                else -> 7
            }
            LazyRow(
                modifier = Modifier.nuvioHorizontalScrollBleed(horizontalScrollPadding).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = horizontalScrollPadding),
                horizontalArrangement = Arrangement.spacedBy(when (section) {
                    MetaDetailsSection.TRAILERS -> trailers.cardSpacing
                    MetaDetailsSection.CAST -> cast.avatarGap
                    else -> 10.dp
                }),
                userScrollEnabled = false,
            ) {
                items(count) { index ->
                    val tag = Modifier.testTag("details-loading-${section.name}-$index")
                    when (section) {
                        MetaDetailsSection.TRAILERS -> SkeletonPoster(
                            modifier = tag.width(trailers.cardWidth),
                            aspectRatio = 16f / 9f, cornerRadius = trailers.cardRadius,
                            showLabels = true, showDetail = false,
                        )
                        MetaDetailsSection.CAST -> Column(
                            modifier = tag.width(cast.itemWidth),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(cast.avatarSize).skeleton(CircleShape))
                            SkeletonBlock(Modifier.fillMaxWidth(0.82f), height = 16.dp)
                            SkeletonBlock(Modifier.fillMaxWidth(0.6f), height = 12.dp)
                        }
                        MetaDetailsSection.PRODUCTION -> SkeletonBlock(
                            modifier = tag, width = 96.dp,
                            height = when {
                                maxWidth >= 1024.dp -> 44.dp
                                maxWidth >= 720.dp -> 40.dp
                                else -> 36.dp
                            },
                            cornerRadius = 12.dp,
                        )
                        else -> SkeletonPoster(
                            modifier = tag.width(if (landscape) landscapePosterWidth(style.widthDp) else style.widthDp.dp),
                            aspectRatio = if (landscape) 16f / 9f else style.widthDp.toFloat() / style.heightDp,
                            cornerRadius = style.cornerRadiusDp.dp,
                            showLabels = !style.hideLabelsEnabled, showDetail = true,
                        )
                    }
                }
            }
        }
    }
}
