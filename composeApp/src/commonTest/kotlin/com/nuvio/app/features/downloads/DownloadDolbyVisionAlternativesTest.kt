package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadDolbyVisionAlternativesTest {

    private val gb = 1024L * 1024L * 1024L
    private val mb = 1024L * 1024L
    private val runtime = 3_600L
    private val config = DownloadFilterConfig.DEFAULT

    @Test
    fun `prefers the same quality closest in size, then lower ones, skipping Dolby Vision only`() {
        val picked = stream("pick", "Show.S01E01.2160p.WEB-DL.DV.HEVC", 6 * gb)
        val dvOnly = stream("dv", "Show.S01E01.2160p.WEB-DL.DV.HEVC-OTHER", 6 * gb)
        val farHdr = stream("far", "Show.S01E01.2160p.WEB-DL.HDR10.HEVC", 12 * gb)
        val nearHdr = stream("near", "Show.S01E01.2160p.WEB-DL.DV.HDR.HEVC", 5 * gb)
        val fhd = stream("fhd", "Show.S01E01.1080p.WEB-DL.HEVC", 1_400 * mb)

        val ordered = ordered(listOf(picked, dvOnly, farHdr, fhd, nearHdr), picked, StreamResolutionTier.UHD_4K, 6 * gb)

        assertEquals(listOf(nearHdr, farHdr, fhd), ordered)
    }

    @Test
    fun `never goes above the picked quality`() {
        val picked = stream("pick", "Show.S01E01.1080p.WEB-DL.DV.HEVC", 1_400 * mb)
        val uhd = stream("uhd", "Show.S01E01.2160p.WEB-DL.HDR10.HEVC", 1_500 * mb)
        // About 1.2 Mbps for an hour: within the 720p HEVC budget (6 x 0.4 x 0.6 = 1.44 Mbps).
        val hd = stream("hd", "Show.S01E01.720p.WEB-DL.HEVC", 500 * mb)

        val ordered = ordered(listOf(picked, uhd, hd), picked, StreamResolutionTier.FHD_1080, 1_400 * mb)

        assertEquals(listOf(hd), ordered)
    }

    @Test
    fun `leaves out files over the filter limits`() {
        val picked = stream("pick", "Show.S01E01.1080p.WEB-DL.DV.HEVC", 1_400 * mb)
        // About 13 Mbps for an hour: over the 1080p HEVC bitrate budget.
        val bloated = stream("big", "Show.S01E01.1080p.BluRay.HEVC", 6 * gb)
        val fits = stream("ok", "Show.S01E01.1080p.WEB-DL.HEVC", 1_200 * mb)
        val sizeCapped = config.copy(maxSizeBytes = 2 * gb, bitrateLimitEnabled = false)

        assertEquals(listOf(fits), ordered(listOf(picked, bloated, fits), picked, StreamResolutionTier.FHD_1080, 1_400 * mb))
        // Bitrate limit off but a 2 GB size cap: the 1.2 GB file fits, the 6 GB one does not.
        assertEquals(
            listOf(fits),
            DownloadDolbyVisionAlternatives.ordered(
                streams = listOf(picked, bloated, fits),
                selected = option(picked, StreamResolutionTier.FHD_1080, 1_400 * mb),
                tried = emptySet(),
                config = sizeCapped,
                runtimeSeconds = runtime,
            ),
        )
    }

    @Test
    fun `leaves out streams already tried`() {
        val picked = stream("pick", "Show.2160p.DV", 6 * gb)
        val a = stream("a", "Show.2160p.HDR10", 5 * gb)
        val b = stream("b", "Show.2160p.HDR10.x265", 8 * gb)

        val ordered = DownloadDolbyVisionAlternatives.ordered(
            streams = listOf(picked, a, b),
            selected = option(picked, StreamResolutionTier.UHD_4K, 6 * gb),
            tried = setOf(a),
            config = config,
            runtimeSeconds = runtime,
        )

        assertEquals(listOf(b), ordered)
    }

    private fun ordered(streams: List<StreamItem>, picked: StreamItem, tier: StreamResolutionTier, size: Long) =
        DownloadDolbyVisionAlternatives.ordered(
            streams = streams,
            selected = option(picked, tier, size),
            tried = emptySet(),
            config = config,
            runtimeSeconds = runtime,
        )

    private fun stream(id: String, title: String, size: Long): StreamItem =
        StreamItem(
            url = "https://example.com/$id.mkv",
            name = title,
            addonName = "Test",
            addonId = "test",
            behaviorHints = StreamBehaviorHints(videoSize = size),
        )

    private fun option(stream: StreamItem, tier: StreamResolutionTier, size: Long) = QualityDownloadOption(
        tier = tier,
        stream = stream,
        sizeBytes = size,
        bitrateKbps = null,
        codec = StreamCodec.HEVC,
        source = StreamSourceType.WEBDL,
    )
}
