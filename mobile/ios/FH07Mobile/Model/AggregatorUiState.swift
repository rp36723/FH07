import Foundation

enum BleConnectionPhase: String, Equatable {
    case idle
    case waitingForPermissions
    case bluetoothUnavailable
    case bluetoothDisabled
    case scanning
    case connecting
    case requestingMtu
    case discoveringServices
    case subscribing
    case readingStatus
    case streaming
    case recovering
    case disconnected

    var label: String {
        switch self {
        case .idle: return "Idle"
        case .waitingForPermissions: return "Waiting for permissions"
        case .bluetoothUnavailable: return "Bluetooth unavailable"
        case .bluetoothDisabled: return "Bluetooth disabled"
        case .scanning: return "Scanning"
        case .connecting: return "Connecting"
        case .requestingMtu: return "Requesting MTU"
        case .discoveringServices: return "Discovering services"
        case .subscribing: return "Subscribing"
        case .readingStatus: return "Reading status"
        case .streaming: return "Streaming"
        case .recovering: return "Recovering"
        case .disconnected: return "Disconnected"
        }
    }
}

struct SavedSessionSummary: Equatable, Identifiable {
    let fileName: String
    let absolutePath: String
    let sizeBytes: Int64
    let modifiedAtEpochMs: Int64

    var id: String { absolutePath }
}

struct AggregatorUiState: Equatable {
    var connectionState: String = "Waiting for Bluetooth permissions"
    var connectionPhase: BleConnectionPhase = .waitingForPermissions
    var deviceName: String = "BLE-Aggregator"
    var isScanning: Bool = false
    var isConnected: Bool = false
    var latestSample: ImuSample? = nil
    var networkStatus: NetworkStatus? = nil
    var lastSampleHex: String? = nil
    var lastStatusHex: String? = nil
    var lastSampleReceivedAtElapsedMs: Int64? = nil
    var lastStatusReceivedAtElapsedMs: Int64? = nil
    var isRecording: Bool = false
    var recordingSessionName: String? = nil
    var recordingSessionPath: String? = nil
    var recordingStartedAtElapsedMs: Int64? = nil
    var recordedSampleCount: Int = 0
    var recordedStatusCount: Int = 0
    var savedSessions: [SavedSessionSummary] = []
    var recordingErrorMessage: String? = nil
    var analysis: AnalysisUiState = AnalysisUiState()
    var modeling: ModelingUiState = ModelingUiState()
    var errorMessage: String? = nil
    var lastFailureReason: String? = nil
    var reconnectCount: Int = 0
    var recentEvents: [String] = []
}
