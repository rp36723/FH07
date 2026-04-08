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
                "This page is intended to visualize the sensor nodes in 3D space and show how they relate to one another.",
                "It is not primarily a model-training or classification page.",
            ),
        )
        ActivitySetupCard(
            title = "Near-Term Direction",
            lines = listOf(
                "Use live and recorded sensor data to place nodes in a shared 3D view.",
                "Show node orientation and relative position clearly enough to inspect posture setups and calibration behavior.",
                "Expand this page later once additional posture classifications and visualization needs are defined.",
            ),
        )
    }
}
