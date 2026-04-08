package com.example.seniordesignmobileapp.analysis

import com.example.seniordesignmobileapp.model.ImuSample
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

interface PostureAnalyzer {
    fun analyze(
        input: AnalysisInputWindow,
        config: AnalysisConfig,
    ): PostureAnalysisResult
}

class SittingPostureAnalyzer : PostureAnalyzer {
    override fun analyze(
        input: AnalysisInputWindow,
        config: AnalysisConfig,
    ): PostureAnalysisResult {
        val upperBackSensorId = input.sensorAssignments
            .firstOrNull { it.placement == SensorPlacement.UPPER_BACK }
            ?.sensorId
        val lowerBackSensorId = input.sensorAssignments
            .firstOrNull { it.placement == SensorPlacement.LOWER_BACK }
            ?.sensorId
        val upperBackSamples = upperBackSensorId?.let(input.samplesBySensor::get).orEmpty()
        val lowerBackSamples = lowerBackSensorId?.let(input.samplesBySensor::get).orEmpty()
        val upperBackMetrics = upperBackSensorId?.let {
            SittingPostureMath.computeSensorMetrics(upperBackSamples)
        }
        val lowerBackMetrics = lowerBackSensorId?.let {
            SittingPostureMath.computeSensorMetrics(lowerBackSamples)
        }
        val bendAngleDeg = if (upperBackMetrics != null && lowerBackMetrics != null) {
            SittingPostureMath.angleBetween(upperBackMetrics.gravityVector, lowerBackMetrics.gravityVector)
        } else {
            null
        }
        val calibration = input.sittingCalibration
        val bendDeltaFromBaselineDeg = if (bendAngleDeg != null && calibration != null) {
            kotlin.math.abs(bendAngleDeg - calibration.bendAngleDeg)
        } else {
            null
        }

        val alerts = buildList {
            if (input.missingSensors.isNotEmpty()) {
                add(
                    PostureAlert(
                        code = PostureAlertCode.SENSOR_MISSING,
                        message = "Missing sensors: ${input.missingSensors.sorted().joinToString()}",
                    )
                )
            }
            if (calibration == null) {
                add(
                    PostureAlert(
                        code = PostureAlertCode.CALIBRATION_REQUIRED,
                        message = "Capture an upright sitting calibration before trusting the score.",
                    )
                )
            }
        }.toMutableList()
        val details = SittingPostureDetails(
            upperBackSensorId = upperBackSensorId,
            lowerBackSensorId = lowerBackSensorId,
            upperBackPitchDeg = upperBackMetrics?.pitchDeg,
            lowerBackPitchDeg = lowerBackMetrics?.pitchDeg,
            bendAngleDeg = bendAngleDeg,
            bendDeltaFromBaselineDeg = bendDeltaFromBaselineDeg,
            baselineBendAngleDeg = calibration?.bendAngleDeg,
        )

        if (upperBackMetrics == null || lowerBackMetrics == null || calibration == null || bendDeltaFromBaselineDeg == null) {
            return PostureAnalysisResult(
                timestampEpochMs = input.windowEndEpochMs,
                score = 0f,
                postureState = PostureState.INCOMPLETE,
                confidence = if (upperBackMetrics != null || lowerBackMetrics != null) 0.35f else 0f,
                contributingSensors = buildSet {
                    upperBackSensorId?.takeIf { upperBackMetrics != null }?.let(::add)
                    lowerBackSensorId?.takeIf { lowerBackMetrics != null }?.let(::add)
                },
                missingSensors = input.missingSensors,
                lookbackMs = input.lookbackMs,
                alerts = alerts,
                sittingDetails = details,
            )
        }

        val score = SittingPostureMath.scoreFromBendDelta(bendDeltaFromBaselineDeg)
        val confidence = SittingPostureMath.confidenceForWindow(
            upperBackSampleCount = upperBackSamples.size,
            lowerBackSampleCount = lowerBackSamples.size,
        )
        if (confidence < 0.6f) {
            alerts += PostureAlert(
                code = PostureAlertCode.LOW_CONFIDENCE,
                message = "Confidence is reduced because the current window has limited sample coverage.",
            )
        }
        val postureState = when {
            score <= config.poorScoreThreshold -> PostureState.POOR
            score <= config.warningScoreThreshold -> PostureState.WARNING
            else -> PostureState.GOOD
        }

        return PostureAnalysisResult(
            timestampEpochMs = input.windowEndEpochMs,
            score = score,
            postureState = postureState,
            confidence = confidence,
            contributingSensors = setOfNotNull(upperBackSensorId, lowerBackSensorId),
            missingSensors = input.missingSensors,
            lookbackMs = input.lookbackMs,
            alerts = alerts,
            sittingDetails = details,
        )
    }
}

data class SensorWindowMetrics(
    val gravityVector: GravityVector,
    val pitchDeg: Float,
)

data class GravityVector(
    val x: Double,
    val y: Double,
    val z: Double,
)

object SittingPostureMath {
    fun computeSensorMetrics(samples: List<ImuSample>): SensorWindowMetrics? {
        if (samples.isEmpty()) {
            return null
        }

        val averageAx = samples.map { it.ax.toDouble() }.average()
        val averageAy = samples.map { it.ay.toDouble() }.average()
        val averageAz = samples.map { it.az.toDouble() }.average()
        val magnitude = sqrt(averageAx.pow(2) + averageAy.pow(2) + averageAz.pow(2))
        if (magnitude == 0.0) {
            return null
        }

        val gravityVector = GravityVector(
            x = averageAx / magnitude,
            y = averageAy / magnitude,
            z = averageAz / magnitude,
        )
        val pitchDeg = Math.toDegrees(
            atan2(
                gravityVector.x,
                sqrt(gravityVector.y.pow(2) + gravityVector.z.pow(2)),
            )
        ).toFloat()

        return SensorWindowMetrics(
            gravityVector = gravityVector,
            pitchDeg = pitchDeg,
        )
    }

    fun angleBetween(
        first: GravityVector,
        second: GravityVector,
    ): Float {
        val dotProduct = (first.x * second.x) + (first.y * second.y) + (first.z * second.z)
        val clamped = dotProduct.coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(clamped)).toFloat()
    }

    fun scoreFromBendDelta(deltaDeg: Float): Float {
        val normalized = (deltaDeg / 30f).coerceIn(0f, 1f)
        return (100f * (1f - normalized)).coerceIn(0f, 100f)
    }

    fun confidenceForWindow(
        upperBackSampleCount: Int,
        lowerBackSampleCount: Int,
    ): Float {
        val minimumSampleCount = minOf(upperBackSampleCount, lowerBackSampleCount)
        return (minimumSampleCount / 20f).coerceIn(0.2f, 1f)
    }
}
