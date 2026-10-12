package de.pascal.pillarone.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import de.pascal.pillarone.analysis.AccelerationInterval
import de.pascal.pillarone.analysis.AccelerationRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AnalysisScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navigationDistinguishesAccelerationAndFutureTrips() {
        compose.setContent {
            PillarOneTheme {
                PillarOneApp(analysisContent = { AnalysisScreen(AnalysisState(loading = false)) }) { }
            }
        }
        compose.onNodeWithTag("navigation-analysis").performClick()
        compose.onNodeWithTag("analysis-page").assertIsDisplayed()
        compose.onNodeWithText("Fahrten").performClick()
        compose.onNodeWithText("Fahrtenauswertungen folgen später.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Beschleunigung").performClick()
        compose.onNodeWithTag("analysis-start").performScrollTo().assertIsDisplayed()
    }

    @Test fun showsActualAndMissingResultsAndDisableAction() {
        var stops = 0
        val record = AccelerationRecord(1, AccelerationInterval.ZERO_TO_HUNDRED, 0, "UTC",
            null, null, 5_250, "display")
        compose.setContent {
            PillarOneTheme {
                AnalysisScreen(AnalysisState(records = listOf(record), loading = false, recording = true, automaticEnabled = true),
                    carSupported = true, onDisableAutomatic = { stops++ })
            }
        }
        compose.onNodeWithTag("analysis-stop").performScrollTo().performClick()
        assertEquals(1, stops)
        compose.onNodeWithTag("measurement-1").performScrollTo()
        compose.onNodeWithText("GPS: nicht gemessen").assertIsDisplayed()
        compose.onNode(hasText("Auto:", substring = true) and hasAnyAncestor(hasTestTag("measurement-1")))
            .assertIsDisplayed()
    }

    @Test fun enablingRequiresExplicitConsentAndCanBeCancelled() {
        var accepted = 0
        compose.setContent {
            PillarOneTheme {
                AnalysisScreen(AnalysisState(loading = false), onEnableAutomatic = { accepted++ })
            }
        }
        compose.onNodeWithTag("analysis-start").performScrollTo().performClick()
        assertEquals(0, accepted)
        compose.onNodeWithText("Nicht jetzt").performClick()
        assertEquals(0, accepted)
        compose.onNodeWithTag("analysis-start").performClick()
        compose.onNodeWithTag("analysis-consent-accept").performClick()
        assertEquals(1, accepted)
    }
}
