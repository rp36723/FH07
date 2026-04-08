package com.example.seniordesignmobileapp.domain

import com.example.seniordesignmobileapp.analysis.AnalysisConfig
import com.example.seniordesignmobileapp.analysis.AnalysisInputWindow
import com.example.seniordesignmobileapp.analysis.AnalysisWindowSummary
import com.example.seniordesignmobileapp.analysis.PostureAlert
import com.example.seniordesignmobileapp.analysis.PostureAlertCode
import com.example.seniordesignmobileapp.analysis.PostureAnalysisResult
import com.example.seniordesignmobileapp.analysis.PostureAnalyzer
import com.example.seniordesignmobileapp.analysis.SensorAssignment
import com.example.seniordesignmobileapp.analysis.SensorPlacement
import com.example.seniordesignmobileapp.analysis.SittingCalibration
import com.example.seniordesignmobileapp.analysis.SittingPostureAnalyzer
import com.example.seniordesignmobileapp.analysis.SittingPostureMath
import com.example.seniordesignmobileapp.analysis.WindowSpec
import com.example.seniordesignmobileapp.model.ImuSample
import com.example.seniordesignmobileapp.model.NetworkStatus

class PostureAnalysisCoordinator(
    private val config: AnalysisConfig = AnalysisConfig.sittingDefault(),
    private val analyzer: PostureAnalyzer = SittingPostureAnalyzer(),
) {
    private val bufferedSamplesBySensor = mutableMapOf<Int, ArrayDeque<BufferedSample>>()
    private val observedSensorIds = linkedSetOf<Int>()
    private var latestNetworkStatus: NetworkStatus? = null
    private var sittingCalibration: SittingCalibration? = null
    private var calibrationMessage: String? = null

    fun onSample(
        receivedAtEpochMs: Long,
        sample: ImuSample,
    ): PostureAnalysisSnapshot {
        observedSensorIds += sample.sensorId
        bufferedSamplesBySensor.getOrPut(sample.sensorId) { ArrayDeque() }
            .addLast(BufferedSample(receivedAtEpochMs, sample))
        return buildSnapshot(nowEpochMs = receivedAtEpochMs)
    }

    fun onNetworkStatus(
        receivedAtEpochMs: Long,
        status: NetworkStatus,
    ): PostureAnalysisSnapshot {
        latestNetworkStatus = status
        observedSensorIds += status.sensors.map { it.sensorId }
        return buildSnapshot(nowEpochMs = receivedAtEpochMs)
    }

    fun captureCalibration(
        nowEpochMs: Long,
    ): CalibrationCaptureResult {
        pruneExpiredSamples(nowEpochMs)
        val sensorAssignments = resolveSensorAssignments()
        val upperBackSensorId = sensorAssignments
            .firstOrNull { it.placement == SensorPlacement.UPPER_BACK }
            ?.sensorId
        val lowerBackSensorId = sensorAssignments
            .firstOrNull { it.placement == SensorPlacement.LOWER_BACK }
            ?.sensorId
        val upperBackMetrics = upperBackSensorId?.let { sensorId ->
            SittingPostureMath.computeSensorMetrics(bufferedSamplesBySensor[sensorId].orEmpty().map { it.sample })
        }
        val lowerBackMetrics = lowerBackSensorId?.let { sensorId ->
            SittingPostureMath.computeSensorMetrics(bufferedSamplesBySensor[sensorId].orEmpty().map { it.sample })
        }

        if (upperBackSensorId == null || lowerBackSensorId == null || upperBackMetrics == null || lowerBackMetrics == null) {
            calibrationMessage = "Calibration needs both upper and lower back sensors with live samples."
            return CalibrationCaptureResult(
                success = false,
                message = calibrationMessage!!,
                snapshot = buildSnapshot(nowEpochMs),
            )
        }

        sittingCalibration = SittingCalibration(
            capturedAtEpochMs = nowEpochMs,
            upperBackSensorId = upperBackSensorId,
            lowerBackSensorId = lowerBackSensorId,
            upperBackPitchDeg = upperBackMetrics.pitchDeg,
            lowerBackPitchDeg = lowerBackMetrics.pitchDeg,
            bendAngleDeg = SittingPostureMath.angleBetween(
                upperBackMetrics.gravityVector,
                lowerBackMetrics.gravityVector,
            ),
        )
        calibrationMessage = "Captured upright sitting calibration using sensors $upperBackSensorId and $lowerBackSensorId."
        return CalibrationCaptureResult(
            success = true,
            message = calibrationMessage!!,
            snapshot = buildSnapshot(nowEpochMs),
        )
    }

    fun clear() {
        bufferedSamplesBySensor.clear()
        observedSensorIds.clear()
        latestNetworkStatus = null
        sittingCalibration = null
        calibrationMessage = null
    }

    fun currentSnapshot(
        nowEpochMs: Long,
    ): PostureAnalysisSnapshot = buildSnapshot(nowEpochMs)

    private fun buildSnapshot(
        nowEpochMs: Long,
    ): PostureAnalysisSnapshot {
        pruneExpiredSamples(nowEpochMs)

        val sensorAssignments = resolveSensorAssignments()
        val effectiveConfig = config.copy(expectedSensors = sensorAssignments)
        val windowDurationMs = effectiveConfig.windowSpec.maxDurationMs()
        val lookbackMs = effectiveConfig.historyLookbackMs
        val windowEndEpochMs = nowEpochMs - lookbackMs
        val windowStartEpochMs = windowEndEpochMs - windowDurationMs
        val minWindowDurationMs = effectiveConfig.windowSpec.minDurationMs()

        val timedSamplesBySensor = bufferedSamplesBySensor.mapValues { (_, samples) ->
            samples.filter { it.receivedAtEpochMs in windowStartEpochMs..windowEndEpochMs }
        }.filterValues { it.isNotEmpty() }
        val samplesBySensor = timedSamplesBySensor.mapValues { (_, samples) ->
            samples.map { it.sample }
        }

        val expectedSensors = sensorAssignments
            .filter { it.required }
            .map { it.sensorId }
            .toSet()
        val availableSensors = samplesBySensor.keys
        val missingSensors = expectedSensors - availableSensors

        val windowSummary = AnalysisWindowSummary(
            windowStartEpochMs = windowStartEpochMs,
            windowEndEpochMs = windowEndEpochMs,
            lookbackMs = lookbackMs,
            sampleCountsBySensor = samplesBySensor.mapValues { it.value.size },
            availableSensors = availableSensors,
            missingSensors = missingSensors,
        )

        if (samplesBySensor.isEmpty()) {
            return PostureAnalysisSnapshot(
                config = effectiveConfig,
                sensorAssignments = sensorAssignments,
                expectedSensors = expectedSensors,
                expectedSensorsInferred = config.expectedSensors.isEmpty(),
                sittingCalibration = sittingCalibration,
                windowSummary = windowSummary,
                latestResult = null,
                calibrationMessage = calibrationMessage,
                statusMessage = "Waiting for enough live samples to build an analysis window.",
            )
        }

        val inputWindow = AnalysisInputWindow(
            windowStartEpochMs = windowStartEpochMs,
            windowEndEpochMs = windowEndEpochMs,
            lookbackMs = lookbackMs,
            samplesBySensor = samplesBySensor,
            networkStatus = latestNetworkStatus,
            sensorAssignments = sensorAssignments,
            expectedSensors = expectedSensors,
            availableSensors = availableSensors,
            missingSensors = missingSensors,
            sittingCalibration = sittingCalibration,
        )

        val baseResult = analyzer.analyze(inputWindow, effectiveConfig)
        val coverageMs = computeCoverageMs(
            timedSamplesBySensor = timedSamplesBySensor,
            windowStartEpochMs = windowStartEpochMs,
            windowEndEpochMs = windowEndEpochMs,
        )
        val augmentedAlerts = buildList {
            addAll(baseResult.alerts)
            if (coverageMs < minWindowDurationMs) {
                add(
                    PostureAlert(
                        code = PostureAlertCode.INSUFFICIENT_WINDOW,
                        message = "Only ${coverageMs} ms of history is buffered; ${minWindowDurationMs} ms minimum is preferred.",
                    )
                )
            }
        }.distinct()
        val result = baseResult.copy(alerts = augmentedAlerts)
        val statusMessage = when {
            sensorAssignments.size < 2 -> "Waiting to identify two back sensors for sitting analysis."
            coverageMs < minWindowDurationMs -> "Collecting more sample history for the requested analysis window."
            sittingCalibration == null -> "Capture an upright sitting calibration to start scoring."
            missingSensors.isNotEmpty() -> "Partial posture preview only; both back sensors are needed for a score."
            else -> "Calibrated sitting analysis is active."
        }

        return PostureAnalysisSnapshot(
            config = effectiveConfig,
            sensorAssignments = sensorAssignments,
            expectedSensors = expectedSensors,
            expectedSensorsInferred = config.expectedSensors.isEmpty(),
            sittingCalibration = sittingCalibration,
            windowSummary = windowSummary,
            latestResult = result,
            calibrationMessage = calibrationMessage,
            statusMessage = statusMessage,
        )
    }

    private fun resolveSensorAssignments(): List<SensorAssignment> {
        if (config.expectedSensors.isNotEmpty()) {
            return config.expectedSensors
        }

        val inferredSensorIds = when {
            latestNetworkStatus?.sensors?.isNotEmpty() == true -> latestNetworkStatus!!.sensors
                .map { it.sensorId }
                .sorted()

            observedSensorIds.isNotEmpty() -> observedSensorIds.sorted()
            else -> emptyList()
        }

        return buildList {
            inferredSensorIds.getOrNull(0)?.let { sensorId ->
                add(
                    SensorAssignment(
                        sensorId = sensorId,
                        placement = SensorPlacement.LOWER_BACK,
                    )
                )
            }
            inferredSensorIds.getOrNull(1)?.let { sensorId ->
                add(
                    SensorAssignment(
                        sensorId = sensorId,
                        placement = SensorPlacement.UPPER_BACK,
                    )
                )
            }
        }
    }

    private fun pruneExpiredSamples(nowEpochMs: Long) {
        val retentionStartEpochMs = nowEpochMs - config.windowSpec.maxDurationMs() - config.historyLookbackMs
        val iterator = bufferedSamplesBySensor.iterator()
        while (iterator.hasNext()) {
            val (_, samples) = iterator.next()
            while (samples.isNotEmpty() && samples.first().receivedAtEpochMs < retentionStartEpochMs) {
                samples.removeFirst()
            }
            if (samples.isEmpty()) {
                iterator.remove()
            }
        }
    }

    private fun computeCoverageMs(
        timedSamplesBySensor: Map<Int, List<BufferedSample>>,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
    ): Long {
        val sampleTimes = timedSamplesBySensor.values.flatten().map { it.receivedAtEpochMs }
        if (sampleTimes.isEmpty()) {
            return 0L
        }
        val earliestSampleEpochMs = sampleTimes.minOrNull() ?: return 0L
        return (windowEndEpochMs - maxOf(windowStartEpochMs, earliestSampleEpochMs)).coerceAtLeast(0L)
    }
}

data class CalibrationCaptureResult(
    val success: Boolean,
    val message: String,
    val snapshot: PostureAnalysisSnapshot,
)

data class PostureAnalysisSnapshot(
    val config: AnalysisConfig,
    val sensorAssignments: List<SensorAssignment>,
    val expectedSensors: Set<Int>,
    val expectedSensorsInferred: Boolean,
    val sittingCalibration: SittingCalibration?,
    val windowSummary: AnalysisWindowSummary,
    val latestResult: PostureAnalysisResult?,
    val calibrationMessage: String?,
    val statusMessage: String,
)

private data class BufferedSample(
    val receivedAtEpochMs: Long,
    val sample: ImuSample,
)

private fun WindowSpec.maxDurationMs(): Long =
    when (this) {
        is WindowSpec.FixedDuration -> durationMs
        is WindowSpec.DurationRange -> maxDurationMs
    }

private fun WindowSpec.minDurationMs(): Long =
    when (this) {
        is WindowSpec.FixedDuration -> durationMs
        is WindowSpec.DurationRange -> minDurationMs
    }
