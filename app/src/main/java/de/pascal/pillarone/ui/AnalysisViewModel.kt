package de.pascal.pillarone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.pascal.pillarone.PillarApplication

class AnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as PillarApplication).acceleration
    val state = runtime.state

    fun enableAutomatic() = runtime.enableAutomatic()
    fun disableAutomatic() = runtime.disableAutomatic()
    fun locationDenied() = runtime.locationDenied()
    fun notificationDenied() = runtime.notificationDenied()
    fun retryStorage() = runtime.retryStorage()
    fun deleteRecord(recordId: Long) = runtime.deleteRecord(recordId)
}
