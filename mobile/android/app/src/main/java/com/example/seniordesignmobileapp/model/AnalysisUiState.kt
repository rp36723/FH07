package com.example.seniordesignmobileapp.model

import com.example.seniordesignmobileapp.analysis.ActivityMode
import com.example.seniordesignmobileapp.analysis.AnalysisConfig
import com.example.seniordesignmobileapp.analysis.AnalysisWindowSummary
import com.example.seniordesignmobileapp.analysis.PostureAnalysisResult

data class AnalysisUiState(
    val config: AnalysisConfig = AnalysisConfig.sittingDefault(),
    val expectedSensors: Set<Int> = emptySet(),
    val expectedSensorsInferred: Boolean = true,
    val windowSummary: AnalysisWindowSummary? = null,
    val latestResult: PostureAnalysisResult? = null,
    val lastUpdatedAtElapsedMs: Long? = null,
    val statusMessage: String = "Waiting for analysis data.",
) {
    val activityMode: ActivityMode
        get() = config.activityMode
}
