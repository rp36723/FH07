package com.example.seniordesignmobileapp.model

data class ImuSample(
    val version: Int,
    val sensorId: Int,
    val seq: Int,
    val timestampMs: Long,
    val ax: Int,
    val ay: Int,
    val az: Int,
    val gx: Int,
    val gy: Int,
    val gz: Int,
)

data class ActiveSensorStatus(
    val sensorId: Int,
    val seq: Int,
    val ageMs: Int,
)

data class NetworkStatus(
    val version: Int,
    val uptimeMs: Long,
    val activeSensorCount: Int,
    val sensors: List<ActiveSensorStatus>,
)
