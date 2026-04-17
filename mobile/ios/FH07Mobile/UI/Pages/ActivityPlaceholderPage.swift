import SwiftUI

struct ActivityPlaceholderPage: View {
    let pageTitle: String
    let activitySummary: String
    let setupLines: [String]
    let calibrationLines: [String]
    let uiState: AggregatorUiState
    let permissionsGranted: Bool
    let onGrantPermissions: () -> Void
    let onReconnect: () -> Void

    var body: some View {
        PageContent(
            title: pageTitle,
            uiState: uiState,
            permissionsGranted: permissionsGranted,
            onGrantPermissions: onGrantPermissions,
            onReconnect: onReconnect
        ) { _ in
            ActivitySetupCard(
                title: "Activity Summary",
                lines: [activitySummary]
            )
            ActivitySetupCard(
                title: "Sensor Setup",
                lines: setupLines
            )
            ActivitySetupCard(
                title: "Calibration",
                lines: calibrationLines
            )
            ActivitySetupCard(
                title: "Implementation Status",
                lines: [
                    "This activity page is intentionally scaffolded before its analysis pipeline exists.",
                    "Use Diagnostics and Export while defining the sensor layout, calibration flow, and score model for this activity.",
                ]
            )
        }
    }
}
