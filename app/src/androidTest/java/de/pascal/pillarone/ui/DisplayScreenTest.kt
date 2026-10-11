package de.pascal.pillarone.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import de.pascal.pillarone.ble.BleDevice
import de.pascal.pillarone.ble.BleState
import de.pascal.pillarone.ble.ConnectionPhase
import de.pascal.pillarone.ble.ProtocolMode
import de.pascal.pillarone.ble.CommandDelivery
import de.pascal.pillarone.ble.TestCommand
import de.pascal.pillarone.ble.BleIssue
import de.pascal.pillarone.protocol.AckStatus
import de.pascal.pillarone.protocol.DeviceCapabilities
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DisplayScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bottomNavigationSwitchesPagesWithoutDisconnectingAndKeepsScrollPosition() {
        var disconnectCount = 0
        var turnCount = 0
        show(
            ready(),
            onDisconnect = { disconnectCount++ },
            onSendTurn = { turnCount++ },
        )
        compose.onNodeWithTag("navigation-display").assertIsSelected()
        compose.onNodeWithTag("navigation-display").assertHeightIsEqualTo(64.dp)
        compose.onNodeWithTag("navigation-configuration").assertHeightIsEqualTo(64.dp)
        compose.onNodeWithTag("navigation-configuration").assertIsNotSelected()
        reveal("send-turn").assertIsDisplayed()
        compose.onNodeWithTag("navigation-configuration").assertIsDisplayed().performClick()
        compose.onNodeWithTag("configuration-page").assertIsDisplayed()
        compose.onNodeWithTag("display-list").assertDoesNotExist()
        compose.onNodeWithTag("navigation-configuration").assertIsSelected()
        compose.onNodeWithTag("navigation-display").assertIsNotSelected().performClick()
        compose.onNodeWithTag("configuration-page").assertDoesNotExist()
        compose.onNodeWithTag("send-turn").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(0, disconnectCount)
        assertEquals(1, turnCount)
    }

    @Test
    fun selectedPageSurvivesStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PillarOneTheme {
                PillarOneApp { androidx.compose.material3.Text("Display content") }
            }
        }
        compose.onNodeWithTag("navigation-configuration").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("navigation-configuration").assertIsSelected()
        compose.onNodeWithTag("configuration-page").assertIsDisplayed()
        compose.onNodeWithTag("navigation-display").performClick()
        compose.onNodeWithText("Display content").assertIsDisplayed()
    }

    @Test
    fun missingPermissionDisablesScanAndWrites() {
        show(BleState(bluetoothEnabled = true))
        reveal("scan-button").assertIsNotEnabled()
        reveal("send-turn").assertIsNotEnabled()
        reveal("send-off").assertIsNotEnabled()
    }

    @Test
    fun discoveringDoesNotEnableTestCommands() {
        show(
            BleState(
                bluetoothEnabled = true,
                permissionsGranted = true,
                phase = ConnectionPhase.DISCOVERING,
                busy = true,
            ),
        )
        reveal("send-turn").assertIsNotEnabled()
    }

    @Test
    fun readySessionCanSendBothCommands() {
        var turnCount = 0
        var offCount = 0
        show(ready(), onSendTurn = { turnCount++ }, onSendOff = { offCount++ })
        reveal("send-turn").assertIsEnabled().performClick()
        reveal("send-off").assertIsEnabled().performClick()
        assertEquals(1, turnCount)
        assertEquals(1, offCount)
    }

    @Test
    fun outstandingWriteDisablesBothCommands() {
        show(ready().copy(busy = true))
        reveal("send-turn").assertIsNotEnabled()
        reveal("send-off").assertIsNotEnabled()
    }

    @Test
    fun disconnectedSessionCannotSend() {
        show(ready().copy(phase = ConnectionPhase.DISCONNECTED))
        reveal("send-turn").assertIsNotEnabled()
        reveal("send-off").assertIsNotEnabled()
    }

    @Test
    fun revokedPermissionDisablesReadySessionCommands() {
        show(ready().copy(permissionsGranted = false))
        reveal("send-turn").assertIsNotEnabled()
        reveal("send-off").assertIsNotEnabled()
    }

    @Test
    fun disabledBluetoothDisablesReadySessionCommands() {
        show(ready().copy(bluetoothEnabled = false))
        reveal("send-turn").assertIsNotEnabled()
        reveal("send-off").assertIsNotEnabled()
    }

    @Test fun subscribingCannotSend() {
        show(ready().copy(protocol = ProtocolMode.V1, phase = ConnectionPhase.SUBSCRIBING, busy = true))
        reveal("connection-status").assertTextContains("Verbindung wird vorbereitet", substring = true)
        reveal("send-turn").assertIsNotEnabled()
        reveal("send-off").assertIsNotEnabled()
    }

    @Test fun systemPairingCannotSendOrStartAnotherScan() {
        show(ready().copy(protocol = ProtocolMode.V1, phase = ConnectionPhase.BONDING, busy = true))
        reveal("connection-status").assertTextContains("bestätige die Bluetooth-Kopplung", substring = true)
        reveal("scan-button").assertIsNotEnabled()
        reveal("send-turn").assertIsNotEnabled()
        reveal("send-off").assertIsNotEnabled()
    }

    @Test fun failedPairingShowsItsOwnError() {
        show(ready().copy(
            protocol = ProtocolMode.V1, phase = ConnectionPhase.ERROR, issue = BleIssue.BOND_FAILED,
        ))
        reveal("connection-error").assertTextContains("Kopplung wurde abgebrochen", substring = true)
        reveal("send-turn").assertIsNotEnabled()
    }

    @Test fun pairingTimeoutIsNotReportedAsWriteTimeout() {
        show(ready().copy(
            protocol = ProtocolMode.V1, phase = ConnectionPhase.ERROR, issue = BleIssue.BOND_TIMEOUT,
        ))
        reveal("connection-error").assertTextContains("60 Sekunden", substring = true)
        reveal("send-turn").assertIsNotEnabled()
    }

    @Test fun handshakeCannotSend() {
        show(ready().copy(protocol = ProtocolMode.V1, phase = ConnectionPhase.HANDSHAKING, busy = true))
        reveal("connection-status").assertTextContains("Verbindung wird geprüft", substring = true)
        reveal("send-turn").assertIsNotEnabled()
        reveal("send-off").assertIsNotEnabled()
    }

    @Test fun handshakeWaitsForApplicationResponseOnlyAfterTransportResponse() {
        show(ready().copy(
            protocol = ProtocolMode.V1, phase = ConnectionPhase.HANDSHAKING,
            busy = true, delivery = CommandDelivery.BLE_WRITTEN,
        ))
        reveal("connection-status").assertTextContains("Warte auf das Display", substring = true)
        reveal("send-turn").assertIsNotEnabled()
        reveal("send-off").assertIsNotEnabled()
    }

    @Test fun readyAndAcceptedResultUsePlainLanguage() {
        show(ready().copy(
            protocol = ProtocolMode.V1, lastWrite = TestCommand.TURN_RIGHT,
            delivery = CommandDelivery.APP_ACCEPTED,
        ))
        reveal("connection-status").assertTextContains("Display verbunden")
        reveal("send-turn").assertIsEnabled()
        reveal("command-result").assertTextContains("Display hat den Testhinweis bestätigt", substring = true)
    }

    @Test fun v1BleWrittenStillWaitsForApplicationAck() {
        show(ready().copy(
            protocol = ProtocolMode.V1, busy = true, delivery = CommandDelivery.BLE_WRITTEN,
        ))
        reveal("command-progress").assertTextContains("Warte auf die Bestätigung", substring = true)
        reveal("send-turn").assertIsNotEnabled()
        reveal("send-off").assertIsNotEnabled()
    }

    @Test fun legacyResultIsOnlyBleWritten() {
        show(ready().copy(
            protocol = ProtocolMode.LEGACY, lastWrite = TestCommand.OFF,
            delivery = CommandDelivery.BLE_WRITTEN,
        ))
        reveal("connection-status").assertTextContains("Display verbunden")
        reveal("command-result").assertTextContains("Beenden übertragen", substring = true)
    }

    @Test fun failedV1DoesNotLookLikeReadyLegacy() {
        show(ready().copy(
            protocol = ProtocolMode.V1, phase = ConnectionPhase.ERROR,
            issue = BleIssue.APPLICATION_TIMEOUT,
        ))
        reveal("connection-error").assertTextContains("Display hat den Hinweis nicht bestätigt", substring = true)
        reveal("send-turn").assertIsNotEnabled()
    }

    @Test fun rejectedCommandShowsStatusAndCanPreserveSession() {
        show(ready().copy(
            protocol = ProtocolMode.V1, issue = BleIssue.APPLICATION_REJECTED,
            rejection = AckStatus.BUSY,
        ))
        reveal("rejection-status").assertTextContains("Display ist gerade beschäftigt", substring = true)
        reveal("send-turn").assertIsEnabled()
    }

    @Test fun systemConnectionDoesNotEnableCommandsWithoutAppSession() {
        val device = BleDevice("00:11:22:33:44:55", "Pillar One", paired = true, systemConnected = true)
        var chosen: BleDevice? = null
        show(
            ready().copy(phase = ConnectionPhase.IDLE, device = null, devices = listOf(device)),
            onConnect = { chosen = it },
        )
        reveal("connection-status").assertTextContains("Display gefunden", substring = true)
        reveal("connect-${device.address}").assertIsEnabled().performClick()
        assertEquals(device, chosen)
        reveal("send-turn").assertIsNotEnabled()
        reveal("send-off").assertIsNotEnabled()
    }

    @Test fun bondedDisplayIsConnectableWithoutScanning() {
        val device = BleDevice("00:11:22:33:44:55", "Pillar One", paired = true)
        show(ready().copy(phase = ConnectionPhase.DISCONNECTED, device = null, devices = listOf(device)))
        reveal("connect-${device.address}").assertIsEnabled()
    }

    @Test fun reconnectWaitDisablesCommandsAndCanBeCancelled() {
        var disconnected = false
        show(
            ready().copy(
                phase = ConnectionPhase.RECONNECTING, reconnectAttempt = 2,
                reconnectDelaySeconds = 5,
            ),
            onDisconnect = { disconnected = true },
        )
        reveal("reconnect-progress").assertTextContains("Versuch 2 von 3", substring = true)
        reveal("disconnect-button").assertIsEnabled().performClick()
        assertEquals(true, disconnected)
        reveal("scan-button").assertIsNotEnabled()
        reveal("send-turn").assertIsNotEnabled()
        reveal("send-off").assertIsNotEnabled()
    }

    @Test fun technicalDeviceDetailsAreNotShown() {
        val device = BleDevice("00:11:22:33:44:55", "Pillar One", -45)
        show(ready().copy(
            protocol = ProtocolMode.V1,
            devices = listOf(device),
            capabilities = DeviceCapabilities("internal-device-id", 3, 192, 5),
        ))
        reveal("connection-status").assertTextContains("Display verbunden")
        compose.onNodeWithText("internal-device-id", substring = true).assertDoesNotExist()
        reveal("connect-${device.address}")
        compose.onNodeWithText(device.address, substring = true).assertDoesNotExist()
        compose.onNodeWithText("dBm", substring = true).assertDoesNotExist()
        compose.onNodeWithText("In deiner Nähe").assertExists()
    }

    @Test fun olderDisplayKeepsManualEndWarningWithoutProtocolTerms() {
        show(ready().copy(protocol = ProtocolMode.LEGACY))
        reveal("send-turn")
        compose.onNodeWithText("Bei diesem Display bleibt der Testhinweis stehen, bis du ihn beendest.")
            .assertExists()
        compose.onNodeWithText("Legacy", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Hinweis beenden").assertExists()
    }

    @Test fun currentDisplayExplainsAutomaticEnd() {
        show(ready().copy(protocol = ProtocolMode.V1))
        reveal("send-turn")
        compose.onNodeWithText("Der Testhinweis endet nach 5 Sekunden automatisch.").assertExists()
        compose.onNodeWithText("TTL", substring = true).assertDoesNotExist()
        compose.onNodeWithText("NAV_END", substring = true).assertDoesNotExist()
    }

    private fun ready() = BleState(
        bluetoothEnabled = true,
        permissionsGranted = true,
        phase = ConnectionPhase.READY,
        device = BleDevice("00:11:22:33:44:55", "Pillar One", -45),
    )

    private fun reveal(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNodeWithTag("display-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun show(
        state: BleState,
        onSendTurn: () -> Unit = {},
        onSendOff: () -> Unit = {},
        onConnect: (BleDevice) -> Unit = {},
        onDisconnect: () -> Unit = {},
    ) {
        compose.setContent {
            PillarOneTheme {
                PillarOneApp {
                    DisplayScreen(
                        state = state,
                        onRequestPermissions = {}, onOpenSettings = {}, onEnableBluetooth = {},
                        onEnableLocation = {}, onScan = {}, onStopScan = {}, onConnect = onConnect,
                        onDisconnect = onDisconnect, onSendTurn = onSendTurn, onSendOff = onSendOff,
                    )
                }
            }
        }
    }
}
