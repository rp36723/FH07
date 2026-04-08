package com.example.seniordesignmobileapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.seniordesignmobileapp.model.AggregatorUiState

@Composable
fun DiagnosticsPage(
    uiState: AggregatorUiState,
    permissionsGranted: Boolean,
    onGrantPermissions: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageContent(
        title = "Diagnostics",
        uiState = uiState,
        permissionsGranted = permissionsGranted,
        onGrantPermissions = onGrantPermissions,
        onReconnect = onReconnect,
        modifier = modifier,
    ) { elapsedRealtimeMs ->
        OverviewCard(
            uiState = uiState,
            elapsedRealtimeMs = elapsedRealtimeMs,
        )
        SensorTableCard(
            networkStatus = uiState.networkStatus,
            rawHex = uiState.lastStatusHex,
        )
        LatestSampleCard(
            sample = uiState.latestSample,
            receivedAtElapsedMs = uiState.lastSampleReceivedAtElapsedMs,
            rawHex = uiState.lastSampleHex,
            elapsedRealtimeMs = elapsedRealtimeMs,
        )
        DiagnosticsCard(uiState = uiState)
    }
}
