package com.example.seniordesignmobileapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.seniordesignmobileapp.model.AggregatorUiState
import com.example.seniordesignmobileapp.model.SavedSessionSummary

@Deprecated("Use AggregatorShell instead.")
@Composable
fun AggregatorScreen(
    uiState: AggregatorUiState,
    permissionsGranted: Boolean,
    onGrantPermissions: () -> Unit,
    onReconnect: () -> Unit,
    onCalibrateSitting: () -> Unit,
    onSetUpperBackSensor: (Int?) -> Unit,
    onSetLowerBackSensor: (Int?) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onShareSession: (SavedSessionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    AggregatorShell(
        uiState = uiState,
        permissionsGranted = permissionsGranted,
        onGrantPermissions = onGrantPermissions,
        onReconnect = onReconnect,
        onCalibrateSitting = onCalibrateSitting,
        onSetUpperBackSensor = onSetUpperBackSensor,
        onSetLowerBackSensor = onSetLowerBackSensor,
        onStartRecording = onStartRecording,
        onStopRecording = onStopRecording,
        onShareSession = onShareSession,
        modifier = modifier,
    )
}
