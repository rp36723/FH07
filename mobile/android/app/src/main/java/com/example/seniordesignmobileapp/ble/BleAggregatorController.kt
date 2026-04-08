package com.example.seniordesignmobileapp.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.seniordesignmobileapp.model.AggregatorUiState
import com.example.seniordesignmobileapp.model.BleConnectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

val REQUIRED_BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val GATT_CONNECTION_TIMEOUT_STATUS = 8
private const val TARGET_MTU = 64
private const val STATUS_REFRESH_MS = 1_500L
private const val RECONNECT_DELAY_MS = 1_000L
private const val TIMEOUT_RECONNECT_DELAY_MS = 3_000L
private const val CONNECTION_TIMEOUT_MS = 12_000L
private const val MAX_EVENT_LOG_ENTRIES = 10
private const val TAG = "BleAggregator"

class BleAggregatorController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _uiState = MutableStateFlow(AggregatorUiState())

    val uiState: StateFlow<AggregatorUiState> = _uiState.asStateFlow()

    private var isRunning = false
    private var isScanning = false
    private var currentGatt: BluetoothGatt? = null
    private var sampleStreamCharacteristic: BluetoothGattCharacteristic? = null
    private var networkStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var statusPollingJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionWatchdogJob: Job? = null
    private var statusReadInFlight = false

    fun start() {
        isRunning = true
        cancelReconnect()

        if (!hasRequiredBlePermissions(appContext)) {
            _uiState.update {
                it.copy(
                    connectionState = "Waiting for Bluetooth permissions",
                    connectionPhase = BleConnectionPhase.WAITING_FOR_PERMISSIONS,
                    isScanning = false,
                    isConnected = false,
                )
            }
            return
        }

        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            _uiState.update {
                it.copy(
                    connectionState = "Bluetooth is unavailable on this device",
                    connectionPhase = BleConnectionPhase.BLUETOOTH_UNAVAILABLE,
                    isScanning = false,
                    isConnected = false,
                    errorMessage = "No Bluetooth adapter was found.",
                )
            }
            logEvent("Bluetooth adapter unavailable")
            return
        }

        if (!adapter.isEnabled) {
            _uiState.update {
                it.copy(
                    connectionState = "Enable Bluetooth to scan for BLE-Aggregator",
                    connectionPhase = BleConnectionPhase.BLUETOOTH_DISABLED,
                    isScanning = false,
                    isConnected = false,
                    errorMessage = "Bluetooth is turned off.",
                )
            }
            logEvent("Bluetooth is disabled")
            return
        }

        if (currentGatt != null || isScanning) {
            return
        }

        startScan()
    }

    fun restart() {
        stop()
        start()
    }

    fun stop() {
        isRunning = false
        cancelReconnect()
        stopScan()
        stopStatusPolling()
        stopConnectionWatchdog()
        closeGatt()
        statusReadInFlight = false
        _uiState.update {
            it.copy(
                connectionState = "Stopped",
                connectionPhase = BleConnectionPhase.IDLE,
                isScanning = false,
                isConnected = false,
            )
        }
        logEvent("Controller stopped")
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasRequiredBlePermissions(appContext)) {
            return
        }

        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            _uiState.update {
                it.copy(
                    connectionState = "Bluetooth LE scanner is unavailable",
                    errorMessage = "Unable to start BLE scanning.",
                )
            }
            return
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setDeviceName(AGGREGATOR_DEVICE_NAME)
                .setServiceUuid(ParcelUuid(GATT_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            cancelReconnect()
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            _uiState.update {
                it.copy(
                    connectionState = "Scanning for $AGGREGATOR_DEVICE_NAME",
                    connectionPhase = BleConnectionPhase.SCANNING,
                    deviceName = AGGREGATOR_DEVICE_NAME,
                    isScanning = true,
                    isConnected = false,
                    errorMessage = null,
                )
            }
            logEvent("Scan started for $AGGREGATOR_DEVICE_NAME")
        } catch (securityError: SecurityException) {
            _uiState.update {
                it.copy(
                    connectionState = "Bluetooth permissions are missing",
                    connectionPhase = BleConnectionPhase.WAITING_FOR_PERMISSIONS,
                    errorMessage = securityError.message,
                )
            }
            logEvent("Scan blocked by missing permissions")
        } catch (stateError: IllegalStateException) {
            _uiState.update {
                it.copy(
                    connectionState = "BLE scan could not start",
                    connectionPhase = BleConnectionPhase.RECOVERING,
                    errorMessage = stateError.message,
                )
            }
            logEvent("Scan failed to start: ${stateError.message ?: "IllegalStateException"}")
            scheduleReconnect("Retrying BLE scan")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: return
        if (!isScanning) {
            return
        }

        try {
            scanner.stopScan(scanCallback)
        } catch (_: IllegalStateException) {
        }

        isScanning = false
        _uiState.update { it.copy(isScanning = false) }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!hasRequiredBlePermissions(appContext) || currentGatt != null) {
            return
        }

        cancelReconnect()
        stopConnectionWatchdog()
        val name = device.name ?: AGGREGATOR_DEVICE_NAME
        _uiState.update {
            it.copy(
                connectionState = "Connecting to $name",
                connectionPhase = BleConnectionPhase.CONNECTING,
                deviceName = name,
                isScanning = false,
                isConnected = false,
                errorMessage = null,
            )
        }
        logEvent("Connecting to $name")
        currentGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        startConnectionWatchdog("Connecting to $name timed out")
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        sampleStreamCharacteristic = null
        networkStatusCharacteristic = null
        stopConnectionWatchdog()

        currentGatt?.let { gatt ->
            try {
                gatt.disconnect()
            } catch (_: IllegalStateException) {
            }
            gatt.close()
        }

        currentGatt = null
    }

    @SuppressLint("MissingPermission")
    private fun requestNetworkStatusRead() {
        val gatt = currentGatt ?: return
        val characteristic = networkStatusCharacteristic ?: return
        if (statusReadInFlight) {
            return
        }

        _uiState.update {
            it.copy(connectionPhase = BleConnectionPhase.READING_STATUS)
        }
        logEvent("Reading network_status")
        statusReadInFlight = true
        val started = gatt.readCharacteristic(characteristic)
        if (!started) {
            statusReadInFlight = false
            logEvent("network_status read request was rejected by Android")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableSampleNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            _uiState.update {
                it.copy(errorMessage = "Failed to enable notifications for sample_stream.")
            }
            logEvent("setCharacteristicNotification failed")
            recoverConnection("Unable to enable sample notifications")
            return
        }

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            _uiState.update {
                it.copy(errorMessage = "sample_stream is missing the CCC descriptor.")
            }
            logEvent("sample_stream CCC descriptor missing")
            recoverConnection("sample_stream CCC descriptor was missing")
            return
        }

        _uiState.update {
            it.copy(connectionPhase = BleConnectionPhase.SUBSCRIBING)
        }
        logEvent("Writing CCCD to subscribe to sample_stream")

        val status = gatt.writeDescriptor(
            descriptor,
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
        )
        if (status != BluetoothStatusCodes.SUCCESS) {
            _uiState.update {
                it.copy(errorMessage = "Failed to subscribe to sample_stream (code $status).")
            }
            logEvent("CCCD write request rejected with code $status")
            recoverConnection("Unable to subscribe to sample_stream")
        }
    }

    private fun startStatusPolling() {
        stopStatusPolling()
        statusPollingJob = scope.launch {
            while (isActive && isRunning && currentGatt != null) {
                delay(STATUS_REFRESH_MS)
                requestNetworkStatusRead()
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private fun startConnectionWatchdog(timeoutMessage: String) {
        stopConnectionWatchdog()
        connectionWatchdogJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (isRunning && currentGatt != null) {
                recoverConnection(timeoutMessage)
            }
        }
    }

    private fun stopConnectionWatchdog() {
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = null
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun scheduleReconnect(
        message: String,
        delayMs: Long = RECONNECT_DELAY_MS,
    ) {
        if (!isRunning) {
            return
        }

        cancelReconnect()
        reconnectJob = scope.launch {
            _uiState.update {
                it.copy(
                    connectionState = message,
                    connectionPhase = BleConnectionPhase.RECOVERING,
                    isScanning = false,
                    isConnected = false,
                    reconnectCount = it.reconnectCount + 1,
                )
            }
            logEvent("$message in ${delayMs}ms")
            delay(delayMs)
            if (isRunning && currentGatt == null && !isScanning) {
                startScan()
            }
        }
    }

    private fun recoverConnection(
        message: String,
        reconnectDelayMs: Long = RECONNECT_DELAY_MS,
    ) {
        stopScan()
        stopStatusPolling()
        stopConnectionWatchdog()
        statusReadInFlight = false
        sampleStreamCharacteristic = null
        networkStatusCharacteristic = null

        currentGatt?.let { gatt ->
            try {
                gatt.disconnect()
            } catch (_: IllegalStateException) {
            }
            gatt.close()
        }
        currentGatt = null

        _uiState.update {
            it.copy(
                connectionState = message,
                connectionPhase = BleConnectionPhase.RECOVERING,
                isScanning = false,
                isConnected = false,
                lastFailureReason = message,
            )
        }
        logEvent("Recovering connection: $message")

        scheduleReconnect(
            message = "Reconnecting to $AGGREGATOR_DEVICE_NAME",
            delayMs = reconnectDelayMs,
        )
    }

    private fun handleDisconnect(message: String, restartScan: Boolean) {
        stopStatusPolling()
        stopConnectionWatchdog()
        statusReadInFlight = false
        sampleStreamCharacteristic = null
        networkStatusCharacteristic = null
        currentGatt?.close()
        currentGatt = null

        _uiState.update {
            it.copy(
                connectionState = message,
                connectionPhase = BleConnectionPhase.DISCONNECTED,
                isScanning = false,
                isConnected = false,
            )
        }
        logEvent(message)

        if (restartScan && isRunning) {
            scheduleReconnect("Reconnecting to $AGGREGATOR_DEVICE_NAME")
        }
    }

    private fun logEvent(message: String) {
        _uiState.update { state ->
            val nextEvents = (state.recentEvents + message).takeLast(MAX_EVENT_LOG_ENTRIES)
            state.copy(recentEvents = nextEvents)
        }
        Log.i(TAG, message)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.scanRecord?.deviceName ?: result.device.name
            val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == GATT_SERVICE_UUID } == true
            if (deviceName != AGGREGATOR_DEVICE_NAME && !hasService) {
                return
            }

            logEvent(
                "Found ${deviceName ?: AGGREGATOR_DEVICE_NAME} rssi=${result.rssi} address=${result.device.address}"
            )
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            _uiState.update {
                it.copy(
                    connectionState = "BLE scan failed",
                    connectionPhase = BleConnectionPhase.RECOVERING,
                    isScanning = false,
                    errorMessage = "Android scan error code: $errorCode",
                    lastFailureReason = "Android scan error code: $errorCode",
                )
            }
            logEvent("Scan failed with code $errorCode")
            scheduleReconnect("Retrying BLE scan")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != currentGatt) {
                gatt.close()
                return
            }

            logEvent("Connection state changed: status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val errorMessage = describeGattConnectionStatus(status)
                recoverConnection(
                    message = errorMessage,
                    reconnectDelayMs = reconnectDelayForStatus(status),
                )
                _uiState.update { it.copy(errorMessage = errorMessage) }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _uiState.update {
                        it.copy(
                            connectionState = "Connected to ${it.deviceName}, requesting MTU",
                            connectionPhase = BleConnectionPhase.REQUESTING_MTU,
                            isConnected = true,
                            errorMessage = null,
                        )
                    }
                    logEvent("Connected, requesting MTU $TARGET_MTU")

                    startConnectionWatchdog("Service discovery timed out")
                    if (!gatt.requestMtu(TARGET_MTU)) {
                        logEvent("MTU request rejected, falling back to service discovery")
                        gatt.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> handleDisconnect(
                    message = "Disconnected from ${_uiState.value.deviceName}",
                    restartScan = true,
                )
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt != currentGatt) {
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                _uiState.update {
                    it.copy(
                        connectionState = "MTU $mtu negotiated, discovering services",
                        connectionPhase = BleConnectionPhase.DISCOVERING_SERVICES,
                    )
                }
                logEvent("MTU negotiated: $mtu")
            } else {
                logEvent("MTU change failed with status $status, discovering services anyway")
            }

            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt != currentGatt) {
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleDisconnect("Service discovery failed", restartScan = true)
                _uiState.update { it.copy(errorMessage = "GATT service discovery status: $status") }
                logEvent("Service discovery failed with status $status")
                return
            }

            logEvent("Services discovered")
            val service = gatt.getService(GATT_SERVICE_UUID)
            val sampleCharacteristic = service?.getCharacteristic(SAMPLE_STREAM_UUID)
            val statusCharacteristic = service?.getCharacteristic(NETWORK_STATUS_UUID)
            if (service == null || sampleCharacteristic == null || statusCharacteristic == null) {
                handleDisconnect("Custom BLE service not found", restartScan = true)
                _uiState.update {
                    it.copy(errorMessage = "Aggregator GATT service is missing expected characteristics.")
                }
                return
            }

            sampleStreamCharacteristic = sampleCharacteristic
            networkStatusCharacteristic = statusCharacteristic
            _uiState.update {
                it.copy(
                    connectionState = "Connected to ${it.deviceName}",
                    connectionPhase = BleConnectionPhase.SUBSCRIBING,
                    isConnected = true,
                    errorMessage = null,
                )
            }

            startConnectionWatchdog("Notification setup timed out")
            enableSampleNotifications(gatt, sampleCharacteristic)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (gatt != currentGatt || characteristic.uuid != NETWORK_STATUS_UUID) {
                return
            }

            statusReadInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _uiState.update {
                    it.copy(
                        connectionPhase = BleConnectionPhase.STREAMING,
                        errorMessage = "network_status read failed with status $status",
                        lastFailureReason = "network_status read failed with status $status",
                    )
                }
                logEvent("network_status read failed with status $status")
                return
            }

            val decoded = decodeNetworkStatus(value)
            _uiState.update {
                it.copy(
                    connectionPhase = if (it.isConnected) {
                        BleConnectionPhase.STREAMING
                    } else {
                        it.connectionPhase
                    },
                    networkStatus = decoded ?: it.networkStatus,
                    lastStatusHex = value.toHexString(),
                    errorMessage = if (decoded == null) {
                        "Failed to decode network_status (${value.size} bytes)."
                    } else {
                        null
                    },
                    lastFailureReason = if (decoded == null) {
                        "Failed to decode network_status (${value.size} bytes)."
                    } else {
                        it.lastFailureReason
                    },
                )
            }
            logEvent(
                if (decoded == null) {
                    "network_status decode failed (${value.size} bytes)"
                } else {
                    "network_status updated: ${decoded.activeSensorCount} sensors"
                }
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt != currentGatt || characteristic.uuid != SAMPLE_STREAM_UUID) {
                return
            }

            val decoded = decodeImuSample(value)
            _uiState.update {
                it.copy(
                    connectionPhase = BleConnectionPhase.STREAMING,
                    latestSample = decoded ?: it.latestSample,
                    lastSampleHex = value.toHexString(),
                    errorMessage = if (decoded == null) {
                        "Failed to decode sample_stream packet (${value.size} bytes)."
                    } else {
                        null
                    },
                    lastFailureReason = if (decoded == null) {
                        "Failed to decode sample_stream packet (${value.size} bytes)."
                    } else {
                        it.lastFailureReason
                    },
                )
            }
            logEvent(
                if (decoded == null) {
                    "sample_stream decode failed (${value.size} bytes)"
                } else {
                    "Sample sensor=${decoded.sensorId} seq=${decoded.seq}"
                }
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (gatt != currentGatt || descriptor.characteristic.uuid != SAMPLE_STREAM_UUID) {
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                stopConnectionWatchdog()
            }
            _uiState.update {
                it.copy(
                    connectionState = if (status == BluetoothGatt.GATT_SUCCESS) {
                        "Streaming samples from ${it.deviceName}"
                    } else {
                        "Connected to ${it.deviceName}"
                    },
                    connectionPhase = if (status == BluetoothGatt.GATT_SUCCESS) {
                        BleConnectionPhase.STREAMING
                    } else {
                        BleConnectionPhase.SUBSCRIBING
                    },
                    errorMessage = if (status == BluetoothGatt.GATT_SUCCESS) {
                        it.errorMessage
                    } else {
                        "Failed to subscribe to sample_stream (status $status)."
                    },
                    lastFailureReason = if (status == BluetoothGatt.GATT_SUCCESS) {
                        it.lastFailureReason
                    } else {
                        "Failed to subscribe to sample_stream (status $status)."
                    },
                )
            }
            logEvent(
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    "Subscribed to sample_stream"
                } else {
                    "Descriptor write failed with status $status"
                }
            )

            if (status == BluetoothGatt.GATT_SUCCESS) {
                requestNetworkStatusRead()
                startStatusPolling()
            } else {
                recoverConnection("Retrying after subscription failure")
            }
        }
    }
}

fun hasRequiredBlePermissions(context: Context): Boolean =
    REQUIRED_BLE_PERMISSIONS.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

private fun reconnectDelayForStatus(status: Int): Long =
    if (status == GATT_CONNECTION_TIMEOUT_STATUS) {
        TIMEOUT_RECONNECT_DELAY_MS
    } else {
        RECONNECT_DELAY_MS
    }

private fun describeGattConnectionStatus(status: Int): String =
    if (status == GATT_CONNECTION_TIMEOUT_STATUS) {
        "GATT connection timed out (status 8)"
    } else {
        "GATT connection failed (status $status)"
    }
