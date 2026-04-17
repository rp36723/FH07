import Foundation

struct AnalysisUiState: Equatable {
    var config: AnalysisConfig = AnalysisConfig.sittingDefault()
    var sensorAssignments: [SensorAssignment] = []
    var availableSensorIds: [Int] = []
    var manualUpperBackSensorId: Int? = nil
    var manualLowerBackSensorId: Int? = nil
    var expectedSensors: Set<Int> = []
    var expectedSensorsInferred: Bool = true
    var sittingCalibration: SittingCalibration? = nil
    var windowSummary: AnalysisWindowSummary? = nil
    var latestResult: PostureAnalysisResult? = nil
    var lastUpdatedAtElapsedMs: Int64? = nil
    var calibrationMessage: String? = nil
    var statusMessage: String = "Waiting for analysis data."

    var activityMode: ActivityMode {
        config.activityMode
    }
}
