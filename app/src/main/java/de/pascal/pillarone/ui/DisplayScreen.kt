package de.pascal.pillarone.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pascal.pillarone.R
import de.pascal.pillarone.ble.BleDevice
import de.pascal.pillarone.ble.BleIssue
import de.pascal.pillarone.ble.BleState
import de.pascal.pillarone.ble.ConnectionPhase
import de.pascal.pillarone.ble.ProtocolMode
import de.pascal.pillarone.ble.CommandDelivery
import de.pascal.pillarone.ble.TestCommand
import de.pascal.pillarone.protocol.AckStatus

@Composable
fun DisplayScreen(
    state: BleState,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onEnableLocation: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onDisconnect: () -> Unit,
    onSendTurn: () -> Unit,
    onSendOff: () -> Unit,
) {
    val available = state.bluetoothAvailable && state.bluetoothEnabled && state.permissionsGranted
    val connected = state.phase in setOf(
        ConnectionPhase.CONNECTING, ConnectionPhase.DISCOVERING, ConnectionPhase.READY,
        ConnectionPhase.SUBSCRIBING, ConnectionPhase.HANDSHAKING,
        ConnectionPhase.BONDING,
        ConnectionPhase.RECONNECTING,
    )
    Scaffold { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).testTag("display-list"),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.prototype_title),
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    SupportingText(R.string.prototype_subtitle)
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (available && state.phase == ConnectionPhase.READY) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    ),
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionTitle(R.string.connection_title)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (connected && state.phase != ConnectionPhase.READY) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Box(
                                    Modifier.size(10.dp).background(
                                        if (available && state.phase == ConnectionPhase.READY) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        CircleShape,
                                    ),
                                )
                            }
                            Text(
                                stringResource(connectionStatus(state)),
                                modifier = Modifier.testTag("connection-status").semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        state.device?.let {
                            Text(it.name, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (state.phase == ConnectionPhase.RECONNECTING) {
                            Text(
                                stringResource(
                                    R.string.reconnect_progress, state.reconnectAttempt,
                                    state.reconnectDelaySeconds,
                                ),
                                Modifier.testTag("reconnect-progress"),
                            )
                        }
                        if (state.phase == ConnectionPhase.ERROR && state.reconnectAttempt == 3) {
                            Text(stringResource(R.string.reconnect_exhausted))
                        }
                        if (state.devices.any { it.systemConnected } && state.phase != ConnectionPhase.READY) {
                            Text(
                                stringResource(R.string.system_connection_explanation),
                                Modifier.testTag("system-connection"),
                            )
                        }
                        if (!state.permissionsGranted && state.bluetoothAvailable) {
                            Button(onClick = onRequestPermissions) { Text(stringResource(R.string.grant_permission)) }
                            OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
                        } else if (!state.bluetoothEnabled && state.bluetoothAvailable) {
                            Button(onClick = onEnableBluetooth) { Text(stringResource(R.string.enable_bluetooth)) }
                        }
                        state.issue?.takeIf { issueMessage(it) != connectionStatus(state) }?.let {
                            Text(
                                stringResource(issueMessage(it)),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("connection-error").semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                            )
                        }
                        state.rejection?.let {
                            Text(
                                stringResource(rejectionMessage(it)),
                                Modifier.testTag("rejection-status").semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.issue == BleIssue.LOCATION_DISABLED) {
                            OutlinedButton(onClick = onEnableLocation) { Text(stringResource(R.string.enable_location)) }
                        }
                        if (connected) {
                            OutlinedButton(
                                onClick = onDisconnect,
                                modifier = Modifier.testTag("disconnect-button"),
                            ) { Text(stringResource(R.string.disconnect)) }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(R.string.scan_title)
                    SupportingText(R.string.scan_explanation)
                    if (state.scanning) {
                        Text(stringResource(R.string.scanning), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(
                            onClick = onStopScan,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.scan_stop)) }
                    } else {
                        Button(
                            onClick = onScan,
                            enabled = available && !connected,
                            modifier = Modifier.fillMaxWidth().testTag("scan-button"),
                        ) { Text(stringResource(R.string.scan_start)) }
                    }
                    if (state.devices.isEmpty() && !state.scanning) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            SupportingText(R.string.scan_empty, Modifier.fillMaxWidth().padding(20.dp))
                        }
                    }
                }
            }
            items(state.devices, key = { it.address }) { device ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        device.rssi?.let {
                            SupportingText(
                                if (it >= -75) R.string.device_signal_near else R.string.device_signal_far,
                            )
                        }
                        if (device.systemConnected) {
                            Text(
                                stringResource(R.string.device_system_connected),
                                Modifier.testTag("system-link-${device.address}"),
                            )
                        } else if (device.paired) {
                            Text(stringResource(R.string.device_paired))
                        }
                        OutlinedButton(
                            onClick = { onConnect(device) },
                            enabled = available && !connected,
                            modifier = Modifier.fillMaxWidth().testTag("connect-${device.address}"),
                        ) { Text(stringResource(R.string.connect_device, device.name)) }
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionTitle(R.string.test_title)
                        NavigationPreview()
                        SupportingText(
                            when {
                                state.phase != ConnectionPhase.READY -> R.string.test_connect_first
                                state.protocol == ProtocolMode.V1 -> R.string.test_ttl
                                else -> R.string.test_manual_end
                            },
                        )
                        Button(
                            onClick = onSendTurn,
                            enabled = available && state.phase == ConnectionPhase.READY && !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("send-turn"),
                        ) { Text(stringResource(R.string.send_turn)) }
                        OutlinedButton(
                            onClick = onSendOff,
                            enabled = available && state.phase == ConnectionPhase.READY && !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("send-off"),
                        ) { Text(stringResource(R.string.send_off)) }
                        if (state.busy && state.phase == ConnectionPhase.READY) {
                            Text(stringResource(
                                if (state.protocol != ProtocolMode.V1) R.string.write_busy
                                else if (state.delivery == CommandDelivery.BLE_WRITTEN) R.string.application_busy
                                else R.string.ack_busy,
                            ), Modifier.testTag("command-progress"))
                        } else if (state.lastWrite != null) {
                            Text(
                                stringResource(
                                    if (state.delivery == CommandDelivery.APP_ACCEPTED) {
                                        if (state.lastWrite == TestCommand.TURN_RIGHT) R.string.turn_accepted else R.string.end_accepted
                                    } else {
                                        if (state.lastWrite == TestCommand.TURN_RIGHT) R.string.turn_written else R.string.off_written
                                    },
                                ),
                                Modifier.testTag("command-result").semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }
            }
            item {
                Column(
                    Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionTitle(R.string.usage_title)
                    SupportingText(
                        if (state.protocol == ProtocolMode.V1) R.string.v1_limits else R.string.prototype_limits,
                    )
                    SupportingText(
                        if (state.protocol == ProtocolMode.V1) R.string.v1_foreground_limits else R.string.foreground_limits,
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationPreview() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.test_preview), style = MaterialTheme.typography.labelMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val arrowColor = MaterialTheme.colorScheme.onPrimaryContainer
                Canvas(Modifier.size(64.dp)) {
                    val arrow = Path().apply {
                        moveTo(size.width * 0.25f, size.height * 0.85f)
                        lineTo(size.width * 0.25f, size.height * 0.35f)
                        quadraticTo(size.width * 0.25f, size.height * 0.25f, size.width * 0.4f, size.height * 0.25f)
                        lineTo(size.width * 0.85f, size.height * 0.25f)
                    }
                    val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawPath(arrow, arrowColor, style = stroke)
                    drawLine(
                        arrowColor,
                        Offset(size.width * 0.65f, size.height * 0.05f),
                        Offset(size.width * 0.85f, size.height * 0.25f),
                        strokeWidth = stroke.width, cap = StrokeCap.Round,
                    )
                    drawLine(
                        arrowColor,
                        Offset(size.width * 0.65f, size.height * 0.45f),
                        Offset(size.width * 0.85f, size.height * 0.25f),
                        strokeWidth = stroke.width, cap = StrokeCap.Round,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.test_distance), style = MaterialTheme.typography.displaySmall)
                    Text(stringResource(R.string.test_instruction), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun SupportingText(@StringRes text: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(text),
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(
        stringResource(text),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
}

@StringRes
private fun rejectionMessage(status: AckStatus): Int = when (status) {
    AckStatus.OK -> R.string.rejection_unconfirmed
    AckStatus.BUSY -> R.string.rejection_busy
    AckStatus.UNSUPPORTED_VERSION, AckStatus.UNSUPPORTED_COMMAND -> R.string.rejection_unsupported
    AckStatus.INVALID_PAYLOAD -> R.string.rejection_invalid
    AckStatus.WRONG_SESSION, AckStatus.STALE_SEQUENCE -> R.string.rejection_reconnect
    AckStatus.NOT_AUTHORIZED -> R.string.rejection_unauthorized
}

@StringRes
private fun connectionStatus(state: BleState): Int = when {
    !state.bluetoothAvailable -> R.string.bluetooth_unavailable
    !state.permissionsGranted -> R.string.permission_missing_status
    !state.bluetoothEnabled -> R.string.bluetooth_disabled
    else -> when (state.phase) {
        ConnectionPhase.IDLE -> if (state.devices.any { it.systemConnected }) {
            R.string.phase_system_connected
        } else {
            R.string.phase_idle
        }
        ConnectionPhase.CONNECTING -> R.string.phase_connecting
        ConnectionPhase.DISCOVERING -> R.string.phase_discovering
        ConnectionPhase.BONDING -> R.string.phase_bonding
        ConnectionPhase.SUBSCRIBING -> R.string.phase_subscribing
        ConnectionPhase.HANDSHAKING -> if (state.delivery == CommandDelivery.BLE_WRITTEN) {
            R.string.phase_handshake_response
        } else {
            R.string.phase_handshaking
        }
        ConnectionPhase.RECONNECTING -> R.string.phase_reconnecting
        ConnectionPhase.READY -> if (state.protocol == ProtocolMode.V1) R.string.phase_ready_v1 else R.string.phase_ready
        ConnectionPhase.DISCONNECTED -> if (state.devices.any { it.systemConnected }) {
            R.string.phase_system_connected
        } else {
            R.string.phase_disconnected
        }
        ConnectionPhase.INCOMPATIBLE -> R.string.phase_incompatible
        ConnectionPhase.ERROR -> R.string.phase_error
    }
}

@StringRes
private fun issueMessage(issue: BleIssue): Int = when (issue) {
    BleIssue.PERMISSION_REQUIRED -> R.string.permission_required
    BleIssue.BLUETOOTH_UNAVAILABLE -> R.string.bluetooth_unavailable
    BleIssue.BLUETOOTH_DISABLED -> R.string.bluetooth_disabled
    BleIssue.LOCATION_DISABLED -> R.string.location_disabled
    BleIssue.SCAN_FAILED -> R.string.scan_failed
    BleIssue.CONNECTION_FAILED -> R.string.connection_failed
    BleIssue.CONNECTION_TIMEOUT -> R.string.connection_timeout
    BleIssue.SERVICE_MISSING -> R.string.service_missing
    BleIssue.CHARACTERISTIC_MISSING -> R.string.characteristic_missing
    BleIssue.WRITE_UNSUPPORTED -> R.string.write_unsupported
    BleIssue.WRITE_FAILED -> R.string.write_failed
    BleIssue.WRITE_TIMEOUT -> R.string.write_timeout
    BleIssue.CONNECTION_LOST -> R.string.connection_lost
    BleIssue.BUSY -> R.string.busy
    BleIssue.NOTIFY_UNSUPPORTED -> R.string.notify_unsupported
    BleIssue.SUBSCRIBE_FAILED -> R.string.subscribe_failed
    BleIssue.SUBSCRIBE_TIMEOUT -> R.string.subscribe_timeout
    BleIssue.PROTOCOL_INVALID -> R.string.protocol_invalid
    BleIssue.APPLICATION_TIMEOUT -> R.string.application_timeout
    BleIssue.APPLICATION_REJECTED -> R.string.application_rejected
    BleIssue.COMMAND_INVALID -> R.string.command_invalid
    BleIssue.SEQUENCE_EXHAUSTED -> R.string.sequence_exhausted
    BleIssue.BOND_FAILED -> R.string.bond_failed
    BleIssue.BOND_TIMEOUT -> R.string.bond_timeout
}

@Preview(showBackground = true)
@Composable
private fun DisplayPreview() {
    PillarOneTheme {
        DisplayScreen(
            state = BleState(bluetoothEnabled = true, permissionsGranted = true),
            onRequestPermissions = {}, onOpenSettings = {}, onEnableBluetooth = {},
            onEnableLocation = {}, onScan = {}, onStopScan = {}, onConnect = {},
            onDisconnect = {}, onSendTurn = {}, onSendOff = {},
        )
    }
}
