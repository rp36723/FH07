import SwiftUI

struct CyclingPage: View {
    let uiState: AggregatorUiState
    let permissionsGranted: Bool
    let onGrantPermissions: () -> Void
    let onReconnect: () -> Void

    var body: some View {
        ActivityPlaceholderPage(
            pageTitle: "Cycling",
            activitySummary: "Cycling analysis is not implemented yet, but this page is reserved for sensor placement, calibration, and live activity-specific scoring.",
            setupLines: [
                "Define which nodes belong on the torso, hips, or legs for cycling.",
                "Decide whether cadence or asymmetry matters alongside posture.",
                "Replace placeholders here once the cycling analyzer exists.",
            ],
            calibrationLines: [
                "TBD: likely a seated-on-bike neutral position.",
                "TBD: determine whether separate calibrations are needed for road vs stationary setups.",
            ],
            uiState: uiState,
            permissionsGranted: permissionsGranted,
            onGrantPermissions: onGrantPermissions,
            onReconnect: onReconnect
        )
    }
}
