package de.pascal.pillarone.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import de.pascal.pillarone.ble.BleState
import de.pascal.pillarone.ble.ConnectionPhase
import de.pascal.pillarone.ble.MenuOperation
import de.pascal.pillarone.protocol.AckStatus
import de.pascal.pillarone.protocol.DeviceCapabilities
import de.pascal.pillarone.protocol.MenuConfiguration
import de.pascal.pillarone.protocol.MenuPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConfigurationScreenTest {
    @get:Rule val compose = createComposeRule()
    private val configuration = MenuConfiguration(
        1, 1, listOf(MenuPage(1, "Maps", true), MenuPage(2, "Music", true)),
    )
    private val ready = BleState(
        phase = ConnectionPhase.READY, capabilities = DeviceCapabilities("device", 7, 192, 30),
    )
    private val editor = MenuEditorState().loaded("device", configuration)

    private fun reveal(tag: String) =
        compose.onNodeWithTag("configuration-page").performScrollToNode(hasTestTag(tag)).let {
            compose.onNodeWithTag(tag)
        }

    @Test fun disconnectedAndOldFirmwareCannotReadOrSave() {
        val state = mutableStateOf(BleState())
        compose.setContent { PillarOneTheme { ConfigurationScreen(state.value) } }
        reveal("menu-read").assertIsNotEnabled()
        reveal("menu-save").assertIsNotEnabled()
        compose.runOnIdle { state.value = ready.copy(capabilities = ready.capabilities!!.copy(bits = 3)) }
        reveal("menu-status").assertTextContains("Firmware-Update", substring = true)
        reveal("menu-read").assertIsNotEnabled()
    }

    @Test fun editsOnlyDraftAndProtectsStartPage() {
        val draft = mutableStateOf(editor)
        var saved: MenuConfiguration? = null
        compose.setContent {
            PillarOneTheme {
                ConfigurationScreen(
                    ready, draft.value,
                    onMove = { id, direction -> draft.value = draft.value.move(id, direction) },
                    onVisibility = { id, visible -> draft.value = draft.value.visibility(id, visible) },
                    onStartup = { draft.value = draft.value.startup(it) },
                    onSave = { saved = draft.value.draft },
                )
            }
        }
        reveal("menu-save").assertIsNotEnabled()
        reveal("menu-visible-1").assertIsNotEnabled()
        reveal("menu-up-1").assertIsNotEnabled()
        reveal("menu-start-2").performClick()
        reveal("menu-visible-1").assertIsEnabled().performClick()
        reveal("menu-up-2").performClick()
        reveal("menu-save").assertIsEnabled().performClick()
        assertEquals(listOf(2, 1), saved!!.pages.map { it.id })
        assertEquals(2, saved!!.startupPageId)
        assertEquals(false, saved!!.pages[1].visible)
        assertEquals(configuration, draft.value.source)
    }

    @Test fun refreshingDirtyDraftRequiresConfirmation() {
        var reads = 0
        compose.setContent {
            PillarOneTheme {
                ConfigurationScreen(ready, editor.move(2, -1), onRead = { reads++ })
            }
        }
        reveal("menu-read").performClick()
        assertEquals(0, reads)
        compose.onNodeWithText("Entwurf behalten").performClick()
        assertEquals(0, reads)
        reveal("menu-read").performClick()
        compose.onNodeWithText("Verwerfen und auslesen").performClick()
        assertEquals(1, reads)
    }

    @Test fun savingLocksAllActionsAndConflictKeepsDraft() {
        val state = mutableStateOf(ready.copy(busy = true, menuOperation = MenuOperation.SAVING))
        val dirty = editor.move(2, -1)
        compose.setContent { PillarOneTheme { ConfigurationScreen(state.value, dirty) } }
        reveal("menu-read").assertIsNotEnabled()
        reveal("menu-save").assertIsNotEnabled()
        reveal("menu-start-2").assertIsNotEnabled()
        compose.runOnIdle {
            state.value = ready.copy(menuOperation = MenuOperation.ERROR, rejection = AckStatus.CONFLICT)
        }
        reveal("menu-status").assertTextContains("hat sich geändert", substring = true)
        reveal("menu-save").assertIsEnabled()
        assertEquals(listOf(2, 1), dirty.draft!!.pages.map { it.id })
    }

    @Test fun differentDeviceCannotReceiveExistingDraft() {
        compose.setContent {
            PillarOneTheme {
                ConfigurationScreen(
                    ready.copy(capabilities = ready.capabilities!!.copy(deviceId = "different")),
                    editor.move(2, -1),
                )
            }
        }
        reveal("menu-save").assertIsNotEnabled()
    }

    @Test fun firmwareRestrictedPagesHaveDisabledStartupAction() {
        val restricted = configuration.copy(
            pages = configuration.pages.map { if (it.id == 2) it.copy(startupAllowed = false) else it },
        )
        compose.setContent {
            PillarOneTheme {
                ConfigurationScreen(ready, editor.loaded("device", restricted))
            }
        }
        reveal("menu-start-2").assertIsNotEnabled()
        reveal("menu-visible-2").assertIsEnabled()
    }
}
