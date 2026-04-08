package com.example.seniordesignmobileapp.analysis

interface PostureAnalyzer {
    fun analyze(
        input: AnalysisInputWindow,
        config: AnalysisConfig,
    ): PostureAnalysisResult
}

class PlaceholderPostureAnalyzer : PostureAnalyzer {
    override fun analyze(
        input: AnalysisInputWindow,
        config: AnalysisConfig,
    ): PostureAnalysisResult {
        val hasEnoughSensors = input.availableSensors.isNotEmpty() &&
            (input.missingSensors.isEmpty() || config.allowPartialAnalysis)
        val state = if (hasEnoughSensors) {
            PostureState.INCOMPLETE
        } else {
            PostureState.INCOMPLETE
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
            add(
                PostureAlert(
                    code = PostureAlertCode.ANALYSIS_SKIPPED,
                    message = "Posture scoring is not implemented yet.",
                )
            )
        }

        return PostureAnalysisResult(
            timestampEpochMs = input.windowEndEpochMs,
            score = 0f,
            postureState = state,
            confidence = if (hasEnoughSensors) 0.1f else 0f,
            contributingSensors = input.availableSensors,
            missingSensors = input.missingSensors,
            lookbackMs = input.lookbackMs,
            alerts = alerts,
        )
    }
}
