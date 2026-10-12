package de.pascal.pillarone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.pascal.pillarone.R
import de.pascal.pillarone.analysis.AccelerationInterval
import de.pascal.pillarone.analysis.AccelerationRecord
import de.pascal.pillarone.analysis.AccelerationSort
import de.pascal.pillarone.analysis.MeasurementLocation
import de.pascal.pillarone.analysis.MeasurementSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AnalysisScreen(
    state: AnalysisState = AnalysisState(),
    onEnableAutomatic: () -> Unit = {},
    onRetry: () -> Unit = {},
    onSettings: () -> Unit = {},
    onLocationSettings: () -> Unit = {},
    onDelete: (Long) -> Unit = {},
) {
    var trips by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(AccelerationSort.NEWEST) }
    var interval by rememberSaveable { mutableStateOf<AccelerationInterval?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val selected = state.records.firstOrNull { it.id == selectedId }
    if (selected != null) {
        AccelerationDetails(selected, onClose = { selectedId = null })
        return
    }
    Column(Modifier.fillMaxSize().testTag("analysis-page")) {
        Text(stringResource(R.string.navigation_analysis),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(24.dp).semantics { heading() })
        PrimaryTabRow(selectedTabIndex = if (trips) 1 else 0) {
            Tab(selected = !trips, onClick = { trips = false },
                text = { Text(stringResource(R.string.analysis_acceleration)) })
            Tab(selected = trips, onClick = { trips = true },
                text = { Text(stringResource(R.string.analysis_trips)) })
        }
        if (trips) {
            Text(stringResource(R.string.analysis_trips_pending), Modifier.padding(24.dp))
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionMenu(
                    label = stringResource(sort.label()),
                    options = AccelerationSort.entries.toList(),
                    optionLabel = { stringResource(it.label()) },
                    onSelect = { sort = it },
                    tag = "analysis-sort",
                    modifier = Modifier.weight(1f),
                )
                SelectionMenu(
                    label = intervalLabel(interval),
                    options = listOf(null) + AccelerationInterval.entries,
                    optionLabel = { intervalLabel(it) },
                    onSelect = { interval = it },
                    tag = "analysis-filter",
                    modifier = Modifier.weight(1f),
                )
            }
            val records = remember(state.records, interval, sort) {
                sort.sorted(state.records.filter { interval == null || it.interval == interval })
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("analysis-records"),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                if (state.loading) {
                    item { Text(stringResource(R.string.analysis_loading)) }
                }
                items(records, key = { it.id }) { record ->
                    AccelerationRecordCard(record, onOpen = { selectedId = record.id },
                        onDelete = { onDelete(record.id) })
                }
            }
        }
    }
}

private fun AccelerationSort.label(): Int = when (this) {
    AccelerationSort.NEWEST -> R.string.analysis_sort_newest
    AccelerationSort.OLDEST -> R.string.analysis_sort_oldest
    AccelerationSort.GPS_FASTEST -> R.string.analysis_sort_gps_fastest
    AccelerationSort.GPS_SLOWEST -> R.string.analysis_sort_gps_slowest
    AccelerationSort.CAR_FASTEST -> R.string.analysis_sort_car_fastest
    AccelerationSort.CAR_SLOWEST -> R.string.analysis_sort_car_slowest
}

@Composable
private fun intervalLabel(interval: AccelerationInterval?): String =
    if (interval == null) stringResource(R.string.analysis_filter_all)
    else stringResource(R.string.analysis_interval, interval.fromKmh, interval.toKmh)

@Composable
private fun <T> SelectionMenu(
    label: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("$tag-menu")) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
private fun AccelerationRecordCard(record: AccelerationRecord, onOpen: () -> Unit, onDelete: () -> Unit) {
    val revealWidth = with(LocalDensity.current) { 72.dp.toPx() }
    var offset by remember(record.id) { mutableFloatStateOf(0f) }
    val deleteLabel = stringResource(R.string.analysis_delete)
    val revealLabel = stringResource(R.string.analysis_reveal_delete)
    val locale = LocalLocale.current.platformLocale
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        if (offset < 0f) {
            FilledIconButton(
                onClick = onDelete,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                    .size(48.dp).testTag("measurement-delete-${record.id}"),
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = deleteLabel)
            }
        }
        Card(
            onClick = { if (offset < 0f) offset = 0f else onOpen() },
            modifier = Modifier.fillMaxWidth()
                .absoluteOffset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    state = rememberDraggableState { delta ->
                        offset = (offset + delta).coerceIn(-revealWidth, 0f)
                    },
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        offset = if (velocity < -500f || offset < -revealWidth / 2) -revealWidth else 0f
                    },
                )
                .semantics {
                    customActions = listOf(CustomAccessibilityAction(revealLabel) {
                        offset = -revealWidth
                        true
                    })
                }
                .testTag("measurement-${record.id}"),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(intervalLabel(record.interval), style = MaterialTheme.typography.titleMedium)
                Text(measurementDate(record.startedAt, record.zoneId, locale),
                    style = MaterialTheme.typography.bodyMedium)
                Text("${compactDuration(record.gpsDurationMs, locale)} / ${compactDuration(record.carDurationMs, locale)}",
                    style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun compactDuration(durationMs: Long?, locale: Locale): String =
    if (durationMs == null) "\u2014" else String.format(locale, "%.2f s", durationMs / 1000.0)

private fun measurementDate(timestamp: Long, zoneId: String, locale: Locale): String =
    DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm:ss", locale).withZone(ZoneId.of(zoneId))
        .format(Instant.ofEpochMilli(timestamp))

@Composable
private fun AccelerationDetails(record: AccelerationRecord, onClose: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().testTag("measurement-details")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("measurement-back")) {
                Icon(painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.analysis_back))
            }
            Text(intervalLabel(record.interval), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DetailSection(stringResource(R.string.analysis_measurement)) {
                DetailValue(stringResource(R.string.analysis_date),
                    measurementDate(record.startedAt, record.zoneId, locale))
                DetailValue(stringResource(R.string.analysis_timezone_label), record.zoneId)
            }
            if (record.results.isEmpty()) {
                DetailSection(stringResource(R.string.analysis_source_gps)) {
                    Duration(stringResource(R.string.analysis_source_gps), record.gpsDurationMs)
                }
                DetailSection(stringResource(R.string.analysis_source_car)) {
                    Duration(stringResource(R.string.analysis_source_car), record.carDurationMs)
                }
                DetailSection(stringResource(R.string.analysis_location_details)) {
                    Locations(record.location, record.endLocation, record.interval)
                    record.deviceId?.let { DetailValue(stringResource(R.string.analysis_device_label), it) }
                }
            } else {
                MeasurementSource.entries.forEach { source ->
                    val result = record.results.firstOrNull { it.source == source }
                    val sourceLabel = stringResource(if (source == MeasurementSource.GPS)
                        R.string.analysis_source_gps else R.string.analysis_source_car)
                    DetailSection(sourceLabel) {
                        Duration(sourceLabel, result?.durationMs)
                        if (result != null) {
                            DetailValue(stringResource(R.string.analysis_started_at_label),
                                measurementDate(result.startedAt, record.zoneId, locale))
                            DetailValue(stringResource(R.string.analysis_ended_at_label),
                                measurementDate(result.endedAt, record.zoneId, locale))
                            Locations(result.location, result.endLocation, record.interval)
                            result.deviceId?.let { DetailValue(stringResource(R.string.analysis_device_label), it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Locations(start: MeasurementLocation?, end: MeasurementLocation?, interval: AccelerationInterval) {
    Text(if (start == null) stringResource(R.string.analysis_location_missing) else
        stringResource(R.string.analysis_location, start.latitude, start.longitude, start.accuracyMetres))
    Text(if (end == null) stringResource(R.string.analysis_end_location_missing, interval.toKmh) else
        stringResource(R.string.analysis_end_location, interval.toKmh, end.latitude, end.longitude, end.accuracyMetres))
}

@Composable
private fun Duration(source: String, durationMs: Long?) {
    Text(if (durationMs == null) stringResource(R.string.analysis_missing, source) else
        stringResource(R.string.analysis_duration, source, durationMs / 1000.0))
}
