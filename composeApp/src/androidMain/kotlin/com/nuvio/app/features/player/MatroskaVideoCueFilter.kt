package com.nuvio.app.features.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor

private const val TAG = "NuvioPlayerDiag"

/**
 * When false the filter only observes and logs the Cues breakdown; the seek map is built
 * exactly as stock Media3 builds it.
 */
private const val VIDEO_CUE_FILTERING_ENABLED = true

// Matroska element IDs. Media3 keeps its copies private, and has no constant for CueTrack at all.
private const val ID_TRACK_ENTRY = 0xAE
private const val ID_TRACK_NUMBER = 0xD7
private const val ID_TRACK_TYPE = 0x83
private const val ID_CUES = 0x1C53BB6B
private const val ID_CUE_POINT = 0xBB
private const val ID_CUE_TIME = 0xB3
private const val ID_CUE_TRACK_POSITIONS = 0xB7
private const val ID_CUE_TRACK = 0xF7
private const val ID_CUE_CLUSTER_POSITION = 0xF1
private const val TRACK_TYPE_VIDEO = 1L

/**
 * Restricts a Matroska seek map to cue points indexed against a video track.
 *
 * Media3's [MatroskaExtractor] never reads CueTrack (it has no element type for it) and keeps the
 * first CueClusterPosition of every CuePoint, whatever track that position belongs to. Remuxes
 * commonly carry cue points for subtitle (e.g. PGS) or audio tracks too, so the seek map ends up
 * full of "sync points" that land mid-GOP for video. An exact seek then pre-rolls from one of
 * those, the video sample queue has to skip ahead to the next real video keyframe (often several
 * seconds later), and audio starts at the target while the picture waits - the frozen-video seek.
 *
 * The filter defers each CueTime and forwards it, together with the first *video* cluster
 * position, to the real extractor. It falls back to the unfiltered behaviour whenever it can't
 * tell which track a position belongs to, and replays the skipped points if filtering would
 * otherwise leave the seek map empty (an empty seek map makes the file unseekable).
 *
 * Kept free of Media3 types so the state machine can be unit tested with synthetic sequences.
 */
internal class MatroskaVideoCueFilter(
    private val filteringEnabled: Boolean = VIDEO_CUE_FILTERING_ENABLED,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val videoTrackNumbers = mutableSetOf<Long>()
    private var entryTrackNumber: Long? = null
    private var entryTrackType: Long? = null

    private var inCues = false
    private var cuesReported = false
    private var pendingCueTime: Long? = null
    private var currentCueTrack: Long? = null
    private var positionIndexInPoint = 0
    private var emittedForPoint = false
    private var firstSkippedForPoint: Pair<Long, Long>? = null

    private var cuePointCount = 0
    private var emittedCount = 0
    private val skippedPoints = mutableListOf<Pair<Long, Long>>()
    private val firstPositionTrackCounts = linkedMapOf<Long?, Int>()

    fun elementType(id: Int): Int? =
        if (id == ID_CUE_TRACK) EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT else null

    fun onStartMaster(id: Int) {
        when (id) {
            ID_TRACK_ENTRY -> {
                entryTrackNumber = null
                entryTrackType = null
            }
            ID_CUES -> {
                inCues = true
                cuePointCount = 0
                emittedCount = 0
                skippedPoints.clear()
                firstPositionTrackCounts.clear()
            }
            ID_CUE_POINT -> {
                pendingCueTime = null
                positionIndexInPoint = 0
                emittedForPoint = false
                firstSkippedForPoint = null
            }
            ID_CUE_TRACK_POSITIONS -> currentCueTrack = null
        }
    }

    /**
     * Returns true when the element was consumed and must NOT be passed to the extractor as-is.
     * [forward] passes an integer element straight through to the extractor.
     */
    fun onInteger(id: Int, value: Long, forward: (Int, Long) -> Unit): Boolean {
        when (id) {
            ID_TRACK_NUMBER -> entryTrackNumber = value
            ID_TRACK_TYPE -> entryTrackType = value
            ID_CUE_TRACK -> {
                currentCueTrack = value
                return true
            }
            ID_CUE_TIME -> if (inCues) {
                pendingCueTime = value
                return filteringEnabled
            }
            ID_CUE_CLUSTER_POSITION -> if (inCues) {
                return onClusterPosition(value, forward)
            }
        }
        return false
    }

    private fun onClusterPosition(position: Long, forward: (Int, Long) -> Unit): Boolean {
        if (positionIndexInPoint == 0) {
            firstPositionTrackCounts[currentCueTrack] = (firstPositionTrackCounts[currentCueTrack] ?: 0) + 1
        }
        positionIndexInPoint += 1
        if (!filteringEnabled) return false

        val cueTime = pendingCueTime ?: return false
        if (emittedForPoint) return true
        val track = currentCueTrack
        val isUsable = videoTrackNumbers.isEmpty() || track == null || track in videoTrackNumbers
        if (isUsable) {
            forward(ID_CUE_TIME, cueTime)
            forward(ID_CUE_CLUSTER_POSITION, position)
            emittedForPoint = true
            emittedCount += 1
        } else if (firstSkippedForPoint == null) {
            firstSkippedForPoint = cueTime to position
        }
        return true
    }

    /**
     * [replayCuePoint] must re-enter a CuePoint on the extractor and forward the given time and
     * cluster position, as if that point had been parsed normally.
     */
    fun onEndMaster(id: Int, replayCuePoint: (cueTime: Long, position: Long) -> Unit) {
        when (id) {
            ID_TRACK_ENTRY -> {
                val number = entryTrackNumber
                if (number != null && entryTrackType == TRACK_TYPE_VIDEO) videoTrackNumbers += number
            }
            ID_CUE_POINT -> if (inCues) {
                cuePointCount += 1
                if (filteringEnabled && !emittedForPoint) firstSkippedForPoint?.let(skippedPoints::add)
            }
            ID_CUES -> {
                inCues = false
                val replayed = filteringEnabled && emittedCount == 0 && skippedPoints.isNotEmpty()
                if (replayed) skippedPoints.forEach { (time, position) -> replayCuePoint(time, position) }
                if (!cuesReported) {
                    cuesReported = true
                    val byTrack = firstPositionTrackCounts.entries
                        .joinToString(prefix = "{", postfix = "}") { (track, count) -> "${track ?: "none"}:$count" }
                    log(
                        "mkvCues points=$cuePointCount videoTracks=$videoTrackNumbers firstPositionTrack=$byTrack " +
                            "filtering=${if (filteringEnabled) "on" else "off"} " +
                            "kept=${if (filteringEnabled) emittedCount else cuePointCount} " +
                            "dropped=${if (filteringEnabled) skippedPoints.size else 0} " +
                            "replayedAllBecauseNoVideoCues=$replayed",
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
internal class VideoCueMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    flags: Int,
) : MatroskaExtractor(subtitleParserFactory, flags) {
    private val cueFilter = MatroskaVideoCueFilter()

    override fun getElementType(id: Int): Int = cueFilter.elementType(id) ?: super.getElementType(id)

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        cueFilter.onStartMaster(id)
        super.startMasterElement(id, contentPosition, contentSize)
    }

    override fun integerElement(id: Int, value: Long) {
        val consumed = cueFilter.onInteger(id, value) { forwardId, forwardValue ->
            super.integerElement(forwardId, forwardValue)
        }
        if (!consumed) super.integerElement(id, value)
    }

    override fun endMasterElement(id: Int) {
        cueFilter.onEndMaster(id) { cueTime, position ->
            super.startMasterElement(ID_CUE_POINT, 0L, 0L)
            super.integerElement(ID_CUE_TIME, cueTime)
            super.integerElement(ID_CUE_CLUSTER_POSITION, position)
        }
        super.endMasterElement(id)
    }
}

/** libass variant: [AssMatroskaExtractor] replaces the stock extractor wholesale, so it needs the filter too. */
@OptIn(UnstableApi::class)
internal class VideoCueAssMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler,
) : AssMatroskaExtractor(subtitleParserFactory, assHandler, 0) {
    private val cueFilter = MatroskaVideoCueFilter()

    override fun getElementType(id: Int): Int = cueFilter.elementType(id) ?: super.getElementType(id)

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        cueFilter.onStartMaster(id)
        super.startMasterElement(id, contentPosition, contentSize)
    }

    override fun integerElement(id: Int, value: Long) {
        val consumed = cueFilter.onInteger(id, value) { forwardId, forwardValue ->
            super.integerElement(forwardId, forwardValue)
        }
        if (!consumed) super.integerElement(id, value)
    }

    override fun endMasterElement(id: Int) {
        cueFilter.onEndMaster(id) { cueTime, position ->
            super.startMasterElement(ID_CUE_POINT, 0L, 0L)
            super.integerElement(ID_CUE_TIME, cueTime)
            super.integerElement(ID_CUE_CLUSTER_POSITION, position)
        }
        super.endMasterElement(id)
    }
}

/**
 * Wraps [DefaultExtractorsFactory] and swaps its stock [MatroskaExtractor] for
 * [VideoCueMatroskaExtractor]. The replacement is constructed exactly as
 * DefaultExtractorsFactory 1.8.0 builds it (subtitle parser factory, plus
 * FLAG_EMIT_RAW_SUBTITLE_DATA when text transcoding is off), so the subtitle setters that
 * DefaultMediaSourceFactory pushes into the factory are mirrored here as well as forwarded.
 */
@OptIn(UnstableApi::class)
internal class VideoCueExtractorsFactory(
    private val delegate: DefaultExtractorsFactory,
) : ExtractorsFactory {
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var textTrackTranscodingEnabled = true

    @Synchronized
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        this.subtitleParserFactory = subtitleParserFactory
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    @Deprecated("Deprecated in ExtractorsFactory, but DefaultMediaSourceFactory still calls it; mirrored so the Matroska replacement matches.")
    @Synchronized
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        this.textTrackTranscodingEnabled = textTrackTranscodingEnabled
        @Suppress("DEPRECATION")
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGop: Int): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGop)
        return this
    }

    override fun createExtractors(): Array<Extractor> = withVideoCueMatroska(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        withVideoCueMatroska(delegate.createExtractors(uri, responseHeaders))

    @Synchronized
    private fun withVideoCueMatroska(extractors: Array<Extractor>): Array<Extractor> {
        for (index in extractors.indices) {
            if (extractors[index].javaClass == MatroskaExtractor::class.java) {
                val flags = if (textTrackTranscodingEnabled) 0 else MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA
                extractors[index] = VideoCueMatroskaExtractor(subtitleParserFactory, flags)
            }
        }
        return extractors
    }
}
