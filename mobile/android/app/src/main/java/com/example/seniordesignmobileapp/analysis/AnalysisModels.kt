package com.example.seniordesignmobileapp.analysis

import com.example.seniordesignmobileapp.model.ImuSample
import com.example.seniordesignmobileapp.model.NetworkStatus

enum class ActivityMode {
    SITTING,
}

enum class SensorPlacement {
    HEAD,
    CHEST,
    UPPER_BACK,
    LOWER_BACK,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_THIGH,
    RIGHT_THIGH,
    UNKNOWN,
}

data class SensorAssignment(
    val sensorId: Int,
    val placement: SensorPlacement,
    val required: Boolean = true,
)

sealed interface WindowSpec {
    data class FixedDuration(
        val durationMs: Long,
    ) : WindowSpec

    data class DurationRange(
        val minDurationMs: Long,
        val maxDurationMs: Long,
    ) : WindowSpec
}

data class AnalysisConfig(
    val activityMode: ActivityMode,
    val expectedSensors: List<SensorAssignment>,
    val windowSpec: WindowSpec,
    val historyLookbackMs: Long,
    val allowPartialAnalysis: Boolean,
    val warningScoreThreshold: Float,
    val poorScoreThreshold: Float,
) {
    init {
        require(historyLookbackMs >= 0) { "historyLookbackMs must be non-negative." }
        require(warningScoreThreshold in 0f..100f) { "warningScoreThreshold must be between 0 and 100." }
        require(poorScoreThreshold in 0f..100f) { "poorScoreThreshold must be between 0 and 100." }
    }

    companion object {
        fun sittingDefault(): AnalysisConfig =
            AnalysisConfig(
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
            )
    }
}

data class AnalysisInputWindow(
    val windowStartEpochMs: Long,
    val windowEndEpochMs: Long,
    val lookbackMs: Long,
    val samplesBySensor: Map<Int, List<ImuSample>>,
    val networkStatus: NetworkStatus?,
    val expectedSensors: Set<Int>,
    val availableSensors: Set<Int>,
    val missingSensors: Set<Int>,
)

data class AnalysisWindowSummary(
    val windowStartEpochMs: Long,
    val windowEndEpochMs: Long,
    val lookbackMs: Long,
    val sampleCountsBySensor: Map<Int, Int>,
    val availableSensors: Set<Int>,
    val missingSensors: Set<Int>,
)

enum class PostureState {
    GOOD,
    WARNING,
    POOR,
    INCOMPLETE,
}

enum class PostureAlertCode {
    SENSOR_MISSING,
    LOW_CONFIDENCE,
    INSUFFICIENT_WINDOW,
    ANALYSIS_SKIPPED,
}

data class PostureAlert(
    val code: PostureAlertCode,
    val message: String,
)

data class PostureAnalysisResult(
    val timestampEpochMs: Long,
    val score: Float,
    val postureState: PostureState,
    val confidence: Float,
    val contributingSensors: Set<Int>,
    val missingSensors: Set<Int>,
    val lookbackMs: Long,
    val alerts: List<PostureAlert>,
)
