package com.example.seniordesignmobileapp.domain

import com.example.seniordesignmobileapp.analysis.AnalysisConfig
import com.example.seniordesignmobileapp.analysis.AnalysisInputWindow
import com.example.seniordesignmobileapp.analysis.AnalysisWindowSummary
import com.example.seniordesignmobileapp.analysis.PlaceholderPostureAnalyzer
import com.example.seniordesignmobileapp.analysis.PostureAlert
import com.example.seniordesignmobileapp.analysis.PostureAlertCode
import com.example.seniordesignmobileapp.analysis.PostureAnalysisResult
import com.example.seniordesignmobileapp.analysis.PostureAnalyzer
import com.example.seniordesignmobileapp.analysis.PostureState
import com.example.seniordesignmobileapp.analysis.WindowSpec
import com.example.seniordesignmobileapp.model.ImuSample
import com.example.seniordesignmobileapp.model.NetworkStatus

class PostureAnalysisCoordinator(
    private val config: AnalysisConfig = AnalysisConfig.sittingDefault(),
    private val analyzer: PostureAnalyzer = PlaceholderPostureAnalyzer(),
) {
    private val bufferedSamplesBySensor = mutableMapOf<Int, ArrayDeque<BufferedSample>>()
    private val observedSensorIds = linkedSetOf<Int>()
    private var latestNetworkStatus: NetworkStatus? = null

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

    fun clear() {
        bufferedSamplesBySensor.clear()
        observedSensorIds.clear()
        latestNetworkStatus = null
    }

    fun currentSnapshot(
        nowEpochMs: Long,
    ): PostureAnalysisSnapshot = buildSnapshot(nowEpochMs)

    private fun buildSnapshot(
        nowEpochMs: Long,
    ): PostureAnalysisSnapshot {
        pruneExpiredSamples(nowEpochMs)

        val windowDurationMs = config.windowSpec.maxDurationMs()
        val lookbackMs = config.historyLookbackMs
        val windowEndEpochMs = nowEpochMs - lookbackMs
        val windowStartEpochMs = windowEndEpochMs - windowDurationMs
        val minWindowDurationMs = config.windowSpec.minDurationMs()

        val timedSamplesBySensor = bufferedSamplesBySensor.mapValues { (_, samples) ->
            samples.filter { it.receivedAtEpochMs in windowStartEpochMs..windowEndEpochMs }
        }.filterValues { it.isNotEmpty() }
        val samplesBySensor = timedSamplesBySensor.mapValues { (_, samples) ->
            samples.map { it.sample }
        }

        val expectedSensors = resolveExpectedSensors()
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
                config = config,
                expectedSensors = expectedSensors,
                expectedSensorsInferred = config.expectedSensors.isEmpty(),
                windowSummary = windowSummary,
                latestResult = null,
                statusMessage = "Waiting for enough live samples to build an analysis window.",
            )
        }

        val inputWindow = AnalysisInputWindow(
            windowStartEpochMs = windowStartEpochMs,
            windowEndEpochMs = windowEndEpochMs,
            lookbackMs = lookbackMs,
            samplesBySensor = samplesBySensor,
            networkStatus = latestNetworkStatus,
            expectedSensors = expectedSensors,
            availableSensors = availableSensors,
            missingSensors = missingSensors,
        )

        val baseResult = analyzer.analyze(inputWindow, config)
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
            coverageMs < minWindowDurationMs -> "Collecting more sample history for the requested analysis window."
            missingSensors.isNotEmpty() && config.allowPartialAnalysis -> "Running partial analysis while waiting for all expected sensors."
            missingSensors.isNotEmpty() -> "Waiting for all expected sensors before full analysis."
            else -> "Analysis window is ready for posture scoring."
        }

        return PostureAnalysisSnapshot(
            config = config,
            expectedSensors = expectedSensors,
            expectedSensorsInferred = config.expectedSensors.isEmpty(),
            windowSummary = windowSummary,
            latestResult = result.withPostureState(
                warningThreshold = config.warningScoreThreshold,
                poorThreshold = config.poorScoreThreshold,
            ),
            statusMessage = statusMessage,
        )
    }

    private fun resolveExpectedSensors(): Set<Int> {
        val configuredSensors = config.expectedSensors.map { it.sensorId }.toSet()
        if (configuredSensors.isNotEmpty()) {
            return configuredSensors
        }
        return observedSensorIds.toSet()
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
        val sampleCount = sampleTimes.size
        if (sampleCount == 0) {
            return 0L
        }
        val earliestSampleEpochMs = sampleTimes.minOrNull() ?: return 0L
        return (windowEndEpochMs - maxOf(windowStartEpochMs, earliestSampleEpochMs)).coerceAtLeast(0L)
    }
}

data class PostureAnalysisSnapshot(
    val config: AnalysisConfig,
    val expectedSensors: Set<Int>,
    val expectedSensorsInferred: Boolean,
    val windowSummary: AnalysisWindowSummary,
    val latestResult: PostureAnalysisResult?,
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

private fun PostureAnalysisResult.withPostureState(
    warningThreshold: Float,
    poorThreshold: Float,
): PostureAnalysisResult {
    val adjustedState = if (postureState == PostureState.INCOMPLETE) {
        postureState
    } else {
        when {
            score <= poorThreshold -> PostureState.POOR
            score <= warningThreshold -> PostureState.WARNING
            else -> PostureState.GOOD
        }
    }
    return copy(postureState = adjustedState)
}
