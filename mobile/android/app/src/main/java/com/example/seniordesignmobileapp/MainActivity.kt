package com.example.seniordesignmobileapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.seniordesignmobileapp.ui.theme.SeniorDesignMobileAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SeniorDesignMobileAppTheme {
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current
                val controller = remember { BleAggregatorController(applicationContext) }
                val uiState by controller.uiState.collectAsState()
                var permissionsGranted by remember {
                    mutableStateOf(hasRequiredBlePermissions(context))
                }
                var requestedPermissions by rememberSaveable { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    permissionsGranted = hasRequiredBlePermissions(context)
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            permissionsGranted = hasRequiredBlePermissions(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                DisposableEffect(Unit) {
                    onDispose { controller.stop() }
                }

                LaunchedEffect(permissionsGranted) {
                    if (permissionsGranted) {
                        controller.start()
                    } else {
                        controller.stop()
                    }
                }

                LaunchedEffect(permissionsGranted, requestedPermissions) {
                    if (!permissionsGranted && !requestedPermissions) {
                        requestedPermissions = true
                        permissionLauncher.launch(REQUIRED_BLE_PERMISSIONS)
                    }
                }

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) { innerPadding ->
                    AggregatorScreen(
                        uiState = uiState,
                        permissionsGranted = permissionsGranted,
                        onGrantPermissions = {
                            requestedPermissions = true
                            permissionLauncher.launch(REQUIRED_BLE_PERMISSIONS)
                        },
                        onReconnect = { controller.restart() },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun AggregatorScreen(
    uiState: AggregatorUiState,
    permissionsGranted: Boolean,
    onGrantPermissions: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "BLE Aggregator",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = uiState.connectionState,
            style = MaterialTheme.typography.bodyLarge,
        )

        DetailCard(title = "Diagnostics") {
            Text("Phase: ${uiState.connectionPhase.label}")
            Text("Reconnect attempts: ${uiState.reconnectCount}")
            Text("Connected: ${if (uiState.isConnected) "yes" else "no"}")
            uiState.lastFailureReason?.let { reason ->
                Text("Last failure: $reason")
            }

            if (uiState.recentEvents.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                uiState.recentEvents.takeLast(6).forEach { event ->
                    Text(event, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!permissionsGranted) {
                Button(onClick = onGrantPermissions) {
                    Text("Grant permissions")
                }
            }
            Button(
                onClick = onReconnect,
                enabled = permissionsGranted,
            ) {
                Text("Rescan")
            }
        }

        if (!permissionsGranted) {
            Text(
                text = "The app needs Bluetooth scan and connect permissions before it can find BLE-Aggregator.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        uiState.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
            )
        }

        DetailCard(title = "Network Status") {
            val status = uiState.networkStatus
            if (status == null) {
                Text("Waiting for network_status...")
            } else {
                Text("Protocol v${status.version}")
                Text("Uptime: ${formatDuration(status.uptimeMs)}")
                Text("Active sensors: ${status.activeSensorCount}")

                if (status.sensors.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    status.sensors.forEach { sensor ->
                        Text("Sensor ${sensor.sensorId}: seq ${sensor.seq}, age ${sensor.ageMs} ms")
                    }
                }
            }

            uiState.lastStatusHex?.let { hex ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Raw: $hex", style = MaterialTheme.typography.bodySmall)
            }
        }

        DetailCard(title = "Latest Sample") {
            val sample = uiState.latestSample
            if (sample == null) {
                Text("Waiting for sample_stream notifications...")
            } else {
                Text("Sensor ${sample.sensorId}")
                Text("Seq: ${sample.seq}")
                Text("Timestamp: ${sample.timestampMs} ms")
                Text("Accel: [${sample.ax}, ${sample.ay}, ${sample.az}]")
                Text("Gyro: [${sample.gx}, ${sample.gy}, ${sample.gz}]")
            }

            uiState.lastSampleHex?.let { hex ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Raw: $hex", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DetailCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}m ${seconds}s"
}

@Preview(showBackground = true)
@Composable
private fun AggregatorScreenPreview() {
    SeniorDesignMobileAppTheme {
        AggregatorScreen(
            uiState = AggregatorUiState(
                connectionState = "Streaming samples from BLE-Aggregator",
                connectionPhase = BleConnectionPhase.STREAMING,
                isConnected = true,
                reconnectCount = 2,
                lastFailureReason = "GATT connection timed out (status 8)",
                recentEvents = listOf(
                    "Scan started for BLE-Aggregator",
                    "Found BLE-Aggregator rssi=-58 address=AA:BB:CC:DD:EE:FF",
                    "Connected, requesting MTU 64",
                    "Services discovered",
                    "Subscribed to sample_stream",
                    "network_status updated: 2 sensors",
                ),
                latestSample = ImuSample(
                    version = 1,
                    sensorId = 7,
                    seq = 42,
                    timestampMs = 1_337,
                    ax = 10,
                    ay = -20,
                    az = 30,
                    gx = -40,
                    gy = 50,
                    gz = -60,
                ),
                networkStatus = NetworkStatus(
                    version = 1,
                    uptimeMs = 12_345,
                    activeSensorCount = 2,
                    sensors = listOf(
                        ActiveSensorStatus(sensorId = 1, seq = 120, ageMs = 75),
                        ActiveSensorStatus(sensorId = 7, seq = 42, ageMs = 18),
                    ),
                ),
                lastSampleHex = "01 07 2A 00 39 05 00 00 0A 00 EC FF 1E 00 D8 FF 32 00 C4 FF",
                lastStatusHex = "01 39 30 00 00 02 01 78 00 4B 00 07 2A 00 12 00",
            ),
            permissionsGranted = true,
            onGrantPermissions = {},
            onReconnect = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionsPreview() {
    SeniorDesignMobileAppTheme {
        AggregatorScreen(
            uiState = AggregatorUiState(
                recentEvents = listOf("Waiting for Bluetooth permissions"),
            ),
            permissionsGranted = false,
            onGrantPermissions = {},
            onReconnect = {},
        )
    }
}
