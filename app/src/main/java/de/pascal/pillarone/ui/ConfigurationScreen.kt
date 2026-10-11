package de.pascal.pillarone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pascal.pillarone.R
import de.pascal.pillarone.ble.BleState
import de.pascal.pillarone.ble.ConnectionPhase
import de.pascal.pillarone.ble.MenuOperation
import de.pascal.pillarone.protocol.AckStatus

@Composable
fun ConfigurationScreen(
    state: BleState = BleState(),
    editor: MenuEditorState = MenuEditorState(),
    onLoad: () -> Unit = {},
    onRead: () -> Unit = {},
    onMove: (Int, Int) -> Unit = { _, _ -> },
    onVisibility: (Int, Boolean) -> Unit = { _, _ -> },
    onStartup: (Int) -> Unit = {},
    onSave: () -> Unit = {},
) {
    val supported = state.capabilities?.supportsMenu == true
    val ready = state.phase == ConnectionPhase.READY && supported
    val currentDevice = editor.deviceId == state.capabilities?.deviceId
    val editable = ready && currentDevice && !state.busy
    val configuration = editor.draft
    var confirmReload by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.phase, state.capabilities?.deviceId) { onLoad() }

    if (confirmReload) {
        AlertDialog(
            onDismissRequest = { confirmReload = false },
            title = { Text(stringResource(R.string.menu_discard_title)) },
            text = { Text(stringResource(R.string.menu_discard_explanation)) },
            confirmButton = {
                TextButton(
                    enabled = ready && !state.busy,
                    onClick = { confirmReload = false; onRead() },
                ) { Text(stringResource(R.string.menu_discard_read)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReload = false }) { Text(stringResource(R.string.menu_keep_draft)) }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("configuration-page"),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                stringResource(R.string.configuration_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        item {
            Text(
                stringResource(R.string.menu_explanation),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text(
                stringResource(when {
                    state.phase != ConnectionPhase.READY -> R.string.menu_connect_first
                    !supported -> R.string.menu_update_required
                    state.menuOperation == MenuOperation.READING -> R.string.menu_reading
                    state.menuOperation == MenuOperation.SAVING -> R.string.menu_saving
                    state.menuOperation == MenuOperation.ERROR -> when (state.rejection) {
                        AckStatus.CONFLICT -> R.string.menu_conflict
                        AckStatus.STORAGE_FAILED -> R.string.menu_storage_failed
                        AckStatus.BUSY -> R.string.rejection_busy
                        AckStatus.UNSUPPORTED_COMMAND, AckStatus.UNSUPPORTED_VERSION -> R.string.menu_update_required
                        else -> R.string.menu_failed
                    }
                    editor.dirty -> R.string.menu_unsaved
                    state.menuOperation == MenuOperation.SAVED -> R.string.menu_saved
                    configuration != null -> R.string.menu_loaded
                    else -> R.string.menu_read_hint
                }),
                modifier = Modifier.testTag("menu-status"),
            )
            if (state.menuOperation == MenuOperation.ERROR && state.phase != ConnectionPhase.READY) {
                Text(stringResource(R.string.menu_failed), modifier = Modifier.testTag("menu-error"))
            }
        }
        item {
            OutlinedButton(
                onClick = { if (editor.dirty) confirmReload = true else onRead() },
                enabled = ready && !state.busy,
                modifier = Modifier.testTag("menu-read"),
            ) { Text(stringResource(R.string.menu_read)) }
        }
        if (configuration != null) {
            itemsIndexed(configuration.pages, key = { _, page -> page.id }) { index, page ->
                val start = configuration.startupPageId == page.id
                Card(Modifier.fillMaxWidth().testTag("menu-page-${page.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(page.name, style = MaterialTheme.typography.titleMedium)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(if (start) R.string.menu_start_visible else R.string.menu_visible))
                            Switch(
                                checked = page.visible,
                                onCheckedChange = { onVisibility(page.id, it) },
                                enabled = editable && !start,
                                modifier = Modifier.testTag("menu-visible-${page.id}").semantics {
                                    contentDescription = page.name
                                },
                            )
                        }
                        TextButton(
                            onClick = { onStartup(page.id) },
                            enabled = editable && page.visible && page.startupAllowed && !start,
                            modifier = Modifier.testTag("menu-start-${page.id}").semantics {
                                contentDescription = page.name
                            },
                        ) { Text(stringResource(if (start) R.string.menu_start_page else R.string.menu_set_start)) }
                        if (!page.startupAllowed) {
                            Text(
                                stringResource(R.string.menu_start_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Row {
                            TextButton(
                                onClick = { onMove(page.id, -1) },
                                enabled = editable && index > 0,
                                modifier = Modifier.testTag("menu-up-${page.id}").semantics {
                                    contentDescription = page.name
                                },
                            ) { Text(stringResource(R.string.menu_move_up)) }
                            TextButton(
                                onClick = { onMove(page.id, 1) },
                                enabled = editable && index < configuration.pages.lastIndex,
                                modifier = Modifier.testTag("menu-down-${page.id}").semantics {
                                    contentDescription = page.name
                                },
                            ) { Text(stringResource(R.string.menu_move_down)) }
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = onSave,
                enabled = editable && editor.dirty && configuration != null,
                modifier = Modifier.fillMaxWidth().testTag("menu-save"),
            ) { Text(stringResource(R.string.menu_save)) }
        }
        item {
            Text(
                stringResource(R.string.menu_limits),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview
@Composable
private fun ConfigurationScreenPreview() {
    PillarOneTheme { ConfigurationScreen() }
}
