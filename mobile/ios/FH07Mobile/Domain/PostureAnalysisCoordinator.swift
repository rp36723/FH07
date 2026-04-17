import Foundation

struct CalibrationCaptureResult {
    let success: Bool
    let message: String
    let snapshot: PostureAnalysisSnapshot
}

struct PostureAnalysisSnapshot {
    let config: AnalysisConfig
    let sensorAssignments: [SensorAssignment]
    let availableSensorIds: [Int]
    let manualUpperBackSensorId: Int?
    let manualLowerBackSensorId: Int?
    let expectedSensors: Set<Int>
    let expectedSensorsInferred: Bool
    let sittingCalibration: SittingCalibration?
    let windowSummary: AnalysisWindowSummary
    let latestResult: PostureAnalysisResult?
    let calibrationMessage: String?
    let statusMessage: String
}

final class PostureAnalysisCoordinator {
    private let config: AnalysisConfig
    private let analyzer: PostureAnalyzer

    private var bufferedSamplesBySensor: [Int: [BufferedSample]] = [:]
    private var observedSensorIds: [Int] = [] // insertion-ordered unique set
    private var latestNetworkStatus: NetworkStatus? = nil
    private var sittingCalibration: SittingCalibration? = nil
    private var calibrationMessage: String? = nil
    private var manualUpperBackSensorId: Int? = nil
    private var manualLowerBackSensorId: Int? = nil

    init(
        config: AnalysisConfig = AnalysisConfig.sittingDefault(),
        analyzer: PostureAnalyzer = SittingPostureAnalyzer()
    ) {
        self.config = config
        self.analyzer = analyzer
    }

    func onSample(receivedAtEpochMs: Int64, sample: ImuSample) -> PostureAnalysisSnapshot {
        if !observedSensorIds.contains(sample.sensorId) {
            observedSensorIds.append(sample.sensorId)
        }
        bufferedSamplesBySensor[sample.sensorId, default: []]
            .append(BufferedSample(receivedAtEpochMs: receivedAtEpochMs, sample: sample))
        return buildSnapshot(nowEpochMs: receivedAtEpochMs)
    }

    func onNetworkStatus(receivedAtEpochMs: Int64, status: NetworkStatus) -> PostureAnalysisSnapshot {
        latestNetworkStatus = status
        for s in status.sensors {
            if !observedSensorIds.contains(s.sensorId) {
                observedSensorIds.append(s.sensorId)
            }
        }
        return buildSnapshot(nowEpochMs: receivedAtEpochMs)
    }

    func captureCalibration(nowEpochMs: Int64) -> CalibrationCaptureResult {
        pruneExpiredSamples(nowEpochMs: nowEpochMs)
        let sensorAssignments = resolveSensorAssignments()
        let upperBackSensorId = sensorAssignments.first(where: { $0.placement == .upperBack })?.sensorId
        let lowerBackSensorId = sensorAssignments.first(where: { $0.placement == .lowerBack })?.sensorId
        let upperBackMetrics: SensorWindowMetrics? = upperBackSensorId.flatMap { id in
            SittingPostureMath.computeSensorMetrics(samples: (bufferedSamplesBySensor[id] ?? []).map { $0.sample })
        }
        let lowerBackMetrics: SensorWindowMetrics? = lowerBackSensorId.flatMap { id in
            SittingPostureMath.computeSensorMetrics(samples: (bufferedSamplesBySensor[id] ?? []).map { $0.sample })
        }

        if upperBackSensorId == nil || lowerBackSensorId == nil || upperBackMetrics == nil || lowerBackMetrics == nil {
            calibrationMessage = "Calibration needs both upper and lower back sensors with live samples."
            return CalibrationCaptureResult(
                success: false,
                message: calibrationMessage!,
                snapshot: buildSnapshot(nowEpochMs: nowEpochMs)
            )
        }

        sittingCalibration = SittingCalibration(
            capturedAtEpochMs: nowEpochMs,
            upperBackSensorId: upperBackSensorId!,
            lowerBackSensorId: lowerBackSensorId!,
            upperBackPitchDeg: upperBackMetrics!.pitchDeg,
            lowerBackPitchDeg: lowerBackMetrics!.pitchDeg,
            bendAngleDeg: SittingPostureMath.angleBetween(
                first: upperBackMetrics!.gravityVector,
                second: lowerBackMetrics!.gravityVector
            )
        )
        calibrationMessage = "Captured upright sitting calibration using sensors \(upperBackSensorId!) and \(lowerBackSensorId!)."
        return CalibrationCaptureResult(
            success: true,
            message: calibrationMessage!,
            snapshot: buildSnapshot(nowEpochMs: nowEpochMs)
        )
    }

    func setUpperBackSensor(sensorId: Int?, nowEpochMs: Int64) -> PostureAnalysisSnapshot {
        if manualUpperBackSensorId != sensorId {
            manualUpperBackSensorId = sensorId
            clearCalibration(message: "Upper back sensor selection updated. Recalibrate sitting posture.")
        }
        return buildSnapshot(nowEpochMs: nowEpochMs)
    }

    func setLowerBackSensor(sensorId: Int?, nowEpochMs: Int64) -> PostureAnalysisSnapshot {
        if manualLowerBackSensorId != sensorId {
            manualLowerBackSensorId = sensorId
            clearCalibration(message: "Lower back sensor selection updated. Recalibrate sitting posture.")
        }
        return buildSnapshot(nowEpochMs: nowEpochMs)
    }

    func clear() {
        bufferedSamplesBySensor.removeAll()
        observedSensorIds.removeAll()
        latestNetworkStatus = nil
        sittingCalibration = nil
        calibrationMessage = nil
        manualUpperBackSensorId = nil
        manualLowerBackSensorId = nil
    }

    func currentSnapshot(nowEpochMs: Int64) -> PostureAnalysisSnapshot {
        buildSnapshot(nowEpochMs: nowEpochMs)
    }

    private func buildSnapshot(nowEpochMs: Int64) -> PostureAnalysisSnapshot {
        pruneExpiredSamples(nowEpochMs: nowEpochMs)

        let sensorAssignments = resolveSensorAssignments()
        var effectiveConfig = config
        effectiveConfig.expectedSensors = sensorAssignments
        let windowDurationMs = effectiveConfig.windowSpec.maxDurationMs
        let lookbackMs = effectiveConfig.historyLookbackMs
        let windowEndEpochMs = nowEpochMs - lookbackMs
        let windowStartEpochMs = windowEndEpochMs - windowDurationMs
        let minWindowDurationMs = effectiveConfig.windowSpec.minDurationMs

        var timedSamplesBySensor: [Int: [BufferedSample]] = [:]
        for (sensorId, samples) in bufferedSamplesBySensor {
            let filtered = samples.filter { $0.receivedAtEpochMs >= windowStartEpochMs && $0.receivedAtEpochMs <= windowEndEpochMs }
            if !filtered.isEmpty {
                timedSamplesBySensor[sensorId] = filtered
            }
        }
        let samplesBySensor: [Int: [ImuSample]] = timedSamplesBySensor.mapValues { $0.map { $0.sample } }

        let expectedSensors = Set(sensorAssignments.filter { $0.required }.map { $0.sensorId })
        let availableSensors = Set(samplesBySensor.keys)
        let missingSensors = expectedSensors.subtracting(availableSensors)

        let windowSummary = AnalysisWindowSummary(
            windowStartEpochMs: windowStartEpochMs,
            windowEndEpochMs: windowEndEpochMs,
            lookbackMs: lookbackMs,
            sampleCountsBySensor: samplesBySensor.mapValues { $0.count },
            availableSensors: availableSensors,
            missingSensors: missingSensors
        )

        if samplesBySensor.isEmpty {
            return PostureAnalysisSnapshot(
                config: effectiveConfig,
                sensorAssignments: sensorAssignments,
                availableSensorIds: resolveAvailableSensorIds(),
                manualUpperBackSensorId: manualUpperBackSensorId,
                manualLowerBackSensorId: manualLowerBackSensorId,
                expectedSensors: expectedSensors,
                expectedSensorsInferred: config.expectedSensors.isEmpty,
                sittingCalibration: sittingCalibration,
                windowSummary: windowSummary,
                latestResult: nil,
                calibrationMessage: calibrationMessage,
                statusMessage: "Waiting for enough live samples to build an analysis window."
            )
        }

        let inputWindow = AnalysisInputWindow(
            windowStartEpochMs: windowStartEpochMs,
            windowEndEpochMs: windowEndEpochMs,
            lookbackMs: lookbackMs,
            samplesBySensor: samplesBySensor,
            networkStatus: latestNetworkStatus,
            sensorAssignments: sensorAssignments,
            expectedSensors: expectedSensors,
            availableSensors: availableSensors,
            missingSensors: missingSensors,
            sittingCalibration: sittingCalibration
        )

        var baseResult = analyzer.analyze(input: inputWindow, config: effectiveConfig)
        let coverageMs = computeCoverageMs(
            timedSamplesBySensor: timedSamplesBySensor,
            windowStartEpochMs: windowStartEpochMs,
            windowEndEpochMs: windowEndEpochMs
        )
        var augmentedAlerts = baseResult.alerts
        if coverageMs < minWindowDurationMs {
            let alert = PostureAlert(
                code: .insufficientWindow,
                message: "Only \(coverageMs) ms of history is buffered; \(minWindowDurationMs) ms minimum is preferred."
            )
            if !augmentedAlerts.contains(alert) {
                augmentedAlerts.append(alert)
            }
        }
        baseResult.alerts = augmentedAlerts
        let statusMessage: String
        if sensorAssignments.count < 2 {
            statusMessage = "Waiting to identify two back sensors for sitting analysis."
        } else if coverageMs < minWindowDurationMs {
            statusMessage = "Collecting more sample history for the requested analysis window."
        } else if sittingCalibration == nil {
            statusMessage = "Capture an upright sitting calibration to start scoring."
        } else if !missingSensors.isEmpty {
            statusMessage = "Partial posture preview only; both back sensors are needed for a score."
        } else {
            statusMessage = "Calibrated sitting analysis is active."
        }

        return PostureAnalysisSnapshot(
            config: effectiveConfig,
            sensorAssignments: sensorAssignments,
            availableSensorIds: resolveAvailableSensorIds(),
            manualUpperBackSensorId: manualUpperBackSensorId,
            manualLowerBackSensorId: manualLowerBackSensorId,
            expectedSensors: expectedSensors,
            expectedSensorsInferred: config.expectedSensors.isEmpty,
            sittingCalibration: sittingCalibration,
            windowSummary: windowSummary,
            latestResult: baseResult,
            calibrationMessage: calibrationMessage,
            statusMessage: statusMessage
        )
    }

    private func resolveSensorAssignments() -> [SensorAssignment] {
        if !config.expectedSensors.isEmpty {
            return config.expectedSensors
        }

        var availableSensorIds = resolveAvailableSensorIds()
        var assignments: [SensorAssignment] = []

        if let lowerBackSensorId = resolveSensorForPlacement(
            preferredSensorId: manualLowerBackSensorId,
            availableSensorIds: availableSensorIds
        ) {
            assignments.append(SensorAssignment(sensorId: lowerBackSensorId, placement: .lowerBack))
            availableSensorIds.removeAll(where: { $0 == lowerBackSensorId })
        }

        if let upperBackSensorId = resolveSensorForPlacement(
            preferredSensorId: manualUpperBackSensorId,
            availableSensorIds: availableSensorIds
        ) {
            assignments.append(SensorAssignment(sensorId: upperBackSensorId, placement: .upperBack))
        }

        return assignments
    }

    private func resolveAvailableSensorIds() -> [Int] {
        let liveSensorIds: [Int]
        if let status = latestNetworkStatus, !status.sensors.isEmpty {
            liveSensorIds = status.sensors.map { $0.sensorId }.sorted()
        } else if !observedSensorIds.isEmpty {
            liveSensorIds = observedSensorIds.sorted()
        } else {
            liveSensorIds = []
        }

        var result = liveSensorIds
        if let id = manualLowerBackSensorId { result.append(id) }
        if let id = manualUpperBackSensorId { result.append(id) }
        return Array(Set(result)).sorted()
    }

    private func resolveSensorForPlacement(preferredSensorId: Int?, availableSensorIds: [Int]) -> Int? {
        if let preferred = preferredSensorId, availableSensorIds.contains(preferred) {
            return preferred
        }
        return availableSensorIds.first
    }

    private func clearCalibration(message: String) {
        sittingCalibration = nil
        calibrationMessage = message
    }

    private func pruneExpiredSamples(nowEpochMs: Int64) {
        let retentionStartEpochMs = nowEpochMs - config.windowSpec.maxDurationMs - config.historyLookbackMs
        for (sensorId, samples) in bufferedSamplesBySensor {
            var mutated = samples
            while let first = mutated.first, first.receivedAtEpochMs < retentionStartEpochMs {
                mutated.removeFirst()
            }
            if mutated.isEmpty {
                bufferedSamplesBySensor.removeValue(forKey: sensorId)
            } else {
                bufferedSamplesBySensor[sensorId] = mutated
            }
        }
    }

    private func computeCoverageMs(
        timedSamplesBySensor: [Int: [BufferedSample]],
        windowStartEpochMs: Int64,
        windowEndEpochMs: Int64
    ) -> Int64 {
        let sampleTimes = timedSamplesBySensor.values.flatMap { $0 }.map { $0.receivedAtEpochMs }
        if sampleTimes.isEmpty { return 0 }
        guard let earliestSampleEpochMs = sampleTimes.min() else { return 0 }
        return max(windowEndEpochMs - max(windowStartEpochMs, earliestSampleEpochMs), 0)
    }
}

private struct BufferedSample {
    let receivedAtEpochMs: Int64
    let sample: ImuSample
}
