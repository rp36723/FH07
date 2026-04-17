import SwiftUI

struct RunningPage: View {
    let uiState: AggregatorUiState
    let permissionsGranted: Bool
    let onGrantPermissions: () -> Void
    let onReconnect: () -> Void

    var body: some View {
        ActivityPlaceholderPage(
            pageTitle: "Running",
            activitySummary: "Running analysis is not implemented yet, but this page will host its own sensor setup, calibration, and scoring workflow.",
            setupLines: [
                "Define whether running uses back-only sensors or adds hip / thigh nodes.",
                "Decide whether impact, symmetry, or trunk lean belong in the first score.",
                "Keep transport and diagnostics separate from the future running model.",
            ],
            calibrationLines: [
                "TBD: standing neutral posture or a short warm-up sequence.",
                "TBD: determine if motion-state detection is required before calibration.",
            ],
            uiState: uiState,
            permissionsGranted: permissionsGranted,
            onGrantPermissions: onGrantPermissions,
            onReconnect: onReconnect
        )
    }
}
