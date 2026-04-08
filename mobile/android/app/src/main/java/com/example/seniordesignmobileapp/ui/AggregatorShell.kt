package com.example.seniordesignmobileapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.seniordesignmobileapp.model.AggregatorUiState
import com.example.seniordesignmobileapp.model.SavedSessionSummary

private enum class AppPage(
    val label: String,
) {
    Diagnostics("Diagnostics"),
    Sitting("Sitting"),
    Cycling("Cycling"),
    Running("Running"),
    Export("Export"),
    Modeling("Modeling"),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AggregatorShell(
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
    var selectedPage by rememberSaveable { mutableStateOf(AppPage.Diagnostics.name) }
    val page = AppPage.valueOf(selectedPage)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "FH07 Mobile",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PageSelector(
                selectedPage = page,
                onSelectPage = { selectedPage = it.name },
            )
            when (page) {
                AppPage.Diagnostics -> DiagnosticsPage(
                    uiState = uiState,
                    permissionsGranted = permissionsGranted,
                    onGrantPermissions = onGrantPermissions,
                    onReconnect = onReconnect,
                    modifier = Modifier.fillMaxSize(),
                )

                AppPage.Sitting -> SittingPage(
                    uiState = uiState,
                    permissionsGranted = permissionsGranted,
                    onGrantPermissions = onGrantPermissions,
                    onReconnect = onReconnect,
                    onCalibrateSitting = onCalibrateSitting,
                    onSetUpperBackSensor = onSetUpperBackSensor,
                    onSetLowerBackSensor = onSetLowerBackSensor,
                    modifier = Modifier.fillMaxSize(),
                )

                AppPage.Cycling -> ActivityPlaceholderPage(
                    pageTitle = "Cycling",
                    activitySummary = "Cycling analysis is not implemented yet, but this page is reserved for sensor placement, calibration, and live activity-specific scoring.",
                    setupLines = listOf(
                        "Define which nodes belong on the torso, hips, or legs for cycling.",
                        "Decide whether cadence or asymmetry matters alongside posture.",
                        "Replace placeholders here once the cycling analyzer exists.",
                    ),
                    calibrationLines = listOf(
                        "TBD: likely a seated-on-bike neutral position.",
                        "TBD: determine whether separate calibrations are needed for road vs stationary setups.",
                    ),
                    uiState = uiState,
                    permissionsGranted = permissionsGranted,
                    onGrantPermissions = onGrantPermissions,
                    onReconnect = onReconnect,
                    modifier = Modifier.fillMaxSize(),
                )

                AppPage.Running -> ActivityPlaceholderPage(
                    pageTitle = "Running",
                    activitySummary = "Running analysis is not implemented yet, but this page will host its own sensor setup, calibration, and scoring workflow.",
                    setupLines = listOf(
                        "Define whether running uses back-only sensors or adds hip / thigh nodes.",
                        "Decide whether impact, symmetry, or trunk lean belong in the first score.",
                        "Keep transport and diagnostics separate from the future running model.",
                    ),
                    calibrationLines = listOf(
                        "TBD: standing neutral posture or a short warm-up sequence.",
                        "TBD: determine if motion-state detection is required before calibration.",
                    ),
                    uiState = uiState,
                    permissionsGranted = permissionsGranted,
                    onGrantPermissions = onGrantPermissions,
                    onReconnect = onReconnect,
                    modifier = Modifier.fillMaxSize(),
                )

                AppPage.Export -> ExportPage(
                    uiState = uiState,
                    permissionsGranted = permissionsGranted,
                    onGrantPermissions = onGrantPermissions,
                    onReconnect = onReconnect,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    onShareSession = onShareSession,
                    modifier = Modifier.fillMaxSize(),
                )

                AppPage.Modeling -> ModelingPage(
                    uiState = uiState,
                    permissionsGranted = permissionsGranted,
                    onGrantPermissions = onGrantPermissions,
                    onReconnect = onReconnect,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun PageSelector(
    selectedPage: AppPage,
    onSelectPage: (AppPage) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Pages",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppPage.entries.forEach { page ->
                FilterChip(
                    selected = page == selectedPage,
                    onClick = { onSelectPage(page) },
                    label = { Text(page.label) },
                )
            }
        }
    }
}
