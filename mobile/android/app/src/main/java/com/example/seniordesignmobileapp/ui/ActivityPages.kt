package com.example.seniordesignmobileapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.seniordesignmobileapp.model.AggregatorUiState

@Composable
fun SittingPage(
    uiState: AggregatorUiState,
    permissionsGranted: Boolean,
    onGrantPermissions: () -> Unit,
    onReconnect: () -> Unit,
    onCalibrateSitting: () -> Unit,
    onSetUpperBackSensor: (Int?) -> Unit,
    onSetLowerBackSensor: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    PageContent(
        title = "Sitting",
        uiState = uiState,
        permissionsGranted = permissionsGranted,
        onGrantPermissions = onGrantPermissions,
        onReconnect = onReconnect,
        modifier = modifier,
    ) { elapsedRealtimeMs ->
        ActivitySetupCard(
            title = "Sensor Setup",
            lines = listOf(
                "V1 sitting analysis uses two back-mounted nodes.",
                "Choose which live sensor is upper back and which is lower back.",
                "You can leave either role on Auto, but explicit selection is preferred with real hardware.",
            ),
        )
        SittingSensorSelectionCard(
            uiState = uiState,
            onSetUpperBackSensor = onSetUpperBackSensor,
            onSetLowerBackSensor = onSetLowerBackSensor,
        )
        ActivitySetupCard(
            title = "Calibration Flow",
            lines = listOf(
                "1. Connect both back sensors and wait for live samples.",
                "2. Sit upright in the reference posture.",
                "3. Tap Calibrate sitting to capture the baseline bend angle.",
                "4. Use the live score and bend delta as posture drifts from that baseline.",
            ),
        )
        AnalysisCard(
            analysis = uiState.analysis,
            elapsedRealtimeMs = elapsedRealtimeMs,
            onCalibrateSitting = onCalibrateSitting,
        )
    }
}

@Composable
private fun SittingSensorSelectionCard(
    uiState: AggregatorUiState,
    onSetUpperBackSensor: (Int?) -> Unit,
    onSetLowerBackSensor: (Int?) -> Unit,
) {
    val analysis = uiState.analysis

    DetailCard(title = "Choose Sensor Roles") {
        Text("Lower back")
        SensorRoleSelectorRow(
            sensorIds = analysis.availableSensorIds,
            selectedSensorId = analysis.manualLowerBackSensorId,
            onSelectSensor = onSetLowerBackSensor,
        )
        Text("Upper back")
        SensorRoleSelectorRow(
            sensorIds = analysis.availableSensorIds,
            selectedSensorId = analysis.manualUpperBackSensorId,
            onSelectSensor = onSetUpperBackSensor,
        )
        if (analysis.availableSensorIds.isEmpty()) {
            Text("Waiting for live sensors before manual role selection is available.")
        }
    }
}

@Composable
private fun SensorRoleSelectorRow(
    sensorIds: List<Int>,
    selectedSensorId: Int?,
    onSelectSensor: (Int?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedSensorId == null,
            onClick = { onSelectSensor(null) },
            label = { Text("Auto") },
        )
        sensorIds.forEach { sensorId ->
            FilterChip(
                selected = selectedSensorId == sensorId,
                onClick = { onSelectSensor(sensorId) },
                label = { Text("Sensor $sensorId") },
            )
        }
    }
}

@Composable
fun ActivityPlaceholderPage(
    pageTitle: String,
    activitySummary: String,
    setupLines: List<String>,
    calibrationLines: List<String>,
    uiState: AggregatorUiState,
    permissionsGranted: Boolean,
    onGrantPermissions: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageContent(
        title = pageTitle,
        uiState = uiState,
        permissionsGranted = permissionsGranted,
        onGrantPermissions = onGrantPermissions,
        onReconnect = onReconnect,
        modifier = modifier,
    ) { _ ->
        ActivitySetupCard(
            title = "Activity Summary",
            lines = listOf(activitySummary),
        )
        ActivitySetupCard(
            title = "Sensor Setup",
            lines = setupLines,
        )
        ActivitySetupCard(
            title = "Calibration",
            lines = calibrationLines,
        )
        ActivitySetupCard(
            title = "Implementation Status",
            lines = listOf(
                "This activity page is intentionally scaffolded before its analysis pipeline exists.",
                "Use Diagnostics and Export while defining the sensor layout, calibration flow, and score model for this activity.",
            ),
        )
    }
}
