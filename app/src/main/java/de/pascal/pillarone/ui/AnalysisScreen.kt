package de.pascal.pillarone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.pascal.pillarone.R
import de.pascal.pillarone.analysis.AccelerationRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun AnalysisScreen(
    state: AnalysisState = AnalysisState(),
    carSupported: Boolean = false,
    onEnableAutomatic: () -> Unit = {},
    onDisableAutomatic: () -> Unit = {},
    onRetry: () -> Unit = {},
    onSettings: () -> Unit = {},
    onLocationSettings: () -> Unit = {},
) {
    var trips by rememberSaveable { mutableStateOf(false) }
    var confirmConsent by rememberSaveable { mutableStateOf(false) }
    if (confirmConsent) {
        AlertDialog(
            onDismissRequest = { confirmConsent = false },
            title = { Text(stringResource(R.string.analysis_consent_title)) },
            text = { Text(stringResource(R.string.analysis_consent_text)) },
            confirmButton = {
                TextButton(onClick = { confirmConsent = false; onEnableAutomatic() },
                    modifier = Modifier.testTag("analysis-consent-accept")) {
                    Text(stringResource(R.string.analysis_consent_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmConsent = false }) {
                    Text(stringResource(R.string.analysis_consent_cancel))
                }
            },
        )
    }
    Column(Modifier.fillMaxSize().testTag("analysis-page")) {
        Text(stringResource(R.string.navigation_analysis),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp).semantics { heading() })
        PrimaryTabRow(selectedTabIndex = if (trips) 1 else 0) {
            Tab(selected = !trips, onClick = { trips = false },
                text = { Text(stringResource(R.string.analysis_acceleration)) })
            Tab(selected = trips, onClick = { trips = true },
                text = { Text(stringResource(R.string.analysis_trips)) })
        }
        if (trips) {
            Text(stringResource(R.string.analysis_trips_pending), Modifier.padding(24.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { Text(stringResource(R.string.analysis_explanation)) }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(when {
                                !state.automaticEnabled -> R.string.analysis_automatic_disabled
                                state.recording -> R.string.analysis_recording
                                else -> R.string.analysis_automatic_waiting
                            }),
                                style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(when {
                                !state.gpsEnabled -> R.string.analysis_gps_off
                                state.gpsReady -> R.string.analysis_gps_ready
                                else -> R.string.analysis_gps_waiting
                            }))
                            Text(stringResource(when {
                                !carSupported -> R.string.analysis_auto_firmware_required
                                state.carReady -> R.string.analysis_car_ready
                                else -> R.string.analysis_car_waiting
                            }))
                            if (state.automaticEnabled) {
                                OutlinedButton(onClick = onDisableAutomatic, modifier = Modifier.testTag("analysis-stop")) {
                                    Text(stringResource(R.string.analysis_disable_automatic))
                                }
                            } else {
                                Button(onClick = { confirmConsent = true }, enabled = !state.loading,
                                    modifier = Modifier.testTag("analysis-start")) {
                                    Text(stringResource(R.string.analysis_enable_automatic))
                                }
                            }
                        }
                    }
                }
                state.error?.let { error ->
                    item {
                        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                        when (error) {
                            R.string.analysis_storage_failed ->
                                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.analysis_retry)) }
                            R.string.analysis_location_denied, R.string.analysis_notification_denied ->
                                OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.analysis_settings)) }
                            R.string.analysis_location_disabled ->
                                OutlinedButton(onClick = onLocationSettings) { Text(stringResource(R.string.analysis_location_settings)) }
                            R.string.analysis_service_required ->
                                OutlinedButton(onClick = onEnableAutomatic) { Text(stringResource(R.string.analysis_prepare_service)) }
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.analysis_safety), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.analysis_foreground), style = MaterialTheme.typography.bodySmall)
                }
                if (state.loading) {
                    item { Text(stringResource(R.string.analysis_loading)) }
                } else if (state.records.isEmpty()) {
                    item { Text(stringResource(R.string.analysis_empty)) }
                }
                items(state.records, key = { it.id }) { record -> AccelerationRecordCard(record) }
                item { Text(stringResource(R.string.analysis_pairing_hint), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun AccelerationRecordCard(record: AccelerationRecord) {
    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(LocalLocale.current.platformLocale).withZone(ZoneId.of(record.zoneId))
        .format(Instant.ofEpochMilli(record.startedAt))
    Card(Modifier.fillMaxWidth().testTag("measurement-${record.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.analysis_interval, record.interval.fromKmh, record.interval.toKmh),
                style = MaterialTheme.typography.titleLarge)
            Text("$date (${record.zoneId})", style = MaterialTheme.typography.bodyMedium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Duration(stringResource(R.string.analysis_source_gps), record.gpsDurationMs)
                Duration(stringResource(R.string.analysis_source_car), record.carDurationMs)
            }
            val location = record.location
            Text(if (location == null) stringResource(R.string.analysis_location_missing) else
                stringResource(R.string.analysis_location, location.latitude, location.longitude, location.accuracyMetres),
                style = MaterialTheme.typography.bodySmall)
            val end = record.endLocation
            Text(if (end == null) stringResource(R.string.analysis_end_location_missing, record.interval.toKmh) else
                stringResource(R.string.analysis_end_location, record.interval.toKmh, end.latitude, end.longitude, end.accuracyMetres),
                style = MaterialTheme.typography.bodySmall)
            record.deviceId?.let {
                Text(stringResource(R.string.analysis_device, it), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Duration(source: String, durationMs: Long?) {
    Text(if (durationMs == null) stringResource(R.string.analysis_missing, source) else
        stringResource(R.string.analysis_duration, source, durationMs / 1000.0))
}
