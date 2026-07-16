package com.nuvio.app.features.player

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * App-wide on-disk HTTP cache shared by every ExoPlayer instance (main
 * playback and the hero trailer preview). Lets rewinds/seeks past the small
 * in-RAM back buffer (see [DynamicBackBufferLoadControl]) re-read from disk
 * instead of re-fetching from the network.
 *
 * media3's SimpleCache throws if two instances open the same cache
 * directory concurrently, so this must stay a process-wide singleton.
 */
internal object PlayerDiskCache {
    private const val TAG = "PlayerDiskCache"
    private const val CACHE_DIR_NAME = "media3_playback_cache"
    private const val DEFAULT_MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024 // 2GB
    private const val LOW_RAM_MAX_CACHE_BYTES = 512L * 1024 * 1024 // 512MB

    @Volatile
    private var cache: SimpleCache? = null
    private var initFailed = false

    @Synchronized
    private fun getOrCreate(context: Context): SimpleCache? {
        cache?.let { return it }
        if (initFailed) return null
        return try {
            val appContext = context.applicationContext
            val isLowRamDevice = (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.isLowRamDevice ?: false
            val maxBytes = if (isLowRamDevice) LOW_RAM_MAX_CACHE_BYTES else DEFAULT_MAX_CACHE_BYTES
            val dir = File(appContext.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
            val databaseProvider = StandaloneDatabaseProvider(appContext)
            SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes), databaseProvider).also { cache = it }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize disk cache; playback will fall back to network-only", e)
            initFailed = true
            null
        }
    }

    /** Wraps [upstream] with a disk-cache layer, falling back to [upstream] untouched on any failure. */
    fun wrap(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val simpleCache = getOrCreate(context) ?: return upstream
        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
