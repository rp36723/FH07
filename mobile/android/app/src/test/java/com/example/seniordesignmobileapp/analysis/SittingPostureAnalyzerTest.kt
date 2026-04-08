package com.example.seniordesignmobileapp.analysis

import com.example.seniordesignmobileapp.model.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SittingPostureAnalyzerTest {
    private val analyzer = SittingPostureAnalyzer()
    private val config = AnalysisConfig(
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
    )

    @Test
    fun analyze_requiresCalibrationBeforeReturningScore() {
        val result = analyzer.analyze(
            input = inputWindow(
                lowerBackSamples = listOf(sample(sensorId = 1, ax = 0, ay = 0, az = 1000)),
                upperBackSamples = listOf(sample(sensorId = 2, ax = 0, ay = 0, az = 1000)),
                calibration = null,
            ),
            config = config,
        )

        assertEquals(PostureState.INCOMPLETE, result.postureState)
        assertEquals(0f, result.score)
        assertTrue(result.alerts.any { it.code == PostureAlertCode.CALIBRATION_REQUIRED })
    }

    @Test
    fun analyze_returnsHighScoreWhenCurrentBendMatchesCalibration() {
        val calibration = SittingCalibration(
            capturedAtEpochMs = 1_000L,
            upperBackSensorId = 2,
            lowerBackSensorId = 1,
            upperBackPitchDeg = 0f,
            lowerBackPitchDeg = 0f,
            bendAngleDeg = 0f,
        )

        val result = analyzer.analyze(
            input = inputWindow(
                lowerBackSamples = List(20) { sample(sensorId = 1, ax = 0, ay = 0, az = 1000) },
                upperBackSamples = List(20) { sample(sensorId = 2, ax = 0, ay = 0, az = 1000) },
                calibration = calibration,
            ),
            config = config,
        )

        assertEquals(PostureState.GOOD, result.postureState)
        assertEquals(100f, result.score)
        assertEquals(0f, result.sittingDetails?.bendDeltaFromBaselineDeg)
    }

    @Test
    fun analyze_penalizesScoreAsUpperBackBendMovesAwayFromCalibration() {
        val calibration = SittingCalibration(
            capturedAtEpochMs = 1_000L,
            upperBackSensorId = 2,
            lowerBackSensorId = 1,
            upperBackPitchDeg = 0f,
            lowerBackPitchDeg = 0f,
            bendAngleDeg = 0f,
        )

        val result = analyzer.analyze(
            input = inputWindow(
                lowerBackSamples = List(20) { sample(sensorId = 1, ax = 0, ay = 0, az = 1000) },
                upperBackSamples = List(20) { sample(sensorId = 2, ax = 259, ay = 0, az = 966) },
                calibration = calibration,
            ),
            config = config,
        )

        assertEquals(PostureState.WARNING, result.postureState)
        assertTrue(result.score in 45f..55f)
        assertTrue((result.sittingDetails?.bendDeltaFromBaselineDeg ?: 0f) in 14f..16f)
    }

    private fun inputWindow(
        lowerBackSamples: List<ImuSample>,
        upperBackSamples: List<ImuSample>,
        calibration: SittingCalibration?,
    ): AnalysisInputWindow =
        AnalysisInputWindow(
            windowStartEpochMs = 0L,
            windowEndEpochMs = 1_000L,
            lookbackMs = 0L,
            samplesBySensor = mapOf(
                1 to lowerBackSamples,
                2 to upperBackSamples,
            ),
            networkStatus = null,
            sensorAssignments = config.expectedSensors,
            expectedSensors = setOf(1, 2),
            availableSensors = setOf(1, 2),
            missingSensors = emptySet(),
            sittingCalibration = calibration,
        )

    private fun sample(
        sensorId: Int,
        ax: Int,
        ay: Int,
        az: Int,
    ): ImuSample =
        ImuSample(
            version = 1,
            sensorId = sensorId,
            seq = 1,
            timestampMs = 0L,
            ax = ax,
            ay = ay,
            az = az,
            gx = 0,
            gy = 0,
            gz = 0,
        )
}
