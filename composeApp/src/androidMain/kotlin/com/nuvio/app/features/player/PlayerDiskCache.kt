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
 *
 * None of this is reachable if the OS kills the process outright (swipe-to-close
 * on many OEM skins, or a low-memory kill, skip onDestroy entirely), so
 * [purgeStaleCacheOnStartup] also wipes any cache left over from a previous
 * process the very next time the app cold-starts, before any player can use it.
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

    /**
     * Wipes all cached playback data. Safe to call anytime, including on app close.
     * The (potentially slow, multi-GB) directory delete is dispatched to [purgeScope]
     * rather than run inline, so this never blocks the caller's thread - notably
     * important when called from `Activity.onDestroy()`, which the OS may only grant
     * a short window to complete before killing the process.
     */
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
            purgeScope.launch {
                try {
                    dir.deleteRecursively()
                } catch (e: Exception) {
                    Log.w(TAG, "Error deleting disk cache directory", e)
                }
            }
        }
    }

    /**
     * Wipes any cache left behind by a previous process that never got to run [clearAll]
     * (force-killed via recents swipe on aggressive OEM skins, low-memory kill, crash, etc).
     * Call once at app startup, before any player has requested the cache - at that point
     * [cache] is always null, so this only ever needs to delete stale files from disk.
     */
    fun purgeStaleCacheOnStartup(context: Context) {
        appContext = context.applicationContext
        clearAll()
    }
}
