import SwiftUI

// MARK: - Elapsed realtime clock

final class ElapsedClock: ObservableObject {
    @Published var elapsedRealtimeMs: Int64 = Int64(ProcessInfo.processInfo.systemUptime * 1000)
    private var timer: Timer?

    init() {
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.elapsedRealtimeMs = Int64(ProcessInfo.processInfo.systemUptime * 1000)
        }
    }

    deinit { timer?.invalidate() }
}

// MARK: - Shared page scaffold

struct PageContent<Content: View>: View {
    let title: String
    let uiState: AggregatorUiState
    let permissionsGranted: Bool
    let onGrantPermissions: () -> Void
    let onReconnect: () -> Void
    @ViewBuilder let content: (Int64) -> Content

    @StateObject private var clock = ElapsedClock()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(title)
                    .font(.largeTitle.weight(.semibold))
                ConnectionSummaryCard(
                    uiState: uiState,
                    permissionsGranted: permissionsGranted,
                    onGrantPermissions: onGrantPermissions,
                    onReconnect: onReconnect,
                    elapsedRealtimeMs: clock.elapsedRealtimeMs
                )
                content(clock.elapsedRealtimeMs)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Cards

struct DetailCard<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.title3.weight(.semibold))
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppTheme.surfaceVariant.opacity(0.6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

struct ConnectionSummaryCard: View {
    let uiState: AggregatorUiState
    let permissionsGranted: Bool
    let onGrantPermissions: () -> Void
    let onReconnect: () -> Void
    let elapsedRealtimeMs: Int64

    var body: some View {
        DetailCard(title: "Connection") {
            Text(uiState.connectionState)
                .font(.body)
            StatRow(label: "Phase", value: uiState.connectionPhase.label)
            StatRow(label: "Status update", value: formatAge(uiState.lastStatusReceivedAtElapsedMs, elapsedRealtimeMs))
            StatRow(label: "Sample update", value: formatAge(uiState.lastSampleReceivedAtElapsedMs, elapsedRealtimeMs))

            HStack(spacing: 12) {
                if !permissionsGranted {
                    Button("Grant permissions", action: onGrantPermissions)
                        .buttonStyle(.borderedProminent)
                }
                Button("Rescan", action: onReconnect)
                    .buttonStyle(.borderedProminent)
                    .disabled(!permissionsGranted)
            }

            if !permissionsGranted {
                Text("Bluetooth scan and connect permissions are required before the app can find BLE-Aggregator.")
                    .foregroundColor(AppTheme.error)
            }
            if let message = uiState.errorMessage {
                Text(message).foregroundColor(AppTheme.error)
            }
        }
    }
}

struct OverviewCard: View {
    let uiState: AggregatorUiState
    let elapsedRealtimeMs: Int64

    var body: some View {
        DetailCard(title: "Overview") {
            StatRow(label: "Device", value: uiState.deviceName)
            StatRow(label: "Phase", value: uiState.connectionPhase.label)
            StatRow(label: "Status update", value: formatAge(uiState.lastStatusReceivedAtElapsedMs, elapsedRealtimeMs))
            StatRow(label: "Sample update", value: formatAge(uiState.lastSampleReceivedAtElapsedMs, elapsedRealtimeMs))

            if let status = uiState.networkStatus {
                Divider().padding(.vertical, 8)
                StatRow(label: "Aggregator uptime", value: formatUptime(status.uptimeMs))
                StatRow(label: "Active sensors", value: String(status.activeSensorCount))
            }

            if let sample = uiState.latestSample {
                Divider().padding(.vertical, 8)
                StatRow(label: "Last sample sensor", value: String(sample.sensorId))
                StatRow(label: "Last sample seq", value: String(sample.seq))
                StatRow(label: "Sample timestamp", value: "\(sample.timestampMs) ms")
            }
        }
    }
}

struct AnalysisCard: View {
    let analysis: AnalysisUiState
    let elapsedRealtimeMs: Int64
    let onCalibrateSitting: () -> Void

    var body: some View {
        DetailCard(title: "Calibration And Score") {
            StatRow(label: "Activity", value: formatActivityMode(analysis.activityMode))
            StatRow(label: "Window", value: formatWindowSpec(analysis.config.windowSpec))
            StatRow(label: "Lookback", value: formatLookback(analysis.config.historyLookbackMs))
            StatRow(label: "Updated", value: formatAge(analysis.lastUpdatedAtElapsedMs, elapsedRealtimeMs))
            Text(analysis.statusMessage).font(.body)

            if let message = analysis.calibrationMessage {
                Text(message).font(.caption).foregroundColor(AppTheme.onSurfaceVariant)
            }

            let upperAssignment = analysis.sensorAssignments.first(where: { $0.placement == .upperBack })
            let lowerAssignment = analysis.sensorAssignments.first(where: { $0.placement == .lowerBack })

            Divider().padding(.vertical, 8)
            StatRow(label: "Upper back sensor", value: upperAssignment.map { String($0.sensorId) } ?? "Waiting")
            StatRow(label: "Lower back sensor", value: lowerAssignment.map { String($0.sensorId) } ?? "Waiting")

            if let calibration = analysis.sittingCalibration {
                Divider().padding(.vertical, 8)
                StatRow(label: "Calibration", value: formatTimestamp(calibration.capturedAtEpochMs))
                StatRow(label: "Baseline bend", value: formatDegrees(calibration.bendAngleDeg))
            }

            Button(analysis.sittingCalibration == nil ? "Calibrate sitting" : "Recalibrate", action: onCalibrateSitting)
                .buttonStyle(.borderedProminent)
                .disabled((analysis.windowSummary?.availableSensors.count ?? 0) < 2)

            if !analysis.expectedSensors.isEmpty {
                Divider().padding(.vertical, 8)
                StatRow(label: "Expected sensors", value: formatSensorSet(analysis.expectedSensors))
                StatRow(label: "Available sensors", value: formatSensorSet(analysis.windowSummary?.availableSensors ?? []))
                StatRow(label: "Missing sensors", value: formatSensorSet(analysis.windowSummary?.missingSensors ?? []))
                if analysis.expectedSensorsInferred {
                    Text("Sensor roles are currently inferred from observed sensor IDs.")
                        .font(.caption).foregroundColor(AppTheme.onSurfaceVariant)
                }
            }

            if let summary = analysis.windowSummary, !summary.sampleCountsBySensor.isEmpty {
                Divider().padding(.vertical, 8)
                Text("Window sample counts").font(.subheadline.weight(.semibold))
                ForEach(summary.sampleCountsBySensor.sorted(by: { $0.key < $1.key }), id: \.key) { (sensorId, count) in
                    StatRow(label: "Sensor \(sensorId)", value: "\(count) samples")
                }
            }

            if let result = analysis.latestResult {
                Divider().padding(.vertical, 8)
                StatRow(label: "State", value: formatPostureState(result.postureState))
                StatRow(label: "Score", value: String(format: "%.1f / 100", result.score))
                StatRow(label: "Confidence", value: String(format: "%.2f", result.confidence))
                if let details = result.sittingDetails {
                    Divider().padding(.vertical, 8)
                    StatRow(label: "Upper pitch", value: formatDegrees(details.upperBackPitchDeg))
                    StatRow(label: "Lower pitch", value: formatDegrees(details.lowerBackPitchDeg))
                    StatRow(label: "Current bend", value: formatDegrees(details.bendAngleDeg))
                    StatRow(label: "Bend delta", value: formatDegrees(details.bendDeltaFromBaselineDeg))
                }
                if !result.alerts.isEmpty {
                    Divider().padding(.vertical, 8)
                    Text("Alerts").font(.subheadline.weight(.semibold))
                    ForEach(result.alerts, id: \.self) { alert in
                        Text("- \(alert.message)").font(.caption)
                    }
                }
            }
        }
    }
}

struct RecordingCard: View {
    let uiState: AggregatorUiState
    let elapsedRealtimeMs: Int64
    let onStartRecording: () -> Void
    let onStopRecording: () -> Void
    let onShareSession: (SavedSessionSummary) -> Void

    var body: some View {
        DetailCard(title: "Session Recording") {
            StatRow(label: "State", value: uiState.isRecording ? "Recording" : "Idle")
            StatRow(label: "Session file", value: uiState.recordingSessionName ?? "None")
            if uiState.isRecording {
                StatRow(label: "Duration", value: formatRecordingDuration(uiState.recordingStartedAtElapsedMs, elapsedRealtimeMs))
            }
            StatRow(label: "Samples saved", value: String(uiState.recordedSampleCount))
            StatRow(label: "Status snapshots", value: String(uiState.recordedStatusCount))
            if let path = uiState.recordingSessionPath {
                Text("Stored in: \(path)").font(.caption)
            }
            if let message = uiState.recordingErrorMessage {
                Text(message).foregroundColor(AppTheme.error)
            }

            HStack(spacing: 12) {
                Button("Start recording", action: onStartRecording)
                    .buttonStyle(.borderedProminent)
                    .disabled(uiState.isRecording)
                Button("Stop recording", action: onStopRecording)
                    .buttonStyle(.borderedProminent)
                    .disabled(!uiState.isRecording)
            }

            if !uiState.savedSessions.isEmpty {
                Divider().padding(.vertical, 8)
                Text("Recent recordings").font(.subheadline.weight(.semibold))
                ForEach(uiState.savedSessions.prefix(5).map { $0 }) { session in
                    SavedSessionRow(session: session, onShareSession: onShareSession)
                }
            }
        }
    }
}

struct SavedSessionRow: View {
    let session: SavedSessionSummary
    let onShareSession: (SavedSessionSummary) -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(session.fileName).font(.body.weight(.medium))
                Text("\(formatFileSize(session.sizeBytes)) - \(formatTimestamp(session.modifiedAtEpochMs))")
                    .font(.caption)
                    .foregroundColor(AppTheme.onSurfaceVariant)
            }
            Spacer()
            Button("Share") { onShareSession(session) }
                .buttonStyle(.borderedProminent)
        }
    }
}

struct SensorTableCard: View {
    let networkStatus: NetworkStatus?
    let rawHex: String?

    var body: some View {
        DetailCard(title: "Sensors") {
            if networkStatus == nil {
                Text("Waiting for network_status...")
            } else if networkStatus!.sensors.isEmpty {
                Text("No active sensors reported.")
            } else {
                TableHeader()
                Divider().padding(.vertical, 6)
                ForEach(networkStatus!.sensors, id: \.sensorId) { sensor in
                    SensorRow(sensor: sensor)
                    Divider().padding(.vertical, 6)
                }
            }
            if let hex = rawHex {
                Text("Raw: \(hex)").font(.caption)
            }
        }
    }
}

private struct TableHeader: View {
    var body: some View {
        HStack(spacing: 12) {
            Text("ID").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading)
            Text("Seq").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading)
            Text("Sensor age").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading)
            Text("State").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct SensorRow: View {
    let sensor: ActiveSensorStatus

    var body: some View {
        let state: String = {
            if sensor.ageMs <= 250 { return "Fresh" }
            if sensor.ageMs <= 1_000 { return "Aging" }
            return "Stale?"
        }()

        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 12) {
                Text(String(sensor.sensorId)).frame(maxWidth: .infinity, alignment: .leading)
                Text(String(sensor.seq)).frame(maxWidth: .infinity, alignment: .leading)
                Text("\(sensor.ageMs) ms").frame(maxWidth: .infinity, alignment: .leading)
                Text(state).frame(maxWidth: .infinity, alignment: .leading)
            }
            Text("Observed \(formatShortElapsed(Int64(sensor.ageMs))) old").font(.caption)
        }
    }
}

struct LatestSampleCard: View {
    let sample: ImuSample?
    let receivedAtElapsedMs: Int64?
    let rawHex: String?
    let elapsedRealtimeMs: Int64

    var body: some View {
        DetailCard(title: "Latest Sample") {
            if let sample = sample {
                StatRow(label: "Sensor", value: String(sample.sensorId))
                StatRow(label: "Sequence", value: String(sample.seq))
                StatRow(label: "Timestamp", value: "\(sample.timestampMs) ms")
                StatRow(label: "Updated", value: formatAge(receivedAtElapsedMs, elapsedRealtimeMs))

                Divider().padding(.vertical, 8)
                AxisGroup(title: "Accel", x: sample.ax, y: sample.ay, z: sample.az)
                Divider().padding(.vertical, 8)
                AxisGroup(title: "Gyro", x: sample.gx, y: sample.gy, z: sample.gz)

                if let hex = rawHex {
                    Divider().padding(.vertical, 8)
                    Text("Raw: \(hex)").font(.caption)
                }
            } else {
                Text("Waiting for sample_stream notifications...")
            }
        }
    }
}

struct DiagnosticsCard: View {
    let uiState: AggregatorUiState

    var body: some View {
        DetailCard(title: "Diagnostics") {
            Text("Phase: \(uiState.connectionPhase.label)")
            Text("Reconnect attempts: \(uiState.reconnectCount)")
            Text("Connected: \(uiState.isConnected ? "yes" : "no")")
            if let reason = uiState.lastFailureReason {
                Text("Last failure: \(reason)")
            }
            if !uiState.recentEvents.isEmpty {
                Divider().padding(.vertical, 8)
                ForEach(Array(uiState.recentEvents.suffix(8).enumerated()), id: \.offset) { _, event in
                    Text(event).font(.caption)
                }
            }
        }
    }
}

struct ActivitySetupCard: View {
    let title: String
    let lines: [String]

    var body: some View {
        DetailCard(title: title) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line).font(.body)
            }
        }
    }
}

struct StatRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(label)
                .font(.body)
                .foregroundColor(AppTheme.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(value)
                .font(.body.weight(.medium))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct AxisGroup: View {
    let title: String
    let x: Int
    let y: Int
    let z: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.subheadline.weight(.semibold))
            HStack(spacing: 12) {
                AxisValue(axis: "X", value: x)
                AxisValue(axis: "Y", value: y)
                AxisValue(axis: "Z", value: z)
            }
        }
    }
}

struct AxisValue: View {
    let axis: String
    let value: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(axis).font(.caption).foregroundColor(AppTheme.onSurfaceVariant)
            Text(String(value)).font(.title3.weight(.medium))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Formatters

func formatAge(_ receivedAtElapsedMs: Int64?, _ elapsedRealtimeMs: Int64) -> String {
    guard let received = receivedAtElapsedMs else { return "Waiting" }
    return formatShortElapsed(max(elapsedRealtimeMs - received, 0))
}

func formatRecordingDuration(_ startedAtElapsedMs: Int64?, _ elapsedRealtimeMs: Int64) -> String {
    guard let started = startedAtElapsedMs else { return "Not recording" }
    return formatUptime(max(elapsedRealtimeMs - started, 0))
}

func formatActivityMode(_ activityMode: ActivityMode) -> String {
    let raw = activityMode.rawValue.lowercased()
    return raw.prefix(1).uppercased() + raw.dropFirst()
}

func formatPostureState(_ postureState: PostureState) -> String {
    let raw = postureState.rawValue.lowercased()
    return raw.prefix(1).uppercased() + raw.dropFirst()
}

func formatWindowSpec(_ windowSpec: WindowSpec) -> String {
    switch windowSpec {
    case .fixedDuration(let durationMs):
        return formatUptime(durationMs)
    case .durationRange(let minDurationMs, let maxDurationMs):
        return "\(formatUptime(minDurationMs)) - \(formatUptime(maxDurationMs))"
    }
}

func formatLookback(_ lookbackMs: Int64) -> String {
    lookbackMs == 0 ? "Live" : formatUptime(lookbackMs)
}

func formatSensorSet(_ sensorIds: Set<Int>) -> String {
    if sensorIds.isEmpty { return "None" }
    return sensorIds.sorted().map(String.init).joined(separator: ", ")
}

func formatDegrees(_ value: Float?) -> String {
    guard let value = value else { return "Waiting" }
    return String(format: "%.1f deg", value)
}

func formatTimestamp(_ timestampEpochMs: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(timestampEpochMs) / 1000)
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
    return formatter.string(from: date)
}

func formatFileSize(_ sizeBytes: Int64) -> String {
    if sizeBytes < 1024 {
        return "\(sizeBytes) B"
    } else if sizeBytes < 1024 * 1024 {
        return String(format: "%.1f KB", Float(sizeBytes) / 1024.0)
    } else {
        return String(format: "%.1f MB", Float(sizeBytes) / (1024.0 * 1024.0))
    }
}

func formatShortElapsed(_ durationMs: Int64) -> String {
    if durationMs < 1_000 {
        return "\(durationMs) ms ago"
    } else if durationMs < 60_000 {
        return String(format: "%.1f s ago", Float(durationMs) / 1000.0)
    } else {
        return formatUptime(durationMs)
    }
}

func formatUptime(_ durationMs: Int64) -> String {
    let totalSeconds = durationMs / 1_000
    let minutes = totalSeconds / 60
    let seconds = totalSeconds % 60
    if minutes == 0 {
        return "\(seconds)s"
    } else {
        return "\(minutes)m \(seconds)s"
    }
}
