package com.nuvio.app.features.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource

internal object PlatformPlaybackDataSourceFactory {
    fun create(
        context: Context,
        defaultRequestHeaders: Map<String, String>,
        defaultResponseHeaders: Map<String, String>,
        useYoutubeChunkedPlayback: Boolean,
        useLongReadTimeout: Boolean = false,
        externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
        useDiskCache: Boolean = true,
    ): DataSource.Factory {
        val httpFactory = PlayerPlaybackNetworking.createHttpDataSourceFactory(
            defaultRequestHeaders,
            useLongReadTimeout,
        )
        val cachedHttpFactory = if (useDiskCache) PlayerDiskCache.wrap(context, httpFactory) else httpFactory
        val subtitleHeaderFactory = SubtitleRequestHeaderDataSourceFactory(
            upstreamFactory = cachedHttpFactory,
            externalSubtitles = externalSubtitles
        )
        val baseFactory: DataSource.Factory = DefaultDataSource.Factory(context, subtitleHeaderFactory)
        return if (defaultResponseHeaders.isEmpty()) {
            baseFactory
        } else {
            ResponseHeaderOverridingDataSourceFactory(
                upstreamFactory = baseFactory,
                defaultResponseHeaders = defaultResponseHeaders,
            )
        }
    }
}
