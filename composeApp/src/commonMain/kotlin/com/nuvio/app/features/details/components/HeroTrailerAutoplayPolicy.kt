package com.nuvio.app.features.details.components

import androidx.compose.runtime.Composable

/**
 * Fork-only: true when the device asks for less data or less motion, in which case the
 * details hero shows the poster with a play badge instead of autoplaying the trailer.
 * (Android: Data Saver on a metered connection, or animations switched off.)
 */
@Composable
expect fun rememberHeroTrailerAutoplayBlocked(): Boolean
