package com.example.seniordesignmobileapp.model

enum class BleConnectionPhase(val label: String) {
    IDLE("Idle"),
    WAITING_FOR_PERMISSIONS("Waiting for permissions"),
    BLUETOOTH_UNAVAILABLE("Bluetooth unavailable"),
    BLUETOOTH_DISABLED("Bluetooth disabled"),
    SCANNING("Scanning"),
    CONNECTING("Connecting"),
    REQUESTING_MTU("Requesting MTU"),
    DISCOVERING_SERVICES("Discovering services"),
    SUBSCRIBING("Subscribing"),
    READING_STATUS("Reading status"),
    STREAMING("Streaming"),
    RECOVERING("Recovering"),
    DISCONNECTED("Disconnected"),
}

data class AggregatorUiState(
    val connectionState: String = "Waiting for Bluetooth permissions",
    val connectionPhase: BleConnectionPhase = BleConnectionPhase.WAITING_FOR_PERMISSIONS,
    val deviceName: String = "BLE-Aggregator",
    val isScanning: Boolean = false,
    val isConnected: Boolean = false,
    val latestSample: ImuSample? = null,
    val networkStatus: NetworkStatus? = null,
    val lastSampleHex: String? = null,
    val lastStatusHex: String? = null,
    val errorMessage: String? = null,
    val lastFailureReason: String? = null,
    val reconnectCount: Int = 0,
    val recentEvents: List<String> = emptyList(),
)
