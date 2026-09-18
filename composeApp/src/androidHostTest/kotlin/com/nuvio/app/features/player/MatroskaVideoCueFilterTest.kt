package com.nuvio.app.features.player

import androidx.media3.extractor.mkv.EbmlProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatroskaVideoCueFilterTest {

    private companion object {
        const val ID_TRACK_ENTRY = 0xAE
        const val ID_TRACK_NUMBER = 0xD7
        const val ID_TRACK_TYPE = 0x83
        const val ID_CUES = 0x1C53BB6B
        const val ID_CUE_POINT = 0xBB
        const val ID_CUE_TIME = 0xB3
        const val ID_CUE_TRACK_POSITIONS = 0xB7
        const val ID_CUE_TRACK = 0xF7
        const val ID_CUE_CLUSTER_POSITION = 0xF1

        const val VIDEO = 1L
        const val AUDIO = 2L
        const val SUBTITLE = 17L
    }

    /** Reproduces stock MatroskaExtractor's cue handling (decompiled from media3-extractor 1.8.0). */
    private class StockCueSink {
        val cueTimes = mutableListOf<Long>()
        val clusterPositions = mutableListOf<Long>()
        private var seenClusterPositionForCurrentCuePoint = false

        fun startMaster(id: Int) {
            if (id == ID_CUE_POINT) seenClusterPositionForCurrentCuePoint = false
        }

        fun integer(id: Int, value: Long) {
            when (id) {
                ID_CUE_TIME -> cueTimes += value
                ID_CUE_CLUSTER_POSITION -> if (!seenClusterPositionForCurrentCuePoint) {
                    clusterPositions += value
                    seenClusterPositionForCurrentCuePoint = true
                }
            }
        }

        val seekMap: List<Pair<Long, Long>> get() = cueTimes.zip(clusterPositions)
    }

    private data class CuePosition(val track: Long?, val position: Long)
    private data class CuePoint(val time: Long, val positions: List<CuePosition>)

    private class Harness(filteringEnabled: Boolean) {
        val logs = mutableListOf<String>()
        val sink = StockCueSink()
        val filter = MatroskaVideoCueFilter(filteringEnabled = filteringEnabled, log = { logs += it })

        private fun startMaster(id: Int) {
            filter.onStartMaster(id)
            sink.startMaster(id)
        }

        private fun integer(id: Int, value: Long) {
            val consumed = filter.onInteger(id, value, sink::integer)
            if (!consumed) sink.integer(id, value)
        }

        private fun endMaster(id: Int) {
            filter.onEndMaster(id) { time, position ->
                sink.startMaster(ID_CUE_POINT)
                sink.integer(ID_CUE_TIME, time)
                sink.integer(ID_CUE_CLUSTER_POSITION, position)
            }
        }

        fun tracks(vararg tracks: Pair<Long, Long>) {
            tracks.forEach { (number, type) ->
                startMaster(ID_TRACK_ENTRY)
                integer(ID_TRACK_NUMBER, number)
                integer(ID_TRACK_TYPE, type)
                endMaster(ID_TRACK_ENTRY)
            }
        }

        fun cues(vararg points: CuePoint) {
            startMaster(ID_CUES)
            points.forEach { point ->
                startMaster(ID_CUE_POINT)
                integer(ID_CUE_TIME, point.time)
                point.positions.forEach { (track, position) ->
                    startMaster(ID_CUE_TRACK_POSITIONS)
                    track?.let { integer(ID_CUE_TRACK, it) }
                    integer(ID_CUE_CLUSTER_POSITION, position)
                    endMaster(ID_CUE_TRACK_POSITIONS)
                }
                endMaster(ID_CUE_POINT)
            }
            endMaster(ID_CUES)
        }
    }

    private fun point(time: Long, vararg positions: Pair<Long?, Long>) =
        CuePoint(time, positions.map { (track, position) -> CuePosition(track, position) })

    private val remuxTracks = arrayOf(VIDEO to 1L, AUDIO to 2L, SUBTITLE to 17L)

    @Test
    fun cueTrackIsExposedAsUnsignedInt() {
        val filter = MatroskaVideoCueFilter(filteringEnabled = true, log = {})
        assertEquals(EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT, filter.elementType(ID_CUE_TRACK))
        assertNull(filter.elementType(ID_CUE_TIME))
    }

    @Test
    fun dropsCuePointsIndexedAgainstNonVideoTracks() {
        val harness = Harness(filteringEnabled = true)
        harness.tracks(*remuxTracks)
        harness.cues(
            point(0L, VIDEO to 100L),
            point(2_300L, SUBTITLE to 200L),
            point(7_700L, VIDEO to 300L),
            point(9_100L, SUBTITLE to 400L),
        )

        assertEquals(listOf(0L to 100L, 7_700L to 300L), harness.sink.seekMap)
    }

    @Test
    fun usesVideoPositionWhenItIsNotTheFirstInTheCuePoint() {
        val harness = Harness(filteringEnabled = true)
        harness.tracks(*remuxTracks)
        harness.cues(point(5_000L, SUBTITLE to 900L, VIDEO to 950L))

        assertEquals(listOf(5_000L to 950L), harness.sink.seekMap)
    }

    @Test
    fun keepsCuePointsWithoutCueTrack() {
        val harness = Harness(filteringEnabled = true)
        harness.tracks(*remuxTracks)
        harness.cues(point(0L, null to 100L), point(4_000L, null to 200L))

        assertEquals(listOf(0L to 100L, 4_000L to 200L), harness.sink.seekMap)
    }

    @Test
    fun keepsEverythingWhenNoVideoTrackIsKnown() {
        val harness = Harness(filteringEnabled = true)
        harness.cues(point(0L, AUDIO to 100L), point(4_000L, SUBTITLE to 200L))

        assertEquals(listOf(0L to 100L, 4_000L to 200L), harness.sink.seekMap)
    }

    @Test
    fun replaysSkippedPointsRatherThanLeavingTheSeekMapEmpty() {
        val harness = Harness(filteringEnabled = true)
        harness.tracks(*remuxTracks)
        harness.cues(point(0L, AUDIO to 100L), point(4_000L, SUBTITLE to 200L))

        assertEquals(listOf(0L to 100L, 4_000L to 200L), harness.sink.seekMap)
        assertTrue(harness.logs.single().contains("replayedAllBecauseNoVideoCues=true"))
    }

    @Test
    fun observeOnlyModeMatchesStockSeekMap() {
        val points = arrayOf(
            point(0L, VIDEO to 100L),
            point(2_300L, SUBTITLE to 200L),
            point(5_000L, SUBTITLE to 900L, VIDEO to 950L),
        )
        val harness = Harness(filteringEnabled = false)
        harness.tracks(*remuxTracks)
        harness.cues(*points)

        assertEquals(listOf(0L to 100L, 2_300L to 200L, 5_000L to 900L), harness.sink.seekMap)
    }

    @Test
    fun logsPerTrackBreakdownOnce() {
        val harness = Harness(filteringEnabled = true)
        harness.tracks(*remuxTracks)
        harness.cues(point(0L, VIDEO to 100L), point(2_300L, SUBTITLE to 200L), point(9_100L, SUBTITLE to 400L))
        harness.cues(point(0L, VIDEO to 100L))

        val line = harness.logs.single()
        assertTrue(line.contains("points=3"), line)
        assertTrue(line.contains("videoTracks=[1]"), line)
        assertTrue(line.contains("firstPositionTrack={1:1, 17:2}"), line)
        assertTrue(line.contains("kept=1 dropped=2"), line)
    }
}
