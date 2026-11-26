package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamDolbyVision
import com.nuvio.app.features.streams.StreamDolbyVisionLabel
import com.nuvio.app.features.streams.StreamItem
import kotlin.math.abs

/**
 * Orders the streams to try in place of a download whose Dolby Vision this device cannot show.
 * Only the same or a lower quality is considered, and only files within the user's download
 * filter limits for their quality ([DownloadQualityPicker.fitsLimits]). The same quality comes
 * first, then each lower one in turn, closest in size to the pick within each. Streams labelled
 * Dolby Vision only are left out, since they are almost always the same unplayable profile 5.
 */
internal object DownloadDolbyVisionAlternatives {
    fun ordered(
        streams: List<StreamItem>,
        selected: QualityDownloadOption,
        tried: Set<StreamItem>,
        config: DownloadFilterConfig,
        runtimeSeconds: Long?,
    ): List<StreamItem> {
        val targetSize = selected.sizeBytes
        val maxRank = selected.tier.rank
        return streams
            .asSequence()
            .filter { it != selected.stream && it !in tried }
            .filter { StreamDolbyVision.label(it) != StreamDolbyVisionLabel.Only }
            .filter { stream ->
                val tier = StreamQualityClassifier.resolutionOf(stream)
                tier != StreamResolutionTier.UNKNOWN && tier.rank <= maxRank
            }
            .filter { DownloadQualityPicker.fitsLimits(it, config, runtimeSeconds) }
            .sortedWith(
                compareByDescending<StreamItem> { StreamQualityClassifier.resolutionOf(it).rank }
                    .thenBy { StreamQualityClassifier.sizeOf(it) == null }
                    .thenBy { stream ->
                        val size = StreamQualityClassifier.sizeOf(stream)
                        if (size == null || targetSize == null) 0L else abs(size - targetSize)
                    },
            )
            .toList()
    }
}
