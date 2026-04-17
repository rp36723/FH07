package com.example.seniordesignmobileapp.model

import com.example.seniordesignmobileapp.analysis.SensorPlacement

data class ModelingUiState(
    val nodes: List<ModeledNodeUiState> = emptyList(),
    val lastUpdatedAtElapsedMs: Long? = null,
    val statusMessage: String = "Waiting for sensor samples to render the modeling scene.",
)

data class ModeledNodeUiState(
    val sensorId: Int,
    val placement: SensorPlacement? = null,
    val isLiveInNetworkStatus: Boolean = false,
    val seq: Int? = null,
    val pitchDeg: Float? = null,
    val rollDeg: Float? = null,
    val gravityVector: ModeledGravityVector? = null,
    val lastSampleReceivedAtElapsedMs: Long? = null,
)

data class ModeledGravityVector(
    val x: Float,
    val y: Float,
    val z: Float,
)
