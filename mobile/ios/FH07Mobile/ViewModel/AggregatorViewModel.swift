import Foundation
import Combine

@MainActor
final class AggregatorViewModel: ObservableObject {
    @Published private(set) var uiState = AggregatorUiState()

    private let controller = BleAggregatorController()
    private let sessionRecorder = SessionRecorder()
    private let analysisCoordinator = PostureAnalysisCoordinator()

    private var recordingState = RecordingStateHolder()
    private var analysisState = AnalysisUiState()
    private var modelingState = ModelingUiState()

    private var latestSamplesBySensor: [Int: TimedSensorSample] = [:]
    private var latestSampleOrder: [Int] = []
    private var lastRecordedSampleReceivedAtElapsedMs: Int64? = nil
    private var lastRecordedStatusReceivedAtElapsedMs: Int64? = nil
    private var lastAnalyzedSampleReceivedAtElapsedMs: Int64? = nil
    private var lastAnalyzedStatusReceivedAtElapsedMs: Int64? = nil

    private var cancellables: Set<AnyCancellable> = []

    init() {
        controller.$uiState
            .receive(on: DispatchQueue.main)
            .sink { [weak self] bleState in
                guard let self = self else { return }
                Task { await self.handleBleStateUpdate(bleState) }
            }
            .store(in: &cancellables)

        Task { await refreshSavedSessions() }
        republishUiState(controller.uiState)
    }

    func onBlePermissionsChanged(granted: Bool) {
        if granted {
            controller.start()
        } else {
            resetAnalysis()
            controller.stop()
        }
    }

    func reconnect() {
        resetAnalysis()
        controller.restart()
    }

    func calibrateSittingPosture() {
        let elapsedRealtimeMsValue = elapsedRealtimeMs()
        let result = analysisCoordinator.captureCalibration(
            nowEpochMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
        analysisState = toUiState(
            snapshot: result.snapshot,
            updatedAtElapsedMs: elapsedRealtimeMsValue,
            calibrationMessage: result.message
        )
        refreshModelingState(bleState: controller.uiState, analysis: analysisState)
        republishUiState(controller.uiState)
    }

    func setUpperBackSensor(_ sensorId: Int?) {
        let elapsedRealtimeMsValue = elapsedRealtimeMs()
        let snapshot = analysisCoordinator.setUpperBackSensor(
            sensorId: sensorId,
            nowEpochMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
        analysisState = toUiState(
            snapshot: snapshot,
            updatedAtElapsedMs: elapsedRealtimeMsValue,
            calibrationMessage: snapshot.calibrationMessage
        )
        refreshModelingState(bleState: controller.uiState, analysis: analysisState)
        republishUiState(controller.uiState)
    }

    func setLowerBackSensor(_ sensorId: Int?) {
        let elapsedRealtimeMsValue = elapsedRealtimeMs()
        let snapshot = analysisCoordinator.setLowerBackSensor(
            sensorId: sensorId,
            nowEpochMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
        analysisState = toUiState(
            snapshot: snapshot,
            updatedAtElapsedMs: elapsedRealtimeMsValue,
            calibrationMessage: snapshot.calibrationMessage
        )
        refreshModelingState(bleState: controller.uiState, analysis: analysisState)
        republishUiState(controller.uiState)
    }

    func startRecording() {
        if recordingState.isRecording { return }

        Task {
            do {
                let deviceName = controller.uiState.deviceName
                let session = try await sessionRecorder.startSession(deviceName: deviceName)
                recordingState = RecordingStateHolder(
                    isRecording: true,
                    sessionName: session.fileName,
                    sessionPath: session.absolutePath,
                    startedAtElapsedMs: elapsedRealtimeMs(),
                    recordedSampleCount: 0,
                    recordedStatusCount: 0,
                    savedSessions: recordingState.savedSessions,
                    errorMessage: nil
                )
                lastRecordedSampleReceivedAtElapsedMs = nil
                lastRecordedStatusReceivedAtElapsedMs = nil
                await recordIfNeeded(bleState: controller.uiState)
                await refreshSavedSessions()
                republishUiState(controller.uiState)
            } catch {
                recordingState.errorMessage = error.localizedDescription
                republishUiState(controller.uiState)
            }
        }
    }

    func stopRecording() {
        if !recordingState.isRecording { return }

        Task {
            _ = await sessionRecorder.stopSession(reason: "user_stopped")
            recordingState.isRecording = false
            recordingState.startedAtElapsedMs = nil
            recordingState.errorMessage = nil
            lastRecordedSampleReceivedAtElapsedMs = nil
            lastRecordedStatusReceivedAtElapsedMs = nil
            await refreshSavedSessions()
            republishUiState(controller.uiState)
        }
    }

    deinit {
        // Recording cleanup: the actor is reference-counted; sessions are closed when the recorder deinits.
    }

    // MARK: - State update pipeline

    private func handleBleStateUpdate(_ bleState: AggregatorUiState) async {
        await recordIfNeeded(bleState: bleState)
        analyzeIfNeeded(bleState: bleState)
        updateModelingState(bleState: bleState)
        republishUiState(bleState)
    }

    private func recordIfNeeded(bleState: AggregatorUiState) async {
        guard recordingState.isRecording else { return }

        if let sample = bleState.latestSample,
           let sampleTimestamp = bleState.lastSampleReceivedAtElapsedMs,
           sampleTimestamp != lastRecordedSampleReceivedAtElapsedMs {
            let wrote = await sessionRecorder.appendSample(
                recordedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1000),
                receivedAtElapsedMs: sampleTimestamp,
                sample: sample
            )
            if wrote {
                lastRecordedSampleReceivedAtElapsedMs = sampleTimestamp
                recordingState.recordedSampleCount += 1
                recordingState.errorMessage = nil
            }
        }

        if let status = bleState.networkStatus,
           let statusTimestamp = bleState.lastStatusReceivedAtElapsedMs,
           statusTimestamp != lastRecordedStatusReceivedAtElapsedMs {
            let wrote = await sessionRecorder.appendNetworkStatus(
                recordedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1000),
                receivedAtElapsedMs: statusTimestamp,
                status: status
            )
            if wrote {
                lastRecordedStatusReceivedAtElapsedMs = statusTimestamp
                recordingState.recordedStatusCount += 1
                recordingState.errorMessage = nil
            }
        }
    }

    private func analyzeIfNeeded(bleState: AggregatorUiState) {
        if let status = bleState.networkStatus,
           let statusTimestamp = bleState.lastStatusReceivedAtElapsedMs,
           statusTimestamp != lastAnalyzedStatusReceivedAtElapsedMs {
            let snapshot = analysisCoordinator.onNetworkStatus(
                receivedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1000),
                status: status
            )
            lastAnalyzedStatusReceivedAtElapsedMs = statusTimestamp
            analysisState = toUiState(
                snapshot: snapshot,
                updatedAtElapsedMs: statusTimestamp,
                calibrationMessage: analysisState.calibrationMessage
            )
        }

        if let sample = bleState.latestSample,
           let sampleTimestamp = bleState.lastSampleReceivedAtElapsedMs,
           sampleTimestamp != lastAnalyzedSampleReceivedAtElapsedMs {
            let snapshot = analysisCoordinator.onSample(
                receivedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1000),
                sample: sample
            )
            lastAnalyzedSampleReceivedAtElapsedMs = sampleTimestamp
            analysisState = toUiState(
                snapshot: snapshot,
                updatedAtElapsedMs: sampleTimestamp,
                calibrationMessage: analysisState.calibrationMessage
            )
        }
    }

    private func refreshSavedSessions() async {
        let sessions = await sessionRecorder.listSessions()
        recordingState.savedSessions = sessions
        republishUiState(controller.uiState)
    }

    private func resetAnalysis() {
        analysisCoordinator.clear()
        analysisState = AnalysisUiState()
        latestSamplesBySensor.removeAll()
        latestSampleOrder.removeAll()
        modelingState = ModelingUiState()
        lastAnalyzedSampleReceivedAtElapsedMs = nil
        lastAnalyzedStatusReceivedAtElapsedMs = nil
    }

    private func updateModelingState(bleState: AggregatorUiState) {
        if let sample = bleState.latestSample,
           let sampleTimestamp = bleState.lastSampleReceivedAtElapsedMs {
            if latestSamplesBySensor[sample.sensorId] == nil {
                latestSampleOrder.append(sample.sensorId)
            }
            latestSamplesBySensor[sample.sensorId] = TimedSensorSample(
                sample: sample,
                receivedAtElapsedMs: sampleTimestamp
            )
        }
        refreshModelingState(bleState: bleState, analysis: analysisState)
    }

    private func refreshModelingState(bleState: AggregatorUiState, analysis: AnalysisUiState) {
        let liveSensorIds: Set<Int> = Set(bleState.networkStatus?.sensors.map { $0.sensorId } ?? [])
        var placementBySensorId: [Int: SensorPlacement] = [:]
        for assignment in analysis.sensorAssignments {
            placementBySensorId[assignment.sensorId] = assignment.placement
        }

        var allIds = liveSensorIds
        for id in analysis.availableSensorIds { allIds.insert(id) }
        for id in latestSamplesBySensor.keys { allIds.insert(id) }
        let sensorIds = allIds.sorted()

        let nodes: [ModeledNodeUiState] = sensorIds.map { sensorId in
            let timedSample = latestSamplesBySensor[sensorId]
            let pose: NodePoseEstimate? = timedSample.flatMap { NodePoseMath.fromSample($0.sample) }
            return ModeledNodeUiState(
                sensorId: sensorId,
                placement: placementBySensorId[sensorId],
                isLiveInNetworkStatus: liveSensorIds.contains(sensorId),
                seq: timedSample?.sample.seq,
                pitchDeg: pose?.pitchDeg,
                rollDeg: pose?.rollDeg,
                gravityVector: pose.map { ModeledGravityVector(x: $0.gravityVector.x, y: $0.gravityVector.y, z: $0.gravityVector.z) },
                lastSampleReceivedAtElapsedMs: timedSample?.receivedAtElapsedMs
            )
        }

        let statusMessage: String
        if nodes.isEmpty {
            statusMessage = "Waiting for network status and live IMU samples before the modeling scene can render."
        } else if nodes.contains(where: { $0.gravityVector != nil }) {
            statusMessage = "Orientation-only node scene from the latest per-sensor IMU samples. Positions are schematic until a full spatial model exists."
        } else {
            statusMessage = "Sensors are known, but the app is still waiting for per-sensor IMU samples to estimate orientation."
        }

        modelingState = ModelingUiState(
            nodes: nodes,
            lastUpdatedAtElapsedMs: bleState.lastSampleReceivedAtElapsedMs ?? bleState.lastStatusReceivedAtElapsedMs,
            statusMessage: statusMessage
        )
    }

    private func republishUiState(_ bleState: AggregatorUiState) {
        var next = bleState
        next.isRecording = recordingState.isRecording
        next.recordingSessionName = recordingState.sessionName
        next.recordingSessionPath = recordingState.sessionPath
        next.recordingStartedAtElapsedMs = recordingState.startedAtElapsedMs
        next.recordedSampleCount = recordingState.recordedSampleCount
        next.recordedStatusCount = recordingState.recordedStatusCount
        next.savedSessions = recordingState.savedSessions
        next.recordingErrorMessage = recordingState.errorMessage
        next.analysis = analysisState
        next.modeling = modelingState
        uiState = next
    }

    private func toUiState(
        snapshot: PostureAnalysisSnapshot,
        updatedAtElapsedMs: Int64,
        calibrationMessage: String?
    ) -> AnalysisUiState {
        AnalysisUiState(
            config: snapshot.config,
            sensorAssignments: snapshot.sensorAssignments,
            availableSensorIds: snapshot.availableSensorIds,
            manualUpperBackSensorId: snapshot.manualUpperBackSensorId,
            manualLowerBackSensorId: snapshot.manualLowerBackSensorId,
            expectedSensors: snapshot.expectedSensors,
            expectedSensorsInferred: snapshot.expectedSensorsInferred,
            sittingCalibration: snapshot.sittingCalibration,
            windowSummary: snapshot.windowSummary,
            latestResult: snapshot.latestResult,
            lastUpdatedAtElapsedMs: updatedAtElapsedMs,
            calibrationMessage: calibrationMessage ?? snapshot.calibrationMessage,
            statusMessage: snapshot.statusMessage
        )
    }
}

private struct RecordingStateHolder {
    var isRecording: Bool = false
    var sessionName: String? = nil
    var sessionPath: String? = nil
    var startedAtElapsedMs: Int64? = nil
    var recordedSampleCount: Int = 0
    var recordedStatusCount: Int = 0
    var savedSessions: [SavedSessionSummary] = []
    var errorMessage: String? = nil
}

private struct TimedSensorSample {
    let sample: ImuSample
    let receivedAtElapsedMs: Int64
}
