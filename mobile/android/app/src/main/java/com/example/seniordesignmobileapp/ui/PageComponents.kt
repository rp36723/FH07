package com.example.seniordesignmobileapp.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.example.seniordesignmobileapp.analysis.ActivityMode
import com.example.seniordesignmobileapp.analysis.PostureState
import com.example.seniordesignmobileapp.analysis.SensorPlacement
import com.example.seniordesignmobileapp.analysis.WindowSpec
import com.example.seniordesignmobileapp.model.ActiveSensorStatus
import com.example.seniordesignmobileapp.model.AggregatorUiState
import com.example.seniordesignmobileapp.model.AnalysisUiState
import com.example.seniordesignmobileapp.model.ImuSample
import com.example.seniordesignmobileapp.model.NetworkStatus
import com.example.seniordesignmobileapp.model.SavedSessionSummary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PageContent(
    title: String,
    uiState: AggregatorUiState,
    permissionsGranted: Boolean,
    onGrantPermissions: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Long) -> Unit,
) {
    val elapsedRealtimeMs by rememberElapsedRealtime()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
        )
        ConnectionSummaryCard(
            uiState = uiState,
            permissionsGranted = permissionsGranted,
            onGrantPermissions = onGrantPermissions,
            onReconnect = onReconnect,
            elapsedRealtimeMs = elapsedRealtimeMs,
        )
        content(elapsedRealtimeMs)
    }
}

@Composable
fun ConnectionSummaryCard(
    uiState: AggregatorUiState,
    permissionsGranted: Boolean,
    onGrantPermissions: () -> Unit,
    onReconnect: () -> Unit,
    elapsedRealtimeMs: Long,
) {
    DetailCard(title = "Connection") {
        Text(
            text = uiState.connectionState,
            style = MaterialTheme.typography.bodyLarge,
        )
        StatRow("Phase", uiState.connectionPhase.label)
        StatRow("Status update", formatAge(uiState.lastStatusReceivedAtElapsedMs, elapsedRealtimeMs))
        StatRow("Sample update", formatAge(uiState.lastSampleReceivedAtElapsedMs, elapsedRealtimeMs))

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
                text = "Bluetooth scan and connect permissions are required before the app can find BLE-Aggregator.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        uiState.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
fun OverviewCard(
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
fun AnalysisCard(
    analysis: AnalysisUiState,
    elapsedRealtimeMs: Long,
    onCalibrateSitting: () -> Unit,
) {
    DetailCard(title = "Calibration And Score") {
        StatRow("Activity", formatActivityMode(analysis.activityMode))
        StatRow("Window", formatWindowSpec(analysis.config.windowSpec))
        StatRow("Lookback", formatLookback(analysis.config.historyLookbackMs))
        StatRow("Updated", formatAge(analysis.lastUpdatedAtElapsedMs, elapsedRealtimeMs))
        Text(analysis.statusMessage, style = MaterialTheme.typography.bodyMedium)

        analysis.calibrationMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val upperAssignment = analysis.sensorAssignments
            .firstOrNull { it.placement == SensorPlacement.UPPER_BACK }
        val lowerAssignment = analysis.sensorAssignments
            .firstOrNull { it.placement == SensorPlacement.LOWER_BACK }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        StatRow("Upper back sensor", upperAssignment?.sensorId?.toString() ?: "Waiting")
        StatRow("Lower back sensor", lowerAssignment?.sensorId?.toString() ?: "Waiting")

        analysis.sittingCalibration?.let { calibration ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            StatRow("Calibration", formatTimestamp(calibration.capturedAtEpochMs))
            StatRow("Baseline bend", formatDegrees(calibration.bendAngleDeg))
        }

        Button(
            onClick = onCalibrateSitting,
            enabled = analysis.windowSummary?.availableSensors?.size ?: 0 >= 2,
        ) {
            Text(if (analysis.sittingCalibration == null) "Calibrate sitting" else "Recalibrate")
        }

        if (analysis.expectedSensors.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            StatRow("Expected sensors", formatSensorSet(analysis.expectedSensors))
            StatRow(
                "Available sensors",
                formatSensorSet(analysis.windowSummary?.availableSensors.orEmpty()),
            )
            StatRow(
                "Missing sensors",
                formatSensorSet(analysis.windowSummary?.missingSensors.orEmpty()),
            )
            if (analysis.expectedSensorsInferred) {
                Text(
                    text = "Sensor roles are currently inferred from observed sensor IDs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        analysis.windowSummary?.let { summary ->
            if (summary.sampleCountsBySensor.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "Window sample counts",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                summary.sampleCountsBySensor.toSortedMap().forEach { (sensorId, count) ->
                    StatRow("Sensor $sensorId", "$count samples")
                }
            }
        }

        analysis.latestResult?.let { result ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            StatRow("State", formatPostureState(result.postureState))
            StatRow("Score", String.format(Locale.US, "%.1f / 100", result.score))
            StatRow("Confidence", String.format(Locale.US, "%.2f", result.confidence))
            result.sittingDetails?.let { details ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                StatRow("Upper pitch", formatDegrees(details.upperBackPitchDeg))
                StatRow("Lower pitch", formatDegrees(details.lowerBackPitchDeg))
                StatRow("Current bend", formatDegrees(details.bendAngleDeg))
                StatRow("Bend delta", formatDegrees(details.bendDeltaFromBaselineDeg))
            }
            if (result.alerts.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "Alerts",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                result.alerts.forEach { alert ->
                    Text(
                        text = "- ${alert.message}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingCard(
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
fun SavedSessionRow(
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
                text = "${formatFileSize(session.sizeBytes)} - ${formatTimestamp(session.modifiedAtEpochMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = { onShareSession(session) }) {
            Text("Share")
        }
    }
}

@Composable
fun SensorTableCard(
    networkStatus: NetworkStatus?,
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
                SensorRow(sensor = sensor)
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            }
        }

        rawHex?.let { hex ->
            Text("Raw: $hex", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun LatestSampleCard(
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
fun DiagnosticsCard(
    uiState: AggregatorUiState,
) {
    DetailCard(title = "Diagnostics") {
        Text("Phase: ${uiState.connectionPhase.label}")
        Text("Reconnect attempts: ${uiState.reconnectCount}")
        Text("Connected: ${if (uiState.isConnected) "yes" else "no"}")
        uiState.lastFailureReason?.let { reason ->
            Text("Last failure: $reason")
        }

        if (uiState.recentEvents.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            uiState.recentEvents.takeLast(8).forEach { event ->
                Text(event, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ActivitySetupCard(
    title: String,
    lines: List<String>,
) {
    DetailCard(title = title) {
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun DetailCard(
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

@Composable
fun StatRow(
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
fun AxisGroup(
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
fun RowScope.AxisValue(
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
private fun rememberElapsedRealtime(): State<Long> =
    produceState(initialValue = SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }

fun formatAge(
    receivedAtElapsedMs: Long?,
    elapsedRealtimeMs: Long,
): String {
    if (receivedAtElapsedMs == null) {
        return "Waiting"
    }
    return formatShortElapsed((elapsedRealtimeMs - receivedAtElapsedMs).coerceAtLeast(0))
}

fun formatRecordingDuration(
    startedAtElapsedMs: Long?,
    elapsedRealtimeMs: Long,
): String {
    if (startedAtElapsedMs == null) {
        return "Not recording"
    }
    return formatUptime((elapsedRealtimeMs - startedAtElapsedMs).coerceAtLeast(0))
}

fun formatActivityMode(activityMode: ActivityMode): String =
    activityMode.name.lowercase()
        .replaceFirstChar { char -> char.titlecase(Locale.US) }

fun formatPostureState(postureState: PostureState): String =
    postureState.name.lowercase()
        .replaceFirstChar { char -> char.titlecase(Locale.US) }

fun formatWindowSpec(windowSpec: WindowSpec): String =
    when (windowSpec) {
        is WindowSpec.FixedDuration -> formatUptime(windowSpec.durationMs)
        is WindowSpec.DurationRange -> "${formatUptime(windowSpec.minDurationMs)} - ${formatUptime(windowSpec.maxDurationMs)}"
    }

fun formatLookback(lookbackMs: Long): String =
    if (lookbackMs == 0L) {
        "Live"
    } else {
        formatUptime(lookbackMs)
    }

fun formatSensorSet(sensorIds: Set<Int>): String =
    if (sensorIds.isEmpty()) {
        "None"
    } else {
        sensorIds.sorted().joinToString()
    }

fun formatDegrees(value: Float?): String =
    if (value == null) {
        "Waiting"
    } else {
        String.format(Locale.US, "%.1f deg", value)
    }

fun formatTimestamp(timestampEpochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampEpochMs))

fun formatFileSize(sizeBytes: Long): String =
    when {
        sizeBytes < 1024 -> "$sizeBytes B"
        sizeBytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024f)
        else -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024f * 1024f))
    }

private fun formatSensorAge(sensorAgeMs: Long): String =
    "${formatShortElapsed(sensorAgeMs)} old"

private fun formatShortElapsed(durationMs: Long): String =
    when {
        durationMs < 1_000 -> "${durationMs} ms ago"
        durationMs < 60_000 -> String.format(Locale.US, "%.1f s ago", durationMs / 1_000f)
        else -> formatUptime(durationMs)
    }

fun formatUptime(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes == 0L) {
        "${seconds}s"
    } else {
        "${minutes}m ${seconds}s"
    }
}
