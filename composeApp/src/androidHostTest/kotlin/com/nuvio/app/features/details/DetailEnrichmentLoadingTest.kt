package com.nuvio.app.features.details

import android.app.Application
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.DetailAdditionalInfoSection
import com.nuvio.app.features.details.components.DetailEnrichmentLoadingSection
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DetailEnrichmentLoadingTest {
    @get:Rule val compose = createComposeRule()
    private val meta = MetaDetails(id = "tmdb:1", type = "movie", name = "Title", ageRating = "PG")

    @Test fun trailersUseOneNoninteractiveLoadingCard() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NuvioTheme {
                DetailEnrichmentLoadingSection(MetaDetailsSection.TRAILERS, meta)
            }
        }
        compose.onNodeWithText("Trailers").assertIsDisplayed()
        compose.onNodeWithTag("details-loading-TRAILERS-0").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithTag("details-loading-TRAILERS-1").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("details-loading-TRAILERS-0").assertIsDisplayed()
    }

    @Test fun partialDetailsKeepTheirExistingCertificationWhileMissingFieldsLoad() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NuvioTheme { DetailAdditionalInfoSection(meta, loading = true) }
        }
        compose.onNodeWithText("PG").assertIsDisplayed()
        compose.onNodeWithText("Status").assertIsDisplayed()
        compose.onNodeWithText("Runtime").assertIsDisplayed()
    }
}
