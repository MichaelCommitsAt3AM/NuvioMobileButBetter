package com.nuvio.app.features.player

import androidx.media3.common.Format
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoDimensionsTest {

    @Test
    fun `plain landscape format is returned unchanged`() {
        val (width, height) = resolveVideoDimensions(
            width = 1920,
            height = 1080,
            decodedWidth = Format.NO_VALUE,
            decodedHeight = Format.NO_VALUE,
            rotationDegrees = 0,
            pixelWidthHeightRatio = 1f,
            fallbackWidth = 0,
            fallbackHeight = 0,
        )
        assertEquals(1920, width)
        assertEquals(1080, height)
    }

    @Test
    fun `portrait-shot clip with 90 degree rotation swaps width and height`() {
        // Coded frame is landscape 1920x1080, but the clip was shot in portrait and carries
        // rotationDegrees=90 metadata - the displayed video is actually 1080x1920.
        val (width, height) = resolveVideoDimensions(
            width = 1920,
            height = 1080,
            decodedWidth = Format.NO_VALUE,
            decodedHeight = Format.NO_VALUE,
            rotationDegrees = 90,
            pixelWidthHeightRatio = 1f,
            fallbackWidth = 0,
            fallbackHeight = 0,
        )
        assertEquals(1080, width)
        assertEquals(1920, height)
    }

    @Test
    fun `270 degree rotation also swaps width and height`() {
        val (width, height) = resolveVideoDimensions(
            width = 1920,
            height = 1080,
            decodedWidth = Format.NO_VALUE,
            decodedHeight = Format.NO_VALUE,
            rotationDegrees = 270,
            pixelWidthHeightRatio = 1f,
            fallbackWidth = 0,
            fallbackHeight = 0,
        )
        assertEquals(1080, width)
        assertEquals(1920, height)
    }

    @Test
    fun `180 degree rotation keeps orientation`() {
        val (width, height) = resolveVideoDimensions(
            width = 1920,
            height = 1080,
            decodedWidth = Format.NO_VALUE,
            decodedHeight = Format.NO_VALUE,
            rotationDegrees = 180,
            pixelWidthHeightRatio = 1f,
            fallbackWidth = 0,
            fallbackHeight = 0,
        )
        assertEquals(1920, width)
        assertEquals(1080, height)
    }

    @Test
    fun `anamorphic pixel aspect ratio stretches width before rotation`() {
        // 1440x1080 coded frame with 1.333 PAR is displayed as 1920x1080.
        val (width, height) = resolveVideoDimensions(
            width = 1440,
            height = 1080,
            decodedWidth = Format.NO_VALUE,
            decodedHeight = Format.NO_VALUE,
            rotationDegrees = 0,
            pixelWidthHeightRatio = 1.3333334f,
            fallbackWidth = 0,
            fallbackHeight = 0,
        )
        assertEquals(1920, width)
        assertEquals(1080, height)
    }

    @Test
    fun `decoder crop rectangle is preferred over the padded decoded buffer`() {
        // Decoded buffer is 1920x1088 (macroblock-padded); the real frame is 1920x1080.
        val (width, height) = resolveVideoDimensions(
            width = 1920,
            height = 1080,
            decodedWidth = 1920,
            decodedHeight = 1088,
            rotationDegrees = 0,
            pixelWidthHeightRatio = 1f,
            fallbackWidth = 0,
            fallbackHeight = 0,
        )
        assertEquals(1920, width)
        assertEquals(1080, height)
    }

    @Test
    fun `missing format dimensions fall back to the supplied fallback size`() {
        val (width, height) = resolveVideoDimensions(
            width = Format.NO_VALUE,
            height = Format.NO_VALUE,
            decodedWidth = Format.NO_VALUE,
            decodedHeight = Format.NO_VALUE,
            rotationDegrees = 0,
            pixelWidthHeightRatio = 1f,
            fallbackWidth = 1280,
            fallbackHeight = 720,
        )
        assertEquals(1280, width)
        assertEquals(720, height)
    }

    @Test
    fun `zero or negative pixel aspect ratio is ignored rather than collapsing the width`() {
        val (width, height) = resolveVideoDimensions(
            width = 1920,
            height = 1080,
            decodedWidth = Format.NO_VALUE,
            decodedHeight = Format.NO_VALUE,
            rotationDegrees = 0,
            pixelWidthHeightRatio = 0f,
            fallbackWidth = 0,
            fallbackHeight = 0,
        )
        assertEquals(1920, width)
        assertEquals(1080, height)
    }

    @Test
    fun `zero size input is passed through without throwing`() {
        val (width, height) = resolveVideoDimensions(
            width = Format.NO_VALUE,
            height = Format.NO_VALUE,
            decodedWidth = Format.NO_VALUE,
            decodedHeight = Format.NO_VALUE,
            rotationDegrees = 90,
            pixelWidthHeightRatio = 1f,
            fallbackWidth = 0,
            fallbackHeight = 0,
        )
        assertEquals(0, width)
        assertEquals(0, height)
    }
}
