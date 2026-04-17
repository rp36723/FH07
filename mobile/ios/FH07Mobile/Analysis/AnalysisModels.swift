import Foundation

enum ActivityMode: String, Equatable {
    case sitting = "SITTING"
}

enum SensorPlacement: String, Equatable {
    case head = "HEAD"
    case chest = "CHEST"
    case upperBack = "UPPER_BACK"
    case lowerBack = "LOWER_BACK"
    case leftHip = "LEFT_HIP"
    case rightHip = "RIGHT_HIP"
    case leftThigh = "LEFT_THIGH"
    case rightThigh = "RIGHT_THIGH"
    case unknown = "UNKNOWN"
}

struct SensorAssignment: Equatable {
    let sensorId: Int
    let placement: SensorPlacement
    let required: Bool

    init(sensorId: Int, placement: SensorPlacement, required: Bool = true) {
        self.sensorId = sensorId
        self.placement = placement
        self.required = required
    }
}

enum WindowSpec: Equatable {
    case fixedDuration(durationMs: Int64)
    case durationRange(minDurationMs: Int64, maxDurationMs: Int64)

    var maxDurationMs: Int64 {
        switch self {
        case .fixedDuration(let durationMs): return durationMs
        case .durationRange(_, let maxDurationMs): return maxDurationMs
        }
    }

    var minDurationMs: Int64 {
        switch self {
        case .fixedDuration(let durationMs): return durationMs
        case .durationRange(let minDurationMs, _): return minDurationMs
        }
    }
}

struct AnalysisConfig: Equatable {
    let activityMode: ActivityMode
    var expectedSensors: [SensorAssignment]
    let windowSpec: WindowSpec
    let historyLookbackMs: Int64
    let allowPartialAnalysis: Bool
    let warningScoreThreshold: Float
    let poorScoreThreshold: Float

    init(
        activityMode: ActivityMode,
        expectedSensors: [SensorAssignment],
        windowSpec: WindowSpec,
        historyLookbackMs: Int64,
        allowPartialAnalysis: Bool,
        warningScoreThreshold: Float,
        poorScoreThreshold: Float
    ) {
        precondition(historyLookbackMs >= 0, "historyLookbackMs must be non-negative.")
        precondition(warningScoreThreshold >= 0 && warningScoreThreshold <= 100, "warningScoreThreshold must be between 0 and 100.")
        precondition(poorScoreThreshold >= 0 && poorScoreThreshold <= 100, "poorScoreThreshold must be between 0 and 100.")
        self.activityMode = activityMode
        self.expectedSensors = expectedSensors
        self.windowSpec = windowSpec
        self.historyLookbackMs = historyLookbackMs
        self.allowPartialAnalysis = allowPartialAnalysis
        self.warningScoreThreshold = warningScoreThreshold
        self.poorScoreThreshold = poorScoreThreshold
    }

    static func sittingDefault() -> AnalysisConfig {
        AnalysisConfig(
            activityMode: .sitting,
            expectedSensors: [],
            windowSpec: .durationRange(minDurationMs: 1_000, maxDurationMs: 5_000),
            historyLookbackMs: 0,
            allowPartialAnalysis: true,
            warningScoreThreshold: 70,
            poorScoreThreshold: 40
        )
    }
}

struct AnalysisInputWindow {
    let windowStartEpochMs: Int64
    let windowEndEpochMs: Int64
    let lookbackMs: Int64
    let samplesBySensor: [Int: [ImuSample]]
    let networkStatus: NetworkStatus?
    let sensorAssignments: [SensorAssignment]
    let expectedSensors: Set<Int>
    let availableSensors: Set<Int>
    let missingSensors: Set<Int>
    let sittingCalibration: SittingCalibration?
}

struct AnalysisWindowSummary: Equatable {
    let windowStartEpochMs: Int64
    let windowEndEpochMs: Int64
    let lookbackMs: Int64
    let sampleCountsBySensor: [Int: Int]
    let availableSensors: Set<Int>
    let missingSensors: Set<Int>
}

enum PostureState: String, Equatable {
    case good = "GOOD"
    case warning = "WARNING"
    case poor = "POOR"
    case incomplete = "INCOMPLETE"
}

enum PostureAlertCode: String, Equatable {
    case sensorMissing = "SENSOR_MISSING"
    case lowConfidence = "LOW_CONFIDENCE"
    case insufficientWindow = "INSUFFICIENT_WINDOW"
    case calibrationRequired = "CALIBRATION_REQUIRED"
    case analysisSkipped = "ANALYSIS_SKIPPED"
}

struct PostureAlert: Equatable, Hashable {
    let code: PostureAlertCode
    let message: String
}

struct SittingCalibration: Equatable {
    let capturedAtEpochMs: Int64
    let upperBackSensorId: Int
    let lowerBackSensorId: Int
    let upperBackPitchDeg: Float
    let lowerBackPitchDeg: Float
    let bendAngleDeg: Float
}

struct SittingPostureDetails: Equatable {
    let upperBackSensorId: Int?
    let lowerBackSensorId: Int?
    let upperBackPitchDeg: Float?
    let lowerBackPitchDeg: Float?
    let bendAngleDeg: Float?
    let bendDeltaFromBaselineDeg: Float?
    let baselineBendAngleDeg: Float?
}

struct PostureAnalysisResult: Equatable {
    let timestampEpochMs: Int64
    let score: Float
    let postureState: PostureState
    let confidence: Float
    let contributingSensors: Set<Int>
    let missingSensors: Set<Int>
    let lookbackMs: Int64
    var alerts: [PostureAlert]
    let sittingDetails: SittingPostureDetails?

    init(
        timestampEpochMs: Int64,
        score: Float,
        postureState: PostureState,
        confidence: Float,
        contributingSensors: Set<Int>,
        missingSensors: Set<Int>,
        lookbackMs: Int64,
        alerts: [PostureAlert],
        sittingDetails: SittingPostureDetails? = nil
    ) {
        self.timestampEpochMs = timestampEpochMs
        self.score = score
        self.postureState = postureState
        self.confidence = confidence
        self.contributingSensors = contributingSensors
        self.missingSensors = missingSensors
        self.lookbackMs = lookbackMs
        self.alerts = alerts
        self.sittingDetails = sittingDetails
    }
}
