package com.example.seniordesignmobileapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.seniordesignmobileapp.model.AggregatorUiState

@Composable
fun SittingPage(
    uiState: AggregatorUiState,
    permissionsGranted: Boolean,
    onGrantPermissions: () -> Unit,
    onReconnect: () -> Unit,
    onCalibrateSitting: () -> Unit,
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
                "Current temporary mapping: lowest observed sensor ID is lower back, next observed sensor ID is upper back.",
                "Long term this page should let the user explicitly assign those roles.",
            ),
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
