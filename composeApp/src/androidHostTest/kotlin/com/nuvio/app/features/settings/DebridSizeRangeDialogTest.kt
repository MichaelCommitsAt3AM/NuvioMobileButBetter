package com.nuvio.app.features.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DebridSizeRangeDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun independentPresetsSaveOnlyAfterConfirmation() {
        var saved: Pair<Double, Double>? = null
        var dismissed = false
        compose.setContent { NuvioTheme { DebridSizeRangeDialog(0.0, 0.0, { min, max -> saved = min to max }, { dismissed = true }) } }
        compose.onNodeWithContentDescription("Minimum size").performClick()
        compose.onNodeWithText("500 MB").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Maximum size").performClick()
        compose.onNodeWithText("5 GB").performScrollTo().performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(0.5 to 5.0, saved); assertEquals(true, dismissed) }
    }

    @Ignore("Robolectric cannot reach layout idle with the two outlined decimal fields; verify this interaction on device.")
    @Test fun customValidationAndClearKeepChangesInTheDraft() {
        var saved: Pair<Double, Double>? = null
        // Text-field animations keep Robolectric's automatic frame loop busy.
        compose.mainClock.autoAdvance = false
        compose.setContent { NuvioTheme { DebridSizeRangeDialog(1.25, 2.75, { min, max -> saved = min to max }, {}) } }
        compose.mainClock.advanceTimeBy(300)
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("3,5")
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Save").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Maximum size must be at least the minimum size.").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("0")
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Save").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Enter a size greater than 0 GB.").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("0,75")
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Save").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertEquals(0.75 to 2.75, saved) }
        compose.onNodeWithText("Clear").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertEquals(0.75 to 2.75, saved) }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(0.0 to 0.0, saved) }
    }
}
