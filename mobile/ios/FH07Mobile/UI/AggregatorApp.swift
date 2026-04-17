import SwiftUI
import CoreBluetooth

struct AggregatorApp: View {
    @StateObject private var viewModel = AggregatorViewModel()
    @StateObject private var permissions = BluetoothPermissionsMonitor()
    @StateObject private var shareState = SessionShareState()

    var body: some View {
        AggregatorShell(
            uiState: viewModel.uiState,
            permissionsGranted: permissions.permissionsGranted,
            onGrantPermissions: { permissions.requestPermissions() },
            onReconnect: { viewModel.reconnect() },
            onCalibrateSitting: { viewModel.calibrateSittingPosture() },
            onSetUpperBackSensor: { viewModel.setUpperBackSensor($0) },
            onSetLowerBackSensor: { viewModel.setLowerBackSensor($0) },
            onStartRecording: { viewModel.startRecording() },
            onStopRecording: { viewModel.stopRecording() },
            onShareSession: { shareState.pendingSession = $0 }
        )
        .sheet(item: $shareState.pendingSession) { session in
            SessionShareSheet(session: session)
        }
        .onAppear {
            permissions.refresh()
            viewModel.onBlePermissionsChanged(granted: permissions.permissionsGranted)
            if !permissions.permissionsGranted && !permissions.hasRequested {
                permissions.requestPermissions()
            }
        }
        .onChange(of: permissions.permissionsGranted) { newValue in
            viewModel.onBlePermissionsChanged(granted: newValue)
        }
    }
}

// MARK: - Bluetooth permissions

final class BluetoothPermissionsMonitor: NSObject, ObservableObject, CBCentralManagerDelegate {
    @Published var permissionsGranted: Bool = false
    @Published var hasRequested: Bool = false

    private var manager: CBCentralManager?

    override init() {
        super.init()
        refresh()
    }

    func refresh() {
        permissionsGranted = evaluateAuthorization()
    }

    func requestPermissions() {
        hasRequested = true
        // Instantiating a CBCentralManager triggers the system Bluetooth permission prompt.
        if manager == nil {
            manager = CBCentralManager(delegate: self, queue: nil)
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        permissionsGranted = evaluateAuthorization()
    }

    private func evaluateAuthorization() -> Bool {
        switch CBManager.authorization {
        case .allowedAlways:
            return true
        case .notDetermined, .restricted, .denied:
            return false
        @unknown default:
            return false
        }
    }
}
