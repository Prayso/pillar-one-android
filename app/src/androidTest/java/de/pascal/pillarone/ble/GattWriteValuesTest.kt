package de.pascal.pillarone.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class GattWriteValuesTest {
    @Suppress("DEPRECATION")
    @Test fun notificationsPopulateCachedAndExplicitValues() {
        val descriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        assertNull(descriptor.value)
        val value = GattWriteValues.notifications(descriptor)
        assertArrayEquals(byteArrayOf(1, 0), value)
        assertArrayEquals(value, descriptor.value)
    }

    @Suppress("DEPRECATION")
    @Test fun commandPopulatesCachedValueAndUsesWriteWithResponse() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.fromString("9a8b0002-7e4f-4c21-9d63-2b51a670c8e0"),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val input = byteArrayOf(0x50, 0x31)
        val value = GattWriteValues.command(characteristic, input)
        input[0] = 0
        assertArrayEquals(byteArrayOf(0x50, 0x31), value)
        assertArrayEquals(value, characteristic.value)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, characteristic.writeType)
    }
}
