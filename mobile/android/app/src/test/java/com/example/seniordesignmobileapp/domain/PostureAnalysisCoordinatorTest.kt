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
                    SensorAssignment(sensorId = 2, placement = SensorPlacement.UPPER_BACK),
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
            "Capture an upright sitting calibration to start scoring.",
            snapshot.statusMessage,
        )
        assertEquals(setOf(1, 2), analyzer.lastInput?.expectedSensors)
        assertEquals(setOf(2), analyzer.lastInput?.missingSensors)
        assertEquals(
            listOf(
                SensorAssignment(sensorId = 1, placement = SensorPlacement.LOWER_BACK),
                SensorAssignment(sensorId = 2, placement = SensorPlacement.UPPER_BACK),
            ),
            analyzer.lastInput?.sensorAssignments,
        )
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

        coordinator.onNetworkStatus(
            receivedAtEpochMs = 4_900L,
            status = networkStatus(sensorIds = listOf(1, 2)),
        )
        coordinator.onSample(
            receivedAtEpochMs = 5_000L,
            sample = sample(sensorId = 1, seq = 1),
        )
        coordinator.onSample(
            receivedAtEpochMs = 5_000L,
            sample = sample(sensorId = 2, seq = 1),
        )
        val finalSnapshot = coordinator.currentSnapshot(nowEpochMs = 5_000L)

        assertTrue(
            finalSnapshot.latestResult?.alerts?.any { alert ->
                alert.message.contains("minimum is preferred")
            } == true
        )
        assertEquals(
            "Collecting more sample history for the requested analysis window.",
            finalSnapshot.statusMessage,
        )
    }

    @Test
    fun captureCalibration_usesInferredLowerAndUpperBackSensors() {
        val coordinator = PostureAnalysisCoordinator()

        coordinator.onNetworkStatus(
            receivedAtEpochMs = 1_000L,
            status = networkStatus(sensorIds = listOf(3, 7)),
        )
        coordinator.onSample(
            receivedAtEpochMs = 1_100L,
            sample = sample(sensorId = 3, seq = 1, ax = 0, ay = 0, az = 1000),
        )
        coordinator.onSample(
            receivedAtEpochMs = 1_100L,
            sample = sample(sensorId = 7, seq = 1, ax = 0, ay = 0, az = 1000),
        )

        val result = coordinator.captureCalibration(nowEpochMs = 1_200L)

        assertTrue(result.success)
        assertEquals(
            listOf(
                SensorAssignment(sensorId = 3, placement = SensorPlacement.LOWER_BACK),
                SensorAssignment(sensorId = 7, placement = SensorPlacement.UPPER_BACK),
            ),
            result.snapshot.sensorAssignments,
        )
        assertEquals(0f, result.snapshot.sittingCalibration?.bendAngleDeg)
    }

    @Test
    fun captureCalibration_requiresBothBackSensorsWithSamples() {
        val coordinator = PostureAnalysisCoordinator()

        coordinator.onNetworkStatus(
            receivedAtEpochMs = 1_000L,
            status = networkStatus(sensorIds = listOf(3, 7)),
        )
        coordinator.onSample(
            receivedAtEpochMs = 1_100L,
            sample = sample(sensorId = 3, seq = 1),
        )

        val result = coordinator.captureCalibration(nowEpochMs = 1_200L)

        assertFalse(result.success)
        assertEquals(
            "Calibration needs both upper and lower back sensors with live samples.",
            result.message,
        )
    }

    @Test
    fun manualSensorSelection_overridesAutoInference() {
        val coordinator = PostureAnalysisCoordinator()

        coordinator.onNetworkStatus(
            receivedAtEpochMs = 1_000L,
            status = networkStatus(sensorIds = listOf(3, 7, 9)),
        )

        val lowerSnapshot = coordinator.setLowerBackSensor(
            sensorId = 9,
            nowEpochMs = 1_100L,
        )
        val finalSnapshot = coordinator.setUpperBackSensor(
            sensorId = 3,
            nowEpochMs = 1_200L,
        )

        assertEquals(listOf(3, 7, 9), lowerSnapshot.availableSensorIds)
        assertEquals(9, finalSnapshot.manualLowerBackSensorId)
        assertEquals(3, finalSnapshot.manualUpperBackSensorId)
        assertEquals(
            listOf(
                SensorAssignment(sensorId = 9, placement = SensorPlacement.LOWER_BACK),
                SensorAssignment(sensorId = 3, placement = SensorPlacement.UPPER_BACK),
            ),
            finalSnapshot.sensorAssignments,
        )
    }

    @Test
    fun changingSensorSelection_clearsCalibration() {
        val coordinator = PostureAnalysisCoordinator()

        coordinator.onNetworkStatus(
            receivedAtEpochMs = 1_000L,
            status = networkStatus(sensorIds = listOf(3, 7)),
        )
        coordinator.onSample(
            receivedAtEpochMs = 1_100L,
            sample = sample(sensorId = 3, seq = 1, ax = 0, ay = 0, az = 1000),
        )
        coordinator.onSample(
            receivedAtEpochMs = 1_100L,
            sample = sample(sensorId = 7, seq = 1, ax = 0, ay = 0, az = 1000),
        )
        val calibration = coordinator.captureCalibration(nowEpochMs = 1_200L)

        assertTrue(calibration.success)

        val updatedSnapshot = coordinator.setUpperBackSensor(
            sensorId = 3,
            nowEpochMs = 1_300L,
        )

        assertNull(updatedSnapshot.sittingCalibration)
        assertEquals(
            "Upper back sensor selection updated. Recalibrate sitting posture.",
            updatedSnapshot.calibrationMessage,
        )
    }

    @Test
    fun manualSensorSelection_doesNotAssignSameSensorToBothBackRoles() {
        val coordinator = PostureAnalysisCoordinator()

        coordinator.onNetworkStatus(
            receivedAtEpochMs = 1_000L,
            status = networkStatus(sensorIds = listOf(3, 7)),
        )
        coordinator.setLowerBackSensor(
            sensorId = 3,
            nowEpochMs = 1_100L,
        )

        val snapshot = coordinator.setUpperBackSensor(
            sensorId = 3,
            nowEpochMs = 1_200L,
        )

        assertEquals(
            listOf(
                SensorAssignment(sensorId = 3, placement = SensorPlacement.LOWER_BACK),
                SensorAssignment(sensorId = 7, placement = SensorPlacement.UPPER_BACK),
            ),
            snapshot.sensorAssignments,
        )
    }

    private fun sample(
        sensorId: Int,
        seq: Int,
        ax: Int = 1,
        ay: Int = 2,
        az: Int = 3,
    ): ImuSample =
        ImuSample(
            version = 1,
            sensorId = sensorId,
            seq = seq,
            timestampMs = seq.toLong() * 10,
            ax = ax,
            ay = ay,
            az = az,
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
