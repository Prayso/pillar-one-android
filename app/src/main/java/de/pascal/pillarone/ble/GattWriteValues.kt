package de.pascal.pillarone.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor

internal object GattWriteValues {
    // Some Android 13 vendor stacks still read the cached value with the new write overloads.
    @Suppress("DEPRECATION")
    fun notifications(descriptor: BluetoothGattDescriptor): ByteArray {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.copyOf()
        descriptor.value = value
        return value
    }

    @Suppress("DEPRECATION")
    fun command(characteristic: BluetoothGattCharacteristic, payload: ByteArray): ByteArray {
        val value = payload.copyOf()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = value
        return value
    }
}
