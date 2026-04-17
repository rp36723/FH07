import Foundation

protocol PostureAnalyzer {
    func analyze(input: AnalysisInputWindow, config: AnalysisConfig) -> PostureAnalysisResult
}

final class SittingPostureAnalyzer: PostureAnalyzer {
    func analyze(input: AnalysisInputWindow, config: AnalysisConfig) -> PostureAnalysisResult {
        let upperBackSensorId = input.sensorAssignments
            .first(where: { $0.placement == .upperBack })?
            .sensorId
        let lowerBackSensorId = input.sensorAssignments
            .first(where: { $0.placement == .lowerBack })?
            .sensorId
        let upperBackSamples: [ImuSample] = upperBackSensorId.flatMap { input.samplesBySensor[$0] } ?? []
        let lowerBackSamples: [ImuSample] = lowerBackSensorId.flatMap { input.samplesBySensor[$0] } ?? []
        let upperBackMetrics: SensorWindowMetrics? = upperBackSensorId != nil
            ? SittingPostureMath.computeSensorMetrics(samples: upperBackSamples)
            : nil
        let lowerBackMetrics: SensorWindowMetrics? = lowerBackSensorId != nil
            ? SittingPostureMath.computeSensorMetrics(samples: lowerBackSamples)
            : nil
        let bendAngleDeg: Float?
        if let upper = upperBackMetrics, let lower = lowerBackMetrics {
            bendAngleDeg = SittingPostureMath.angleBetween(first: upper.gravityVector, second: lower.gravityVector)
        } else {
            bendAngleDeg = nil
        }
        let calibration = input.sittingCalibration
        let bendDeltaFromBaselineDeg: Float?
        if let bend = bendAngleDeg, let calib = calibration {
            bendDeltaFromBaselineDeg = abs(bend - calib.bendAngleDeg)
        } else {
            bendDeltaFromBaselineDeg = nil
        }

        var alerts: [PostureAlert] = []
        if !input.missingSensors.isEmpty {
            alerts.append(
                PostureAlert(
                    code: .sensorMissing,
                    message: "Missing sensors: \(input.missingSensors.sorted().map(String.init).joined(separator: ", "))"
                )
            )
        }
        if calibration == nil {
            alerts.append(
                PostureAlert(
                    code: .calibrationRequired,
                    message: "Capture an upright sitting calibration before trusting the score."
                )
            )
        }
        let details = SittingPostureDetails(
            upperBackSensorId: upperBackSensorId,
            lowerBackSensorId: lowerBackSensorId,
            upperBackPitchDeg: upperBackMetrics?.pitchDeg,
            lowerBackPitchDeg: lowerBackMetrics?.pitchDeg,
            bendAngleDeg: bendAngleDeg,
            bendDeltaFromBaselineDeg: bendDeltaFromBaselineDeg,
            baselineBendAngleDeg: calibration?.bendAngleDeg
        )

        if upperBackMetrics == nil || lowerBackMetrics == nil || calibration == nil || bendDeltaFromBaselineDeg == nil {
            var contributingSensors: Set<Int> = []
            if let id = upperBackSensorId, upperBackMetrics != nil { contributingSensors.insert(id) }
            if let id = lowerBackSensorId, lowerBackMetrics != nil { contributingSensors.insert(id) }
            return PostureAnalysisResult(
                timestampEpochMs: input.windowEndEpochMs,
                score: 0,
                postureState: .incomplete,
                confidence: (upperBackMetrics != nil || lowerBackMetrics != nil) ? 0.35 : 0,
                contributingSensors: contributingSensors,
                missingSensors: input.missingSensors,
                lookbackMs: input.lookbackMs,
                alerts: alerts,
                sittingDetails: details
            )
        }

        let score = SittingPostureMath.scoreFromBendDelta(deltaDeg: bendDeltaFromBaselineDeg!)
        let confidence = SittingPostureMath.confidenceForWindow(
            upperBackSampleCount: upperBackSamples.count,
            lowerBackSampleCount: lowerBackSamples.count
        )
        if confidence < 0.6 {
            alerts.append(
                PostureAlert(
                    code: .lowConfidence,
                    message: "Confidence is reduced because the current window has limited sample coverage."
                )
            )
        }
        let postureState: PostureState
        if score <= config.poorScoreThreshold {
            postureState = .poor
        } else if score <= config.warningScoreThreshold {
            postureState = .warning
        } else {
            postureState = .good
        }

        var contributingSensors: Set<Int> = []
        if let id = upperBackSensorId { contributingSensors.insert(id) }
        if let id = lowerBackSensorId { contributingSensors.insert(id) }

        return PostureAnalysisResult(
            timestampEpochMs: input.windowEndEpochMs,
            score: score,
            postureState: postureState,
            confidence: confidence,
            contributingSensors: contributingSensors,
            missingSensors: input.missingSensors,
            lookbackMs: input.lookbackMs,
            alerts: alerts,
            sittingDetails: details
        )
    }
}

struct SensorWindowMetrics {
    let gravityVector: GravityVector
    let pitchDeg: Float
}

struct GravityVector: Equatable {
    let x: Double
    let y: Double
    let z: Double
}

enum SittingPostureMath {
    static func computeSensorMetrics(samples: [ImuSample]) -> SensorWindowMetrics? {
        if samples.isEmpty { return nil }

        let averageAx = samples.map { Double($0.ax) }.reduce(0, +) / Double(samples.count)
        let averageAy = samples.map { Double($0.ay) }.reduce(0, +) / Double(samples.count)
        let averageAz = samples.map { Double($0.az) }.reduce(0, +) / Double(samples.count)
        let magnitude = (averageAx * averageAx + averageAy * averageAy + averageAz * averageAz).squareRoot()
        if magnitude == 0 { return nil }

        let gravityVector = GravityVector(
            x: averageAx / magnitude,
            y: averageAy / magnitude,
            z: averageAz / magnitude
        )
        let pitchRad = atan2(
            gravityVector.x,
            (gravityVector.y * gravityVector.y + gravityVector.z * gravityVector.z).squareRoot()
        )
        let pitchDeg = Float(pitchRad * 180.0 / .pi)

        return SensorWindowMetrics(gravityVector: gravityVector, pitchDeg: pitchDeg)
    }

    static func angleBetween(first: GravityVector, second: GravityVector) -> Float {
        let dotProduct = (first.x * second.x) + (first.y * second.y) + (first.z * second.z)
        let clamped = min(max(dotProduct, -1.0), 1.0)
        return Float(acos(clamped) * 180.0 / .pi)
    }

    static func scoreFromBendDelta(deltaDeg: Float) -> Float {
        let normalized = min(max(deltaDeg / 30.0, 0), 1)
        return min(max(100.0 * (1.0 - normalized), 0), 100)
    }

    static func confidenceForWindow(upperBackSampleCount: Int, lowerBackSampleCount: Int) -> Float {
        let minimumSampleCount = min(upperBackSampleCount, lowerBackSampleCount)
        return min(max(Float(minimumSampleCount) / 20.0, 0.2), 1)
    }
}
