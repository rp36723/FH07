package com.example.seniordesignmobileapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.seniordesignmobileapp.model.AggregatorUiState

@Composable
fun ModelingPage(
    uiState: AggregatorUiState,
    permissionsGranted: Boolean,
    onGrantPermissions: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageContent(
        title = "Modeling",
        uiState = uiState,
        permissionsGranted = permissionsGranted,
        onGrantPermissions = onGrantPermissions,
        onReconnect = onReconnect,
        modifier = modifier,
    ) { _ ->
        ActivitySetupCard(
            title = "Modeling Status",
            lines = listOf(
                "This page is reserved for future model training, replay, inference tuning, or classification tooling.",
                "For now, recorded sessions from the Export page are the main modeling input.",
            ),
        )
        ActivitySetupCard(
            title = "Near-Term Direction",
            lines = listOf(
                "Replay exported sessions through the current analyzers.",
                "Compare calibration baselines and score curves across real posture data.",
                "Define how this app should interact with on-device vs offline models before building UI controls here.",
            ),
        )
    }
}
