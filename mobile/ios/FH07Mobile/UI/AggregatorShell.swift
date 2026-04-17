import SwiftUI

private enum AppPage: String, CaseIterable, Identifiable {
    case diagnostics
    case sitting
    case cycling
    case running
    case export
    case modeling

    var id: String { rawValue }

    var label: String {
        switch self {
        case .diagnostics: return "Diagnostics"
        case .sitting: return "Sitting"
        case .cycling: return "Cycling"
        case .running: return "Running"
        case .export: return "Export"
        case .modeling: return "Modeling"
        }
    }
}

struct AggregatorShell: View {
    let uiState: AggregatorUiState
    let permissionsGranted: Bool
    let onGrantPermissions: () -> Void
    let onReconnect: () -> Void
    let onCalibrateSitting: () -> Void
    let onSetUpperBackSensor: (Int?) -> Void
    let onSetLowerBackSensor: (Int?) -> Void
    let onStartRecording: () -> Void
    let onStopRecording: () -> Void
    let onShareSession: (SavedSessionSummary) -> Void

    @SceneStorage("AggregatorShell.selectedPage") private var selectedPageRaw: String = AppPage.diagnostics.rawValue

    var body: some View {
        let page = AppPage(rawValue: selectedPageRaw) ?? .diagnostics
        NavigationView {
            VStack(spacing: 0) {
                PageSelector(
                    selectedPage: page,
                    onSelectPage: { selectedPageRaw = $0.rawValue }
                )
                pageView(for: page)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .navigationTitle("FH07 Mobile")
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func pageView(for page: AppPage) -> some View {
        switch page {
        case .diagnostics:
            DiagnosticsPage(
                uiState: uiState,
                permissionsGranted: permissionsGranted,
                onGrantPermissions: onGrantPermissions,
                onReconnect: onReconnect
            )
        case .sitting:
            SittingPage(
                uiState: uiState,
                permissionsGranted: permissionsGranted,
                onGrantPermissions: onGrantPermissions,
                onReconnect: onReconnect,
                onCalibrateSitting: onCalibrateSitting,
                onSetUpperBackSensor: onSetUpperBackSensor,
                onSetLowerBackSensor: onSetLowerBackSensor
            )
        case .cycling:
            CyclingPage(
                uiState: uiState,
                permissionsGranted: permissionsGranted,
                onGrantPermissions: onGrantPermissions,
                onReconnect: onReconnect
            )
        case .running:
            RunningPage(
                uiState: uiState,
                permissionsGranted: permissionsGranted,
                onGrantPermissions: onGrantPermissions,
                onReconnect: onReconnect
            )
        case .export:
            ExportPage(
                uiState: uiState,
                permissionsGranted: permissionsGranted,
                onGrantPermissions: onGrantPermissions,
                onReconnect: onReconnect,
                onStartRecording: onStartRecording,
                onStopRecording: onStopRecording,
                onShareSession: onShareSession
            )
        case .modeling:
            ModelingPage(
                uiState: uiState,
                permissionsGranted: permissionsGranted,
                onGrantPermissions: onGrantPermissions,
                onReconnect: onReconnect
            )
        }
    }
}

private struct PageSelector: View {
    let selectedPage: AppPage
    let onSelectPage: (AppPage) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Pages")
                .font(.footnote.weight(.semibold))
                .foregroundColor(AppTheme.onSurfaceVariant)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(AppPage.allCases) { page in
                        FilterChipView(
                            label: page.label,
                            isSelected: page == selectedPage,
                            onTap: { onSelectPage(page) }
                        )
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
