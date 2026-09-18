package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleStyleStateTest {

    @Test
    fun testHdrAwareOpacityDimsOnlyWhenContentAndDisplayAreBothHdr() {
        val style = SubtitleStyleState(textColor = Color.White)

        assertEquals(1f, style.withHdrAwareOpacity(isHdrContent = false, displaySupportsHdr = false).textColor.alpha)
        assertEquals(1f, style.withHdrAwareOpacity(isHdrContent = true, displaySupportsHdr = false).textColor.alpha)
        assertEquals(1f, style.withHdrAwareOpacity(isHdrContent = false, displaySupportsHdr = true).textColor.alpha)
        assertEquals(0.8f, style.withHdrAwareOpacity(isHdrContent = true, displaySupportsHdr = true).textColor.alpha)
    }

    @Test
    fun testHdrAwareOpacityMultipliesExistingAlphaInsteadOfOverwriting() {
        val style = SubtitleStyleState(textColor = Color.White.copy(alpha = 0.5f))

        val adjusted = style.withHdrAwareOpacity(isHdrContent = true, displaySupportsHdr = true)

        assertTrue(abs(0.4f - adjusted.textColor.alpha) < 1e-4f)
    }

    @Test
    fun testHdrAwareOpacityLeavesOtherStyleFieldsUnchanged() {
        val style = SubtitleStyleState(textColor = Color.White, outlineColor = Color.Red, fontSizeSp = 22)

        val adjusted = style.withHdrAwareOpacity(isHdrContent = true, displaySupportsHdr = true)

        assertEquals(style.outlineColor, adjusted.outlineColor)
        assertEquals(style.fontSizeSp, adjusted.fontSizeSp)
    }
}
