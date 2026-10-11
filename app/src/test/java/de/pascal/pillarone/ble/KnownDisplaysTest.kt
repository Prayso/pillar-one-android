package de.pascal.pillarone.ble

import org.junit.Assert.*
import org.junit.Test

class KnownDisplaysTest {
    private val first = BleDevice("00:11:22:33:44:55", "Pillar One")
    private val second = BleDevice("00:11:22:33:44:66", "Pillar One")

    @Test fun connectedDisplayCanBeRestoredWithoutScanOrSavedSelection() {
        val connected = first.copy(paired = true, systemConnected = true)
        assertEquals(connected, restorationCandidate(listOf(connected), null))
    }

    @Test fun lastAcceptedBondedDisplayIsPreferredWithoutAdvertising() {
        val selected = first.copy(paired = true)
        assertEquals(selected, restorationCandidate(listOf(selected, second.copy(systemConnected = true)), first.address))
    }

    @Test fun unpairedOldAddressAndScanOnlyDeviceAreNotAutomaticallyRestored() {
        assertNull(restorationCandidate(listOf(first.copy(rssi = -50)), first.address))
    }

    @Test fun multipleConnectedDisplaysRequireExplicitSelection() {
        assertNull(restorationCandidate(listOf(first.copy(systemConnected = true), second.copy(systemConnected = true)), null))
    }

    @Test fun unknownBondedDisplayRemainsAUserChoice() {
        assertNull(restorationCandidate(listOf(first.copy(paired = true)), null))
    }

    @Test fun scanAndSystemResultsAreDeduplicatedWithoutFakeSignalStrength() {
        val merged = mergeKnownDevices(
            listOf(first.copy(rssi = -40)),
            listOf(first.copy(paired = true, systemConnected = true), second.copy(paired = true)),
        )
        assertEquals(2, merged.size)
        assertEquals(-40, merged[0].rssi)
        assertTrue(merged[0].systemConnected)
        assertNull(merged[1].rssi)
    }

    @Test fun disconnectedAndForgottenDevicesLoseTheirSystemFlags() {
        val old = listOf(first.copy(rssi = -40, paired = true, systemConnected = true), second.copy(paired = true))
        assertEquals(listOf(first.copy(rssi = -40)), mergeKnownDevices(old, emptyList()))
    }

    @Test fun pairedDevicesRemainVisibleWhenThereAreNoScanResults() {
        assertEquals(listOf(first.copy(paired = true)), mergeKnownDevices(emptyList(), listOf(first.copy(paired = true))))
    }
}
