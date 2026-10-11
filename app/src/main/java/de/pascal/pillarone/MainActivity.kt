package de.pascal.pillarone

import android.bluetooth.BluetoothAdapter
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pascal.pillarone.ble.BlePermissions
import de.pascal.pillarone.ui.DisplayScreen
import de.pascal.pillarone.ui.ConfigurationScreen
import de.pascal.pillarone.ui.DisplayViewModel
import de.pascal.pillarone.ui.PillarOneApp
import de.pascal.pillarone.ui.PillarOneTheme

class MainActivity : ComponentActivity() {
    private val model: DisplayViewModel by viewModels()

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { model.refreshAvailability() }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { model.refreshAvailability() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PillarOneTheme {
                PillarOneApp(
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
                        onConnect = model::connect,
                        onDisconnect = model::disconnect,
                        onSendTurn = model::sendTestTurn,
                        onSendOff = model::sendOff,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.onAppVisible()
    }

    override fun onStop() {
        val pairingDialog = model.isPairingUiActive() &&
            getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!isChangingConfigurations) {
            if (pairingDialog) model.onPairingDialogCovered() else model.onAppHidden()
        }
        super.onStop()
    }
}
