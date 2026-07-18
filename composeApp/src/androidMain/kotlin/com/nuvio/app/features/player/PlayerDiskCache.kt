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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App-wide on-disk HTTP cache shared by every ExoPlayer instance (main
 * playback and the hero trailer preview). Lets rewinds/seeks past the small
 * in-RAM back buffer (see [DynamicBackBufferLoadControl]) re-read from disk
 * instead of re-fetching from the network.
 *
 * media3's SimpleCache throws if two instances open the same cache
 * directory concurrently, so this must stay a process-wide singleton.
 *
 * The cache has no built-in retention limit besides its byte-size LRU
 * evictor, so without the hooks below it would happily hold onto cached
 * video segments long after playback stops. [onPlaybackStarted]/
 * [onPlaybackStopped] track how many players are currently using the cache
 * and wipe it from disk after [IDLE_PURGE_DELAY_MS] once nothing is using it
 * anymore; [clearAll] wipes it immediately for a hard app-close.
 */
internal object PlayerDiskCache {
    private const val TAG = "PlayerDiskCache"
    private const val CACHE_DIR_NAME = "media3_playback_cache"
    private const val DEFAULT_MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024 // 2GB
    private const val LOW_RAM_MAX_CACHE_BYTES = 512L * 1024 * 1024 // 512MB
    private const val IDLE_PURGE_DELAY_MS = 45_000L

    @Volatile
    private var cache: SimpleCache? = null
    private var initFailed = false
    private var appContext: Context? = null

    private val purgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeSessions = 0
    private var purgeJob: Job? = null

    @Synchronized
    private fun getOrCreate(context: Context): SimpleCache? {
        cache?.let { return it }
        if (initFailed) return null
        return try {
            val resolvedContext = context.applicationContext
            appContext = resolvedContext
            val isLowRamDevice = (resolvedContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.isLowRamDevice ?: false
            val maxBytes = if (isLowRamDevice) LOW_RAM_MAX_CACHE_BYTES else DEFAULT_MAX_CACHE_BYTES
            val dir = File(resolvedContext.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
            val databaseProvider = StandaloneDatabaseProvider(resolvedContext)
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

    /** Call when a player backed by this cache starts/is created. Cancels any pending idle purge. */
    @Synchronized
    fun onPlaybackStarted() {
        activeSessions++
        purgeJob?.cancel()
        purgeJob = null
    }

    /**
     * Call when a player backed by this cache stops/releases. Once the last active session ends,
     * schedules the whole disk cache to be wiped after [IDLE_PURGE_DELAY_MS] of inactivity, unless
     * a new session starts first.
     */
    @Synchronized
    fun onPlaybackStopped() {
        if (activeSessions > 0) activeSessions--
        if (activeSessions == 0) {
            purgeJob?.cancel()
            purgeJob = purgeScope.launch {
                delay(IDLE_PURGE_DELAY_MS)
                clearAll()
            }
        }
    }

    /** Immediately wipes all cached playback data from disk. Safe to call anytime, including on app close. */
    @Synchronized
    fun clearAll() {
        purgeJob?.cancel()
        purgeJob = null
        activeSessions = 0

        val existing = cache
        cache = null
        try {
            existing?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing disk cache", e)
        }

        val dir = appContext?.let { File(it.cacheDir, CACHE_DIR_NAME) }
        if (dir != null && dir.exists()) {
            try {
                dir.deleteRecursively()
            } catch (e: Exception) {
                Log.w(TAG, "Error deleting disk cache directory", e)
            }
        }
    }
}
