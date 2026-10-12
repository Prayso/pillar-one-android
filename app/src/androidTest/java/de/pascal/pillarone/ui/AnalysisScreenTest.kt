package de.pascal.pillarone.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import de.pascal.pillarone.analysis.AccelerationInterval
import de.pascal.pillarone.analysis.AccelerationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class AnalysisScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun record(id: Long = 1, interval: AccelerationInterval = AccelerationInterval.ZERO_TO_HUNDRED,
        gps: Long? = 20_550, car: Long? = 19_550,
        startedAt: Long = Instant.parse("2026-10-12T17:40:48Z").toEpochMilli()) =
        AccelerationRecord(id, interval, startedAt, "UTC", null, gps, car, "display")

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
        compose.onNodeWithTag("analysis-sort").assertIsDisplayed()
        compose.onNodeWithTag("analysis-start").assertDoesNotExist()
    }

    @Test fun compactCardOpensDetailsIncludingMissingResults() {
        val record = record(gps = null, car = 5_250)
        compose.setContent {
            PillarOneTheme {
                PillarOneApp(analysisContent = {
                    AnalysisScreen(AnalysisState(records = listOf(record), loading = false))
                }) { }
            }
        }
        compose.onNodeWithTag("navigation-analysis").performClick()
        val locale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.locales[0]
        compose.onNodeWithText("\u2014 / ${String.format(locale, "%.2f s", 5.25)}").assertIsDisplayed()
        compose.onNodeWithText("12.10.2026, 17:40:48").assertIsDisplayed()
        compose.onNodeWithText("GPS: nicht gemessen").assertDoesNotExist()
        compose.onNodeWithText("Startstandort:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Display: display").assertDoesNotExist()
        compose.onNodeWithTag("measurement-1").performClick()
        compose.onNodeWithTag("measurement-details").assertIsDisplayed()
        compose.onNodeWithTag("analysis-sort").assertDoesNotExist()
        compose.onNodeWithTag("measurement-1").assertDoesNotExist()
        compose.onNodeWithTag("navigation-display").assertIsDisplayed()
        compose.onNodeWithTag("navigation-configuration").assertIsDisplayed()
        compose.onNodeWithTag("navigation-analysis").assertIsDisplayed()
        compose.onNodeWithTag("navigation-settings").assertIsDisplayed()
        compose.onNodeWithText("GPS: nicht gemessen").assertIsDisplayed()
        compose.onNodeWithText("Startstandort: nicht verfügbar").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Zielstandort bei 100 km/h: nicht verfügbar").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("display").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("measurement-back").performClick()
        compose.onNodeWithTag("measurement-details").assertDoesNotExist()
        compose.onNodeWithTag("measurement-1").assertIsDisplayed()
    }

    @Test fun systemBackRestoresTheFilteredList() {
        lateinit var activity: ComponentActivity
        compose.setContent {
            activity = LocalActivity.current as ComponentActivity
            PillarOneTheme {
                AnalysisScreen(AnalysisState(records = listOf(record(), record(2,
                    interval = AccelerationInterval.HUNDRED_TO_TWO_HUNDRED)), loading = false))
            }
        }
        compose.onNodeWithTag("analysis-filter").performClick()
        compose.onNode(hasText("100-200 km/h") and hasAnyAncestor(hasTestTag("analysis-filter-menu")))
            .performClick()
        compose.onNodeWithTag("measurement-2").performClick()
        compose.onNodeWithTag("measurement-details").assertIsDisplayed()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("measurement-details").assertDoesNotExist()
        compose.onNodeWithTag("measurement-1").assertDoesNotExist()
        compose.onNodeWithTag("measurement-2").assertIsDisplayed()
    }

    @Test fun swipeOnlyRevealsDeleteAndTrashConfirmsRemoval() {
        var records by mutableStateOf(listOf(record(), record(2)))
        val deleted = mutableListOf<Long>()
        compose.setContent {
            PillarOneTheme {
                AnalysisScreen(AnalysisState(records = records, loading = false), onDelete = { id ->
                    deleted += id
                    records = records.filterNot { it.id == id }
                })
            }
        }
        compose.onNodeWithTag("measurement-delete-1").assertDoesNotExist()
        compose.onNodeWithTag("measurement-1").performTouchInput { swipeLeft() }
        assertTrue(deleted.isEmpty())
        compose.onNodeWithTag("measurement-delete-1").assertIsDisplayed()
        compose.onNodeWithTag("measurement-1").performTouchInput { swipeRight() }
        compose.onNodeWithTag("measurement-delete-1").assertDoesNotExist()
        compose.onNodeWithTag("measurement-1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("measurement-delete-1").performClick()
        assertEquals(listOf(1L), deleted)
        compose.onNodeWithTag("measurement-1").assertDoesNotExist()
        compose.onNodeWithTag("measurement-2").assertIsDisplayed()
    }

    @Test fun filteringAndSourceSpecificSortingChangeTheVisibleRecords() {
        val records = listOf(record(1, gps = 10_000, car = 30_000),
            record(2, gps = 20_000, car = 15_000),
            record(3, interval = AccelerationInterval.HUNDRED_TO_TWO_HUNDRED))
        compose.setContent {
            PillarOneTheme { AnalysisScreen(AnalysisState(records = records, loading = false)) }
        }
        compose.onNodeWithTag("analysis-filter").performClick()
        compose.onNode(hasText("100-200 km/h") and hasAnyAncestor(hasTestTag("analysis-filter-menu")))
            .performClick()
        compose.onNodeWithTag("measurement-1").assertDoesNotExist()
        compose.onNodeWithTag("measurement-2").assertDoesNotExist()
        compose.onNodeWithTag("measurement-3").assertIsDisplayed()
        compose.onNodeWithTag("analysis-filter").performClick()
        compose.onNodeWithText("0-100 km/h").performClick()
        compose.onNodeWithTag("measurement-3").assertDoesNotExist()
        compose.onNodeWithTag("analysis-sort").performClick()
        compose.onNodeWithText("GPS: schnellste zuerst").performClick()
        assertBefore("measurement-1", "measurement-2")
        compose.onNodeWithTag("analysis-sort").performClick()
        compose.onNodeWithText("Auto: schnellste zuerst").performClick()
        assertBefore("measurement-2", "measurement-1")
    }

    private fun assertBefore(first: String, second: String) {
        assertTrue(compose.onNodeWithTag(first).fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag(second).fetchSemanticsNode().boundsInRoot.top)
    }
}
