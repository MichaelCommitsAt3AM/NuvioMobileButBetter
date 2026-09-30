package com.nuvio.app.features.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Total byte size of the main playback file, once any open-ended request has revealed it. */
internal class SourceContentLength {
    @Volatile
    var bytes: Long = C.LENGTH_UNSET.toLong()
        internal set
}

/**
 * Passes everything through to [upstream], but records the full size of [sourceUrl] into [sink]
 * the first time an open-ended request for it opens: `open()` returns the bytes remaining from
 * the requested position, so position + that is the whole file. Works the same when
 * [PlayerDiskCache] answers from disk. Other URLs through the same factory (sidecar subtitles)
 * are ignored.
 */
internal class ContentLengthProbeDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val sourceUrl: String,
    private val sink: SourceContentLength,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        ContentLengthProbeDataSource(upstream.createDataSource(), sourceUrl, sink)
}

private class ContentLengthProbeDataSource(
    private val upstream: DataSource,
    private val sourceUrl: String,
    private val sink: SourceContentLength,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val bytesRemaining = upstream.open(dataSpec)
        if (sink.bytes == C.LENGTH_UNSET.toLong() &&
            dataSpec.length == C.LENGTH_UNSET.toLong() &&
            bytesRemaining != C.LENGTH_UNSET.toLong() &&
            dataSpec.uri.toString() == sourceUrl
        ) {
            sink.bytes = dataSpec.position + bytesRemaining
        }
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }
}
