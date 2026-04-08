package com.example.seniordesignmobileapp.domain

import com.example.seniordesignmobileapp.analysis.ActivityMode
import com.example.seniordesignmobileapp.analysis.AnalysisConfig
import com.example.seniordesignmobileapp.analysis.AnalysisInputWindow
import com.example.seniordesignmobileapp.analysis.PostureAnalysisResult
import com.example.seniordesignmobileapp.analysis.PostureAnalyzer
import com.example.seniordesignmobileapp.analysis.PostureState
import com.example.seniordesignmobileapp.analysis.SensorAssignment
import com.example.seniordesignmobileapp.analysis.SensorPlacement
import com.example.seniordesignmobileapp.analysis.WindowSpec
import com.example.seniordesignmobileapp.model.ActiveSensorStatus
import com.example.seniordesignmobileapp.model.ImuSample
import com.example.seniordesignmobileapp.model.NetworkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostureAnalysisCoordinatorTest {
    @Test
    fun onNetworkStatus_withoutSamples_keepsAnalysisWaiting() {
        val coordinator = PostureAnalysisCoordinator()

        val snapshot = coordinator.onNetworkStatus(
            receivedAtEpochMs = 1_000L,
            status = networkStatus(sensorIds = listOf(1, 2)),
        )

        assertEquals(setOf(1, 2), snapshot.expectedSensors)
        assertTrue(snapshot.expectedSensorsInferred)
        assertTrue(snapshot.windowSummary.sampleCountsBySensor.isEmpty())
        assertNull(snapshot.latestResult)
        assertEquals(
            "Waiting for enough live samples to build an analysis window.",
            snapshot.statusMessage,
        )
    }

    @Test
    fun onSample_usesConfiguredExpectedSensorsAndAllowsPartialAnalysis() {
        val analyzer = RecordingAnalyzer()
        val coordinator = PostureAnalysisCoordinator(
            config = AnalysisConfig(
                activityMode = ActivityMode.SITTING,
                expectedSensors = listOf(
                    SensorAssignment(sensorId = 1, placement = SensorPlacement.LOWER_BACK),
                    SensorAssignment(sensorId = 2, placement = SensorPlacement.RIGHT_THIGH),
                ),
                windowSpec = WindowSpec.FixedDuration(durationMs = 1_000),
                historyLookbackMs = 0,
                allowPartialAnalysis = true,
                warningScoreThreshold = 70f,
                poorScoreThreshold = 40f,
            ),
            analyzer = analyzer,
        )

        coordinator.onSample(
            receivedAtEpochMs = 1_000L,
            sample = sample(sensorId = 1, seq = 1),
        )
        val snapshot = coordinator.onSample(
            receivedAtEpochMs = 2_000L,
            sample = sample(sensorId = 1, seq = 2),
        )

        assertFalse(snapshot.expectedSensorsInferred)
        assertEquals(setOf(1, 2), snapshot.expectedSensors)
        assertEquals(setOf(1), snapshot.windowSummary.availableSensors)
        assertEquals(setOf(2), snapshot.windowSummary.missingSensors)
        assertEquals(
            "Running partial analysis while waiting for all expected sensors.",
            snapshot.statusMessage,
        )
        assertEquals(setOf(1, 2), analyzer.lastInput?.expectedSensors)
        assertEquals(setOf(2), analyzer.lastInput?.missingSensors)
        assertEquals(82f, snapshot.latestResult?.score)
    }

    @Test
    fun onSample_prunesSamplesOutsideConfiguredWindow() {
        val analyzer = RecordingAnalyzer()
        val coordinator = PostureAnalysisCoordinator(
            config = AnalysisConfig(
                activityMode = ActivityMode.SITTING,
                expectedSensors = emptyList(),
                windowSpec = WindowSpec.FixedDuration(durationMs = 1_000),
                historyLookbackMs = 0,
                allowPartialAnalysis = true,
                warningScoreThreshold = 70f,
                poorScoreThreshold = 40f,
            ),
            analyzer = analyzer,
        )

        coordinator.onSample(
            receivedAtEpochMs = 100L,
            sample = sample(sensorId = 1, seq = 1),
        )
        coordinator.onSample(
            receivedAtEpochMs = 700L,
            sample = sample(sensorId = 1, seq = 2),
        )
        val snapshot = coordinator.onSample(
            receivedAtEpochMs = 2_000L,
            sample = sample(sensorId = 1, seq = 3),
        )

        assertEquals(mapOf(1 to 1), snapshot.windowSummary.sampleCountsBySensor)
        assertEquals(1, analyzer.lastInput?.samplesBySensor?.get(1)?.size)
        assertEquals(3, analyzer.lastInput?.samplesBySensor?.get(1)?.single()?.seq)
    }

    @Test
    fun onSample_addsInsufficientWindowAlertUntilEnoughHistoryIsBuffered() {
        val analyzer = RecordingAnalyzer()
        val coordinator = PostureAnalysisCoordinator(
            config = AnalysisConfig(
                activityMode = ActivityMode.SITTING,
                expectedSensors = emptyList(),
                windowSpec = WindowSpec.DurationRange(
                    minDurationMs = 1_000,
                    maxDurationMs = 5_000,
                ),
                historyLookbackMs = 0,
                allowPartialAnalysis = true,
                warningScoreThreshold = 70f,
                poorScoreThreshold = 40f,
            ),
            analyzer = analyzer,
        )

        val snapshot = coordinator.onSample(
            receivedAtEpochMs = 5_000L,
            sample = sample(sensorId = 1, seq = 1),
        )

        assertTrue(
            snapshot.latestResult?.alerts?.any { alert ->
                alert.message.contains("minimum is preferred")
            } == true
        )
        assertEquals(
            "Collecting more sample history for the requested analysis window.",
            snapshot.statusMessage,
        )
    }

    private fun sample(
        sensorId: Int,
        seq: Int,
    ): ImuSample =
        ImuSample(
            version = 1,
            sensorId = sensorId,
            seq = seq,
            timestampMs = seq.toLong() * 10,
            ax = 1,
            ay = 2,
            az = 3,
            gx = 4,
            gy = 5,
            gz = 6,
        )

    private fun networkStatus(sensorIds: List<Int>): NetworkStatus =
        NetworkStatus(
            version = 1,
            uptimeMs = 500L,
            activeSensorCount = sensorIds.size,
            sensors = sensorIds.mapIndexed { index, sensorId ->
                ActiveSensorStatus(
                    sensorId = sensorId,
                    seq = index + 1,
                    ageMs = 25,
                )
            },
        )
}

private class RecordingAnalyzer : PostureAnalyzer {
    var lastInput: AnalysisInputWindow? = null

    override fun analyze(
        input: AnalysisInputWindow,
        config: AnalysisConfig,
    ): PostureAnalysisResult {
        lastInput = input
        return PostureAnalysisResult(
            timestampEpochMs = input.windowEndEpochMs,
            score = 82f,
            postureState = PostureState.GOOD,
            confidence = 0.75f,
            contributingSensors = input.availableSensors,
            missingSensors = input.missingSensors,
            lookbackMs = input.lookbackMs,
            alerts = emptyList(),
        )
    }
}
