package com.example.seniordesignmobileapp.model

import com.example.seniordesignmobileapp.analysis.ActivityMode
import com.example.seniordesignmobileapp.analysis.AnalysisConfig
import com.example.seniordesignmobileapp.analysis.AnalysisWindowSummary
import com.example.seniordesignmobileapp.analysis.PostureAnalysisResult
import com.example.seniordesignmobileapp.analysis.SensorAssignment
import com.example.seniordesignmobileapp.analysis.SittingCalibration

data class AnalysisUiState(
    val config: AnalysisConfig = AnalysisConfig.sittingDefault(),
    val sensorAssignments: List<SensorAssignment> = emptyList(),
    val availableSensorIds: List<Int> = emptyList(),
    val manualUpperBackSensorId: Int? = null,
    val manualLowerBackSensorId: Int? = null,
    val expectedSensors: Set<Int> = emptySet(),
    val expectedSensorsInferred: Boolean = true,
    val sittingCalibration: SittingCalibration? = null,
    val windowSummary: AnalysisWindowSummary? = null,
    val latestResult: PostureAnalysisResult? = null,
    val lastUpdatedAtElapsedMs: Long? = null,
    val calibrationMessage: String? = null,
    val statusMessage: String = "Waiting for analysis data.",
) {
    val activityMode: ActivityMode
        get() = config.activityMode
}
