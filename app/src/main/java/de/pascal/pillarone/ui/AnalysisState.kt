package de.pascal.pillarone.ui

import de.pascal.pillarone.analysis.AccelerationRecord

data class AnalysisState(
    val records: List<AccelerationRecord> = emptyList(),
    val loading: Boolean = true,
    val recording: Boolean = false,
    val gpsEnabled: Boolean = false,
    val gpsReady: Boolean = false,
    val carReady: Boolean = false,
    val automaticEnabled: Boolean = false,
    val serviceReady: Boolean = false,
    val error: Int? = null,
)
