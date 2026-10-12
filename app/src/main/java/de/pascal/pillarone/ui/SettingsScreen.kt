package de.pascal.pillarone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.pascal.pillarone.R

@Composable
fun SettingsScreen(
    mediaAccessGranted: Boolean = false,
    automaticGpsEnabled: Boolean = false,
    preciseLocationGranted: Boolean = false,
    error: Int? = null,
    onMediaAccessChange: (Boolean) -> Unit = {},
    onGpsAccessChange: (Boolean) -> Unit = {},
    onLocationPermissionSettings: () -> Unit = {},
) {
    var confirmGpsConsent by rememberSaveable { mutableStateOf(false) }
    if (confirmGpsConsent) {
        AlertDialog(
            onDismissRequest = { confirmGpsConsent = false },
            title = { Text(stringResource(R.string.analysis_consent_title)) },
            text = { Text(stringResource(R.string.analysis_consent_text)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmGpsConsent = false; onGpsAccessChange(true) },
                    modifier = Modifier.testTag("settings-gps-consent-accept"),
                ) {
                    Text(stringResource(R.string.analysis_consent_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmGpsConsent = false }) {
                    Text(stringResource(R.string.analysis_consent_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings-page"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                stringResource(R.string.navigation_settings),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.settings_media_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    if (mediaAccessGranted) R.string.settings_media_enabled
                                    else R.string.settings_media_disabled,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = mediaAccessGranted,
                            onCheckedChange = onMediaAccessChange,
                            modifier = Modifier.testTag("settings-media-switch"),
                        )
                    }
                    Text(stringResource(R.string.settings_media_explanation))
                    Text(stringResource(R.string.settings_media_privacy), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.settings_gps_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    if (automaticGpsEnabled) R.string.settings_gps_enabled
                                    else R.string.settings_gps_disabled,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = automaticGpsEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) confirmGpsConsent = true else onGpsAccessChange(false)
                            },
                            modifier = Modifier.testTag("settings-gps-switch"),
                        )
                    }
                    Text(stringResource(R.string.settings_gps_explanation))
                    Text(
                        stringResource(
                            if (preciseLocationGranted) R.string.settings_gps_permission_granted
                            else R.string.settings_gps_permission_missing,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onLocationPermissionSettings) {
                        Text(stringResource(R.string.settings_gps_permission_settings))
                    }
                }
            }
        }
        error?.let { message ->
            item { Text(stringResource(message), color = MaterialTheme.colorScheme.error) }
        }
    }
}
