package com.example.seniordesignmobileapp.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.seniordesignmobileapp.model.ActiveSensorStatus
import com.example.seniordesignmobileapp.model.AggregatorUiState
import com.example.seniordesignmobileapp.model.BleConnectionPhase
import com.example.seniordesignmobileapp.model.ImuSample
import com.example.seniordesignmobileapp.model.NetworkStatus
import com.example.seniordesignmobileapp.model.SavedSessionSummary
import com.example.seniordesignmobileapp.ui.theme.SeniorDesignMobileAppTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AggregatorScreen(
    uiState: AggregatorUiState,
    permissionsGranted: Boolean,
    onGrantPermissions: () -> Unit,
    onReconnect: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onShareSession: (SavedSessionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val elapsedRealtimeMs by rememberElapsedRealtime()

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

        RecordingCard(
            uiState = uiState,
            elapsedRealtimeMs = elapsedRealtimeMs,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onShareSession = onShareSession,
        )

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

        OverviewCard(
            uiState = uiState,
            elapsedRealtimeMs = elapsedRealtimeMs,
        )

        SensorTableCard(
            networkStatus = uiState.networkStatus,
            elapsedRealtimeMs = elapsedRealtimeMs,
            rawHex = uiState.lastStatusHex,
        )

        LatestSampleCard(
            sample = uiState.latestSample,
            receivedAtElapsedMs = uiState.lastSampleReceivedAtElapsedMs,
            rawHex = uiState.lastSampleHex,
            elapsedRealtimeMs = elapsedRealtimeMs,
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
    }
}

@Composable
private fun RecordingCard(
    uiState: AggregatorUiState,
    elapsedRealtimeMs: Long,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onShareSession: (SavedSessionSummary) -> Unit,
) {
    DetailCard(title = "Session Recording") {
        StatRow("State", if (uiState.isRecording) "Recording" else "Idle")
        StatRow("Session file", uiState.recordingSessionName ?: "None")
        if (uiState.isRecording) {
            StatRow(
                "Duration",
                formatRecordingDuration(uiState.recordingStartedAtElapsedMs, elapsedRealtimeMs),
            )
        }
        StatRow("Samples saved", uiState.recordedSampleCount.toString())
        StatRow("Status snapshots", uiState.recordedStatusCount.toString())
        uiState.recordingSessionPath?.let { path ->
            Text("Stored in: $path", style = MaterialTheme.typography.bodySmall)
        }
        uiState.recordingErrorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onStartRecording,
                enabled = !uiState.isRecording,
            ) {
                Text("Start recording")
            }
            Button(
                onClick = onStopRecording,
                enabled = uiState.isRecording,
            ) {
                Text("Stop recording")
            }
        }

        if (uiState.savedSessions.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Recent recordings",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            uiState.savedSessions.take(5).forEach { session ->
                SavedSessionRow(
                    session = session,
                    onShareSession = onShareSession,
                )
            }
        }
    }
}

@Composable
private fun SavedSessionRow(
    session: SavedSessionSummary,
    onShareSession: (SavedSessionSummary) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = session.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${formatFileSize(session.sizeBytes)} • ${formatTimestamp(session.modifiedAtEpochMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = { onShareSession(session) },
        ) {
            Text("Share")
        }
    }
}

@Composable
private fun OverviewCard(
    uiState: AggregatorUiState,
    elapsedRealtimeMs: Long,
) {
    val status = uiState.networkStatus
    val sample = uiState.latestSample

    DetailCard(title = "Overview") {
        StatRow("Device", uiState.deviceName)
        StatRow("Phase", uiState.connectionPhase.label)
        StatRow("Status update", formatAge(uiState.lastStatusReceivedAtElapsedMs, elapsedRealtimeMs))
        StatRow("Sample update", formatAge(uiState.lastSampleReceivedAtElapsedMs, elapsedRealtimeMs))

        if (status != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            StatRow("Aggregator uptime", formatUptime(status.uptimeMs))
            StatRow("Active sensors", status.activeSensorCount.toString())
        }

        if (sample != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            StatRow("Last sample sensor", sample.sensorId.toString())
            StatRow("Last sample seq", sample.seq.toString())
            StatRow("Sample timestamp", "${sample.timestampMs} ms")
        }
    }
}

@Composable
private fun SensorTableCard(
    networkStatus: NetworkStatus?,
    elapsedRealtimeMs: Long,
    rawHex: String?,
) {
    DetailCard(title = "Sensors") {
        if (networkStatus == null) {
            Text("Waiting for network_status...")
            return@DetailCard
        }

        if (networkStatus.sensors.isEmpty()) {
            Text("No active sensors reported.")
        } else {
            TableHeader()
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            networkStatus.sensors.forEach { sensor ->
                SensorRow(
                    sensor = sensor,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            }
        }

        rawHex?.let { hex ->
            Text("Raw: $hex", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LatestSampleCard(
    sample: ImuSample?,
    receivedAtElapsedMs: Long?,
    rawHex: String?,
    elapsedRealtimeMs: Long,
) {
    DetailCard(title = "Latest Sample") {
        if (sample == null) {
            Text("Waiting for sample_stream notifications...")
            return@DetailCard
        }

        StatRow("Sensor", sample.sensorId.toString())
        StatRow("Sequence", sample.seq.toString())
        StatRow("Timestamp", "${sample.timestampMs} ms")
        StatRow("Updated", formatAge(receivedAtElapsedMs, elapsedRealtimeMs))

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        AxisGroup(
            title = "Accel",
            x = sample.ax,
            y = sample.ay,
            z = sample.az,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        AxisGroup(
            title = "Gyro",
            x = sample.gx,
            y = sample.gy,
            z = sample.gz,
        )

        rawHex?.let { hex ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Raw: $hex", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TableCell("ID", weight = 0.16f, bold = true)
        TableCell("Seq", weight = 0.22f, bold = true)
        TableCell("Sensor age", weight = 0.32f, bold = true)
        TableCell("State", weight = 0.30f, bold = true)
    }
}

@Composable
private fun SensorRow(
    sensor: ActiveSensorStatus,
) {
    val state = when {
        sensor.ageMs <= 250 -> "Fresh"
        sensor.ageMs <= 1_000 -> "Aging"
        else -> "Stale?"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TableCell(sensor.sensorId.toString(), weight = 0.16f)
        TableCell(sensor.seq.toString(), weight = 0.22f)
        TableCell("${sensor.ageMs} ms", weight = 0.32f)
        TableCell(state, weight = 0.30f)
    }

    Text(
        text = "Observed ${formatSensorAge(sensor.ageMs.toLong())}",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun RowScope.TableCell(
    text: String,
    weight: Float,
    bold: Boolean = false,
) {
    Text(
        text = text,
        modifier = Modifier
            .weight(weight)
            .widthIn(min = 0.dp),
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Start,
    )
}

@Composable
private fun StatRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AxisGroup(
    title: String,
    x: Int,
    y: Int,
    z: Int,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AxisValue("X", x)
        AxisValue("Y", y)
        AxisValue("Z", z)
    }
}

@Composable
private fun RowScope.AxisValue(
    axis: String,
    value: Int,
) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = axis,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun rememberElapsedRealtime(): State<Long> =
    produceState(initialValue = SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }

private fun formatAge(
    receivedAtElapsedMs: Long?,
    elapsedRealtimeMs: Long,
): String {
    if (receivedAtElapsedMs == null) {
        return "Waiting"
    }
    return formatShortElapsed((elapsedRealtimeMs - receivedAtElapsedMs).coerceAtLeast(0))
}

private fun formatSensorAge(sensorAgeMs: Long): String {
    return "${formatShortElapsed(sensorAgeMs)} old"
}

private fun formatRecordingDuration(
    startedAtElapsedMs: Long?,
    elapsedRealtimeMs: Long,
): String {
    if (startedAtElapsedMs == null) {
        return "Not recording"
    }
    return formatUptime((elapsedRealtimeMs - startedAtElapsedMs).coerceAtLeast(0))
}

private fun formatShortElapsed(durationMs: Long): String =
    when {
        durationMs < 1_000 -> "${durationMs} ms ago"
        durationMs < 60_000 -> String.format("%.1f s ago", durationMs / 1_000f)
        else -> formatUptime(durationMs)
    }

private fun formatUptime(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes == 0L) {
        "${seconds}s"
    } else {
        "${minutes}m ${seconds}s"
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
                lastSampleReceivedAtElapsedMs = SystemClock.elapsedRealtime() - 420,
                lastStatusReceivedAtElapsedMs = SystemClock.elapsedRealtime() - 950,
            ),
            permissionsGranted = true,
            onGrantPermissions = {},
            onReconnect = {},
            onStartRecording = {},
            onStopRecording = {},
            onShareSession = {},
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
            onStartRecording = {},
            onStopRecording = {},
            onShareSession = {},
        )
    }
}

private fun formatTimestamp(timestampEpochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampEpochMs))

private fun formatFileSize(sizeBytes: Long): String =
    when {
        sizeBytes < 1024 -> "$sizeBytes B"
        sizeBytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024f)
        else -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024f * 1024f))
    }
