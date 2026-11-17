package com.nuvio.app.features.details.components

import androidx.compose.runtime.Composable

// This fork only ships Android; iOS never blocks autoplay.
@Composable
actual fun rememberHeroTrailerAutoplayBlocked(): Boolean = false
