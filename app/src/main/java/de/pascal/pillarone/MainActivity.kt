package de.pascal.pillarone

import android.bluetooth.BluetoothAdapter
import android.Manifest
import android.content.pm.PackageManager
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.pascal.pillarone.ble.BleDevice
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pascal.pillarone.ble.BlePermissions
import de.pascal.pillarone.ui.DisplayScreen
import de.pascal.pillarone.ui.ConfigurationScreen
import de.pascal.pillarone.ui.DisplayViewModel
import de.pascal.pillarone.ui.AnalysisViewModel
import de.pascal.pillarone.ui.AnalysisScreen
import de.pascal.pillarone.ui.SettingsScreen
import de.pascal.pillarone.ble.ConnectionPhase
import de.pascal.pillarone.ui.PillarOneApp
import de.pascal.pillarone.ui.PillarOneTheme
import de.pascal.pillarone.media.PhoneMediaSessions

class MainActivity : ComponentActivity() {
    private val model: DisplayViewModel by viewModels()
    private val analysis: AnalysisViewModel by viewModels()
    private var preciseLocationGranted by mutableStateOf(false)

    private val locationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) prepareAutomaticRecording()
        else analysis.locationDenied()
    }

    private fun enableAutomaticRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) prepareAutomaticRecording()
        else locationPermissions.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        ))
    }

    private fun prepareAutomaticRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) automaticNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else analysis.enableAutomatic()
    }

    private val automaticNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) analysis.enableAutomatic() else analysis.notificationDenied()
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { model.refreshAvailability() }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { model.refreshAvailability() }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { model.refreshAvailability() }

    private fun connect(device: BleDevice) {
        model.connect(device)
        if (BlePermissions.hasPermissions(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notification_requested", false)
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_requested", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PillarOneTheme {
                val analysisState = analysis.state.collectAsStateWithLifecycle().value
                PillarOneApp(
                    analysisContent = {
                        val connection = model.state.collectAsStateWithLifecycle().value
                        AnalysisScreen(
                            state = analysisState,
                            carSupported = connection.phase == ConnectionPhase.READY &&
                                connection.capabilities?.supportsAutomaticAcceleration == true,
                            onEnableAutomatic = ::enableAutomaticRecording,
                            onDisableAutomatic = analysis::disableAutomatic,
                            onRetry = analysis::retryStorage,
                            onSettings = {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                })
                            },
                            onLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                        )
                    },
                    settingsContent = {
                        val analysisState = analysis.state.collectAsStateWithLifecycle().value
                        SettingsScreen(
                            mediaAccessGranted = model.media.collectAsStateWithLifecycle().value.accessGranted,
                            automaticGpsEnabled = analysisState.automaticEnabled,
                            preciseLocationGranted = preciseLocationGranted,
                            error = analysisState.error,
                            onMediaAccessChange = { startActivity(PhoneMediaSessions.settingsIntent(this)) },
                            onGpsAccessChange = { enabled ->
                                if (enabled) enableAutomaticRecording() else analysis.disableAutomatic()
                            },
                            onLocationPermissionSettings = {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                })
                            },
                        )
                    },
                    configurationContent = {
                        ConfigurationScreen(
                            state = model.state.collectAsStateWithLifecycle().value,
                            editor = model.menuEditor.collectAsStateWithLifecycle().value,
                            onLoad = model::loadMenuIfNeeded,
                            onRead = model::readMenu,
                            onMove = model::moveMenuPage,
                            onVisibility = model::setMenuPageVisible,
                            onStartup = model::setMenuStartupPage,
                            onSave = model::saveMenu,
                        )
                    },
                ) {
                    DisplayScreen(
                        state = model.state.collectAsStateWithLifecycle().value,
                        onRequestPermissions = { permissions.launch(BlePermissions.requiredPermissions()) },
                        onOpenSettings = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        },
                        onEnableBluetooth = {
                            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        },
                        onEnableLocation = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                        onScan = model::startScan,
                        onStopScan = model::stopScan,
                        onConnect = ::connect,
                        onDisconnect = model::disconnect,
                        onSendTurn = model::sendTestTurn,
                        onSendOff = model::sendOff,
                        media = model.media.collectAsStateWithLifecycle().value,
                        onEnableMedia = { startActivity(PhoneMediaSessions.settingsIntent(this)) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        preciseLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        (application as PillarApplication).acceleration.onAppVisible()
        model.onAppVisible()
    }

    override fun onStop() {
        val pairingDialog = model.isPairingUiActive() &&
            getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        (application as PillarApplication).acceleration.onAppHidden()
        if (!isChangingConfigurations) {
            if (pairingDialog) model.onPairingDialogCovered() else model.onAppHidden()
        }
        super.onStop()
    }
}
