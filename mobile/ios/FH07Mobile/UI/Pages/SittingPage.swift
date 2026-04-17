import SwiftUI

struct SittingPage: View {
    let uiState: AggregatorUiState
    let permissionsGranted: Bool
    let onGrantPermissions: () -> Void
    let onReconnect: () -> Void
    let onCalibrateSitting: () -> Void
    let onSetUpperBackSensor: (Int?) -> Void
    let onSetLowerBackSensor: (Int?) -> Void

    var body: some View {
        PageContent(
            title: "Sitting",
            uiState: uiState,
            permissionsGranted: permissionsGranted,
            onGrantPermissions: onGrantPermissions,
            onReconnect: onReconnect
        ) { elapsedRealtimeMs in
            ActivitySetupCard(
                title: "Sensor Setup",
                lines: [
                    "V1 sitting analysis uses two back-mounted nodes.",
                    "Choose which live sensor is upper back and which is lower back.",
                    "You can leave either role on Auto, but explicit selection is preferred with real hardware.",
                ]
            )
            SittingSensorSelectionCard(
                uiState: uiState,
                onSetUpperBackSensor: onSetUpperBackSensor,
                onSetLowerBackSensor: onSetLowerBackSensor
            )
            ActivitySetupCard(
                title: "Calibration Flow",
                lines: [
                    "1. Connect both back sensors and wait for live samples.",
                    "2. Sit upright in the reference posture.",
                    "3. Tap Calibrate sitting to capture the baseline bend angle.",
                    "4. Use the live score and bend delta as posture drifts from that baseline.",
                ]
            )
            AnalysisCard(
                analysis: uiState.analysis,
                elapsedRealtimeMs: elapsedRealtimeMs,
                onCalibrateSitting: onCalibrateSitting
            )
        }
    }
}

private struct SittingSensorSelectionCard: View {
    let uiState: AggregatorUiState
    let onSetUpperBackSensor: (Int?) -> Void
    let onSetLowerBackSensor: (Int?) -> Void

    var body: some View {
        let analysis = uiState.analysis
        DetailCard(title: "Choose Sensor Roles") {
            Text("Lower back")
            SensorRoleSelectorRow(
                sensorIds: analysis.availableSensorIds,
                selectedSensorId: analysis.manualLowerBackSensorId,
                onSelectSensor: onSetLowerBackSensor
            )
            Text("Upper back")
            SensorRoleSelectorRow(
                sensorIds: analysis.availableSensorIds,
                selectedSensorId: analysis.manualUpperBackSensorId,
                onSelectSensor: onSetUpperBackSensor
            )
            if analysis.availableSensorIds.isEmpty {
                Text("Waiting for live sensors before manual role selection is available.")
            }
        }
    }
}

private struct SensorRoleSelectorRow: View {
    let sensorIds: [Int]
    let selectedSensorId: Int?
    let onSelectSensor: (Int?) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                FilterChipView(
                    label: "Auto",
                    isSelected: selectedSensorId == nil,
                    onTap: { onSelectSensor(nil) }
                )
                ForEach(sensorIds, id: \.self) { sensorId in
                    FilterChipView(
                        label: "Sensor \(sensorId)",
                        isSelected: selectedSensorId == sensorId,
                        onTap: { onSelectSensor(sensorId) }
                    )
                }
            }
        }
    }
}

struct FilterChipView: View {
    let label: String
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            Text(label)
                .font(.subheadline)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    Capsule()
                        .fill(isSelected ? AppTheme.primary.opacity(0.2) : Color.clear)
                )
                .overlay(
                    Capsule()
                        .stroke(isSelected ? AppTheme.primary : AppTheme.outline, lineWidth: 1)
                )
                .foregroundColor(isSelected ? AppTheme.primary : Color.primary)
        }
        .buttonStyle(.plain)
    }
}
