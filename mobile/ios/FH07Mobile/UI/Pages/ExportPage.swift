import SwiftUI

struct ExportPage: View {
    let uiState: AggregatorUiState
    let permissionsGranted: Bool
    let onGrantPermissions: () -> Void
    let onReconnect: () -> Void
    let onStartRecording: () -> Void
    let onStopRecording: () -> Void
    let onShareSession: (SavedSessionSummary) -> Void

    var body: some View {
        PageContent(
            title: "Data Export",
            uiState: uiState,
            permissionsGranted: permissionsGranted,
            onGrantPermissions: onGrantPermissions,
            onReconnect: onReconnect
        ) { elapsedRealtimeMs in
            ActivitySetupCard(
                title: "Export Workflow",
                lines: [
                    "Use this page to record sessions, review recent saved files, and share JSONL exports.",
                    "These exports are the current bridge from live BLE collection into offline analysis and future modeling work.",
                ]
            )
            RecordingCard(
                uiState: uiState,
                elapsedRealtimeMs: elapsedRealtimeMs,
                onStartRecording: onStartRecording,
                onStopRecording: onStopRecording,
                onShareSession: onShareSession
            )
        }
    }
}
