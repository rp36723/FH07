package com.example.seniordesignmobileapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.seniordesignmobileapp.model.AggregatorUiState
import com.example.seniordesignmobileapp.model.SavedSessionSummary

@Composable
fun ExportPage(
    uiState: AggregatorUiState,
    permissionsGranted: Boolean,
    onGrantPermissions: () -> Unit,
    onReconnect: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onShareSession: (SavedSessionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    PageContent(
        title = "Data Export",
        uiState = uiState,
        permissionsGranted = permissionsGranted,
        onGrantPermissions = onGrantPermissions,
        onReconnect = onReconnect,
        modifier = modifier,
    ) { elapsedRealtimeMs ->
        ActivitySetupCard(
            title = "Export Workflow",
            lines = listOf(
                "Use this page to record sessions, review recent saved files, and share JSONL exports.",
                "These exports are the current bridge from live BLE collection into offline analysis and future modeling work.",
            ),
        )
        RecordingCard(
            uiState = uiState,
            elapsedRealtimeMs = elapsedRealtimeMs,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onShareSession = onShareSession,
        )
    }
}
