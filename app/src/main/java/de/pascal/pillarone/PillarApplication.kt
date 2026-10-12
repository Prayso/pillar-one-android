package de.pascal.pillarone

import android.app.Application
import de.pascal.pillarone.analysis.AccelerationRecordingRuntime
import de.pascal.pillarone.ble.BleRuntime

class PillarApplication : Application() {
    val ble by lazy { BleRuntime(this) }
    val acceleration by lazy { AccelerationRecordingRuntime(this, ble) }
}