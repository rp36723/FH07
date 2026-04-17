import Foundation
import CoreBluetooth
import Combine
import os.log

private let TAG = "BleAggregator"
private let STATUS_REFRESH_MS: Int64 = 1_500
private let RECONNECT_DELAY_MS: Int64 = 1_000
private let TIMEOUT_RECONNECT_DELAY_MS: Int64 = 3_000
private let CONNECTION_TIMEOUT_MS: Int64 = 12_000
private let MAX_EVENT_LOG_ENTRIES = 10

func elapsedRealtimeMs() -> Int64 {
    // Monotonic system uptime in milliseconds (analog of SystemClock.elapsedRealtime on Android).
    Int64(ProcessInfo.processInfo.systemUptime * 1000)
}

final class BleAggregatorController: NSObject {
    @Published private(set) var uiState = AggregatorUiState()

    private var centralManager: CBCentralManager!
    private let queue = DispatchQueue(label: "ble.aggregator.controller")
    private let logger = Logger(subsystem: "com.example.seniordesignmobileapp", category: TAG)

    private var isRunning = false
    private var isScanning = false
    private var currentPeripheral: CBPeripheral?
    private var sampleStreamCharacteristic: CBCharacteristic?
    private var networkStatusCharacteristic: CBCharacteristic?
    private var statusPollingTimer: DispatchSourceTimer?
    private var reconnectWorkItem: DispatchWorkItem?
    private var connectionWatchdogWorkItem: DispatchWorkItem?
    private var statusReadInFlight = false

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: queue)
    }

    func start() {
        queue.async { [weak self] in
            self?.internalStart()
        }
    }

    func restart() {
        queue.async { [weak self] in
            self?.internalStop()
            self?.internalStart()
        }
    }

    func stop() {
        queue.async { [weak self] in
            self?.internalStop()
        }
    }

    private func internalStart() {
        isRunning = true
        cancelReconnect()

        switch centralManager.state {
        case .poweredOn:
            break
        case .unauthorized:
            updateState { state in
                state.connectionState = "Waiting for Bluetooth permissions"
                state.connectionPhase = .waitingForPermissions
                state.isScanning = false
                state.isConnected = false
            }
            return
        case .unsupported:
            updateState { state in
                state.connectionState = "Bluetooth is unavailable on this device"
                state.connectionPhase = .bluetoothUnavailable
                state.isScanning = false
                state.isConnected = false
                state.errorMessage = "No Bluetooth adapter was found."
            }
            logEvent("Bluetooth adapter unavailable")
            return
        case .poweredOff:
            updateState { state in
                state.connectionState = "Enable Bluetooth to scan for BLE-Aggregator"
                state.connectionPhase = .bluetoothDisabled
                state.isScanning = false
                state.isConnected = false
                state.errorMessage = "Bluetooth is turned off."
            }
            logEvent("Bluetooth is disabled")
            return
        case .resetting, .unknown:
            updateState { state in
                state.connectionState = "Waiting for Bluetooth"
                state.connectionPhase = .recovering
            }
            return
        @unknown default:
            return
        }

        if currentPeripheral != nil || isScanning {
            return
        }

        startScan()
    }

    private func internalStop() {
        isRunning = false
        cancelReconnect()
        stopScan()
        stopStatusPolling()
        stopConnectionWatchdog()
        closeConnection()
        statusReadInFlight = false
        updateState { state in
            state.connectionState = "Stopped"
            state.connectionPhase = .idle
            state.isScanning = false
            state.isConnected = false
        }
        logEvent("Controller stopped")
    }

    private func startScan() {
        guard centralManager.state == .poweredOn else { return }

        cancelReconnect()
        centralManager.scanForPeripherals(
            withServices: [GATT_SERVICE_UUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        isScanning = true
        updateState { state in
            state.connectionState = "Scanning for \(AGGREGATOR_DEVICE_NAME)"
            state.connectionPhase = .scanning
            state.deviceName = AGGREGATOR_DEVICE_NAME
            state.isScanning = true
            state.isConnected = false
            state.errorMessage = nil
        }
        logEvent("Scan started for \(AGGREGATOR_DEVICE_NAME)")
    }

    private func stopScan() {
        if !isScanning { return }
        centralManager.stopScan()
        isScanning = false
        updateState { state in
            state.isScanning = false
        }
    }

    private func connect(peripheral: CBPeripheral) {
        if currentPeripheral != nil { return }

        cancelReconnect()
        stopConnectionWatchdog()
        let name = peripheral.name ?? AGGREGATOR_DEVICE_NAME
        currentPeripheral = peripheral
        peripheral.delegate = self
        updateState { state in
            state.connectionState = "Connecting to \(name)"
            state.connectionPhase = .connecting
            state.deviceName = name
            state.isScanning = false
            state.isConnected = false
            state.errorMessage = nil
        }
        logEvent("Connecting to \(name)")
        centralManager.connect(peripheral, options: nil)
        startConnectionWatchdog(timeoutMessage: "Connecting to \(name) timed out")
    }

    private func closeConnection() {
        sampleStreamCharacteristic = nil
        networkStatusCharacteristic = nil
        stopConnectionWatchdog()

        if let peripheral = currentPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        currentPeripheral = nil
    }

    private func requestNetworkStatusRead() {
        guard let peripheral = currentPeripheral,
              let characteristic = networkStatusCharacteristic else { return }
        if statusReadInFlight { return }

        updateState { state in
            state.connectionPhase = .readingStatus
        }
        logEvent("Reading network_status")
        statusReadInFlight = true
        peripheral.readValue(for: characteristic)
    }

    private func enableSampleNotifications(peripheral: CBPeripheral, characteristic: CBCharacteristic) {
        updateState { state in
            state.connectionPhase = .subscribing
        }
        logEvent("Writing CCCD to subscribe to sample_stream")
        peripheral.setNotifyValue(true, for: characteristic)
    }

    private func startStatusPolling() {
        stopStatusPolling()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(Int(STATUS_REFRESH_MS)), repeating: .milliseconds(Int(STATUS_REFRESH_MS)))
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            if self.isRunning && self.currentPeripheral != nil {
                self.requestNetworkStatusRead()
            }
        }
        timer.resume()
        statusPollingTimer = timer
    }

    private func stopStatusPolling() {
        statusPollingTimer?.cancel()
        statusPollingTimer = nil
    }

    private func startConnectionWatchdog(timeoutMessage: String) {
        stopConnectionWatchdog()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            if self.isRunning && self.currentPeripheral != nil {
                self.recoverConnection(message: timeoutMessage)
            }
        }
        connectionWatchdogWorkItem = workItem
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(CONNECTION_TIMEOUT_MS)), execute: workItem)
    }

    private func stopConnectionWatchdog() {
        connectionWatchdogWorkItem?.cancel()
        connectionWatchdogWorkItem = nil
    }

    private func cancelReconnect() {
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
    }

    private func scheduleReconnect(message: String, delayMs: Int64 = RECONNECT_DELAY_MS) {
        if !isRunning { return }

        cancelReconnect()
        updateState { state in
            state.connectionState = message
            state.connectionPhase = .recovering
            state.isScanning = false
            state.isConnected = false
            state.reconnectCount += 1
        }
        logEvent("\(message) in \(delayMs)ms")

        let workItem = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            if self.isRunning && self.currentPeripheral == nil && !self.isScanning {
                self.startScan()
            }
        }
        reconnectWorkItem = workItem
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delayMs)), execute: workItem)
    }

    private func recoverConnection(message: String, reconnectDelayMs: Int64 = RECONNECT_DELAY_MS) {
        stopScan()
        stopStatusPolling()
        stopConnectionWatchdog()
        statusReadInFlight = false
        sampleStreamCharacteristic = nil
        networkStatusCharacteristic = nil

        if let peripheral = currentPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        currentPeripheral = nil

        updateState { state in
            state.connectionState = message
            state.connectionPhase = .recovering
            state.isScanning = false
            state.isConnected = false
            state.lastFailureReason = message
        }
        logEvent("Recovering connection: \(message)")

        scheduleReconnect(
            message: "Reconnecting to \(AGGREGATOR_DEVICE_NAME)",
            delayMs: reconnectDelayMs
        )
    }

    private func handleDisconnect(message: String, restartScan: Bool) {
        stopStatusPolling()
        stopConnectionWatchdog()
        statusReadInFlight = false
        sampleStreamCharacteristic = nil
        networkStatusCharacteristic = nil
        currentPeripheral = nil

        updateState { state in
            state.connectionState = message
            state.connectionPhase = .disconnected
            state.isScanning = false
            state.isConnected = false
        }
        logEvent(message)

        if restartScan && isRunning {
            scheduleReconnect(message: "Reconnecting to \(AGGREGATOR_DEVICE_NAME)")
        }
    }

    private func logEvent(_ message: String) {
        updateState { state in
            var events = state.recentEvents
            events.append(message)
            if events.count > MAX_EVENT_LOG_ENTRIES {
                events = Array(events.suffix(MAX_EVENT_LOG_ENTRIES))
            }
            state.recentEvents = events
        }
        logger.info("\(message, privacy: .public)")
    }

    private func updateState(_ mutate: (inout AggregatorUiState) -> Void) {
        var next = uiState
        mutate(&next)
        let snapshot = next
        DispatchQueue.main.async { [weak self] in
            self?.uiState = snapshot
        }
        // Also update the backing copy on the queue so subsequent reads on this queue stay consistent.
        uiState = next
    }
}

extension BleAggregatorController: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if isRunning {
            internalStart()
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let advName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let deviceName = advName ?? peripheral.name
        let serviceUuids = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID]
        let hasService = serviceUuids?.contains(GATT_SERVICE_UUID) ?? false
        if deviceName != AGGREGATOR_DEVICE_NAME && !hasService {
            return
        }

        logEvent("Found \(deviceName ?? AGGREGATOR_DEVICE_NAME) rssi=\(RSSI.intValue) address=\(peripheral.identifier.uuidString)")
        stopScan()
        connect(peripheral: peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral == currentPeripheral else {
            centralManager.cancelPeripheralConnection(peripheral)
            return
        }

        logEvent("Connection state changed: status=0 newState=2")
        updateState { state in
            state.connectionState = "Connected to \(state.deviceName), requesting MTU"
            state.connectionPhase = .requestingMtu
            state.isConnected = true
            state.errorMessage = nil
        }
        logEvent("Connected, requesting MTU 64")

        startConnectionWatchdog(timeoutMessage: "Service discovery timed out")
        updateState { state in
            state.connectionState = "MTU negotiated, discovering services"
            state.connectionPhase = .discoveringServices
        }
        peripheral.discoverServices([GATT_SERVICE_UUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral == currentPeripheral else { return }
        let errorMessage = "GATT connection failed: \(error?.localizedDescription ?? "unknown")"
        recoverConnection(message: errorMessage)
        updateState { state in
            state.errorMessage = errorMessage
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard peripheral == currentPeripheral else { return }
        let name = uiState.deviceName
        if let error = error {
            let description = error.localizedDescription
            // Treat timeouts with a longer reconnect delay, mirroring Android status-8 handling.
            let isTimeout = (error as NSError).code == CBError.connectionTimeout.rawValue
            handleDisconnect(message: "Disconnected from \(name)", restartScan: false)
            if isRunning {
                scheduleReconnect(
                    message: "Reconnecting to \(AGGREGATOR_DEVICE_NAME)",
                    delayMs: isTimeout ? TIMEOUT_RECONNECT_DELAY_MS : RECONNECT_DELAY_MS
                )
            }
            updateState { state in
                state.errorMessage = description
                state.lastFailureReason = description
            }
        } else {
            handleDisconnect(message: "Disconnected from \(name)", restartScan: true)
        }
    }
}

extension BleAggregatorController: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral == currentPeripheral else { return }

        if let error = error {
            handleDisconnect(message: "Service discovery failed", restartScan: true)
            updateState { state in
                state.errorMessage = "GATT service discovery: \(error.localizedDescription)"
            }
            logEvent("Service discovery failed: \(error.localizedDescription)")
            return
        }

        logEvent("Services discovered")
        guard let service = peripheral.services?.first(where: { $0.uuid == GATT_SERVICE_UUID }) else {
            handleDisconnect(message: "Custom BLE service not found", restartScan: true)
            updateState { state in
                state.errorMessage = "Aggregator GATT service is missing expected characteristics."
            }
            return
        }
        peripheral.discoverCharacteristics([SAMPLE_STREAM_UUID, NETWORK_STATUS_UUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral == currentPeripheral else { return }

        let sampleCharacteristic = service.characteristics?.first(where: { $0.uuid == SAMPLE_STREAM_UUID })
        let statusCharacteristic = service.characteristics?.first(where: { $0.uuid == NETWORK_STATUS_UUID })
        guard let sampleChar = sampleCharacteristic, let statusChar = statusCharacteristic else {
            handleDisconnect(message: "Custom BLE service not found", restartScan: true)
            updateState { state in
                state.errorMessage = "Aggregator GATT service is missing expected characteristics."
            }
            return
        }

        sampleStreamCharacteristic = sampleChar
        networkStatusCharacteristic = statusChar
        updateState { state in
            state.connectionState = "Connected to \(state.deviceName)"
            state.connectionPhase = .subscribing
            state.isConnected = true
            state.errorMessage = nil
        }

        startConnectionWatchdog(timeoutMessage: "Notification setup timed out")
        enableSampleNotifications(peripheral: peripheral, characteristic: sampleChar)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral == currentPeripheral, characteristic.uuid == SAMPLE_STREAM_UUID else { return }

        if error == nil {
            stopConnectionWatchdog()
        }

        updateState { state in
            state.connectionState = error == nil
                ? "Streaming samples from \(state.deviceName)"
                : "Connected to \(state.deviceName)"
            state.connectionPhase = error == nil ? .streaming : .subscribing
            if let error = error {
                state.errorMessage = "Failed to subscribe to sample_stream: \(error.localizedDescription)"
                state.lastFailureReason = state.errorMessage
            } else {
                state.errorMessage = nil
            }
        }
        if error == nil {
            logEvent("Subscribed to sample_stream")
            requestNetworkStatusRead()
            startStatusPolling()
        } else {
            logEvent("Descriptor write failed: \(error!.localizedDescription)")
            recoverConnection(message: "Retrying after subscription failure")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral == currentPeripheral else { return }

        if characteristic.uuid == NETWORK_STATUS_UUID {
            statusReadInFlight = false
            if let error = error {
                updateState { state in
                    state.connectionPhase = .streaming
                    state.errorMessage = "network_status read failed: \(error.localizedDescription)"
                    state.lastFailureReason = state.errorMessage
                }
                logEvent("network_status read failed: \(error.localizedDescription)")
                return
            }
            guard let value = characteristic.value else { return }
            let decoded = decodeNetworkStatus(value)
            let receivedAt = elapsedRealtimeMs()
            updateState { state in
                if state.isConnected {
                    state.connectionPhase = .streaming
                }
                if let decoded = decoded {
                    state.networkStatus = decoded
                }
                state.lastStatusHex = value.toHexString()
                state.lastStatusReceivedAtElapsedMs = receivedAt
                if decoded == nil {
                    state.errorMessage = "Failed to decode network_status (\(value.count) bytes)."
                    state.lastFailureReason = state.errorMessage
                } else {
                    state.errorMessage = nil
                }
            }
            if let decoded = decoded {
                logEvent("network_status updated: \(decoded.activeSensorCount) sensors")
            } else {
                logEvent("network_status decode failed (\(value.count) bytes)")
            }
        } else if characteristic.uuid == SAMPLE_STREAM_UUID {
            guard let value = characteristic.value else { return }
            let decoded = decodeImuSample(value)
            let receivedAt = elapsedRealtimeMs()
            updateState { state in
                state.connectionPhase = .streaming
                if let decoded = decoded {
                    state.latestSample = decoded
                }
                state.lastSampleHex = value.toHexString()
                state.lastSampleReceivedAtElapsedMs = receivedAt
                if decoded == nil {
                    state.errorMessage = "Failed to decode sample_stream packet (\(value.count) bytes)."
                    state.lastFailureReason = state.errorMessage
                } else {
                    state.errorMessage = nil
                }
            }
            if let decoded = decoded {
                logEvent("Sample sensor=\(decoded.sensorId) seq=\(decoded.seq)")
            } else {
                logEvent("sample_stream decode failed (\(value.count) bytes)")
            }
        }
    }
}
