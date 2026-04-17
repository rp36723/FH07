import SwiftUI

struct DiagnosticsPage: View {
    let uiState: AggregatorUiState
    let permissionsGranted: Bool
    let onGrantPermissions: () -> Void
    let onReconnect: () -> Void

    var body: some View {
        PageContent(
            title: "Diagnostics",
            uiState: uiState,
            permissionsGranted: permissionsGranted,
            onGrantPermissions: onGrantPermissions,
            onReconnect: onReconnect
        ) { elapsedRealtimeMs in
            OverviewCard(
                uiState: uiState,
                elapsedRealtimeMs: elapsedRealtimeMs
            )
            SensorTableCard(
                networkStatus: uiState.networkStatus,
                rawHex: uiState.lastStatusHex
            )
            LatestSampleCard(
                sample: uiState.latestSample,
                receivedAtElapsedMs: uiState.lastSampleReceivedAtElapsedMs,
                rawHex: uiState.lastSampleHex,
                elapsedRealtimeMs: elapsedRealtimeMs
            )
            DiagnosticsCard(uiState: uiState)
        }
    }
}
