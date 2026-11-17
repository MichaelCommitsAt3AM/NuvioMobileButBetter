package com.nuvio.app.features.details.components

import android.content.Context
import android.net.ConnectivityManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberHeroTrailerAutoplayBlocked(): Boolean {
    val context = LocalContext.current
    return remember(context) { heroTrailerAutoplayBlocked(context) }
}

private fun heroTrailerAutoplayBlocked(context: Context): Boolean {
    val reduceMotion = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    val dataSaverOnMeteredNetwork = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        manager != null &&
            manager.isActiveNetworkMetered &&
            manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }.getOrDefault(false)
    return reduceMotion || dataSaverOnMeteredNetwork
}
