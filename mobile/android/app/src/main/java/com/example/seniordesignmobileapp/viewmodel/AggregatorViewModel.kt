package com.example.seniordesignmobileapp.viewmodel

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.seniordesignmobileapp.ble.BleAggregatorController
import com.example.seniordesignmobileapp.domain.PostureAnalysisCoordinator
import com.example.seniordesignmobileapp.model.AggregatorUiState
import com.example.seniordesignmobileapp.model.AnalysisUiState
import com.example.seniordesignmobileapp.model.ModeledGravityVector
import com.example.seniordesignmobileapp.model.ModeledNodeUiState
import com.example.seniordesignmobileapp.model.ModelingUiState
import com.example.seniordesignmobileapp.model.SavedSessionSummary
import com.example.seniordesignmobileapp.modeling.NodePoseMath
import com.example.seniordesignmobileapp.recording.SessionRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AggregatorViewModel(
    applicationContext: Context,
) : ViewModel() {
    private val controller = BleAggregatorController(applicationContext)
    private val sessionRecorder = SessionRecorder(applicationContext)
    private val recordingState = MutableStateFlow(RecordingState())
    private val analysisCoordinator = PostureAnalysisCoordinator()
    private val analysisState = MutableStateFlow(AnalysisUiState())
    private val modelingState = MutableStateFlow(ModelingUiState())
    private val latestSamplesBySensor = linkedMapOf<Int, TimedSensorSample>()
    private var lastRecordedSampleReceivedAtElapsedMs: Long? = null
    private var lastRecordedStatusReceivedAtElapsedMs: Long? = null
    private var lastAnalyzedSampleReceivedAtElapsedMs: Long? = null
    private var lastAnalyzedStatusReceivedAtElapsedMs: Long? = null

    val uiState: StateFlow<AggregatorUiState> = combine(
        controller.uiState,
        recordingState,
        analysisState,
        modelingState,
    ) { bleState, recording, analysis, modeling ->
        bleState.copy(
            isRecording = recording.isRecording,
            recordingSessionName = recording.sessionName,
            recordingSessionPath = recording.sessionPath,
            recordingStartedAtElapsedMs = recording.startedAtElapsedMs,
            recordedSampleCount = recording.recordedSampleCount,
            recordedStatusCount = recording.recordedStatusCount,
            savedSessions = recording.savedSessions,
            recordingErrorMessage = recording.errorMessage,
            analysis = analysis,
            modeling = modeling,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = controller.uiState.value,
    )

    init {
        viewModelScope.launch {
            refreshSavedSessions()
        }
        viewModelScope.launch {
            controller.uiState.collect { bleState ->
                recordIfNeeded(bleState)
                analyzeIfNeeded(bleState)
                updateModelingState(bleState)
            }
        }
    }

    fun onBlePermissionsChanged(granted: Boolean) {
        if (granted) {
            controller.start()
        } else {
            resetAnalysis()
            controller.stop()
        }
    }

    fun reconnect() {
        resetAnalysis()
        controller.restart()
    }

    fun calibrateSittingPosture() {
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        val result = analysisCoordinator.captureCalibration(
            nowEpochMs = System.currentTimeMillis(),
        )
        analysisState.value = result.snapshot.toUiState(
            updatedAtElapsedMs = elapsedRealtimeMs,
            calibrationMessage = result.message,
        )
        refreshModelingState(
            bleState = controller.uiState.value,
            analysis = analysisState.value,
        )
    }

    fun setUpperBackSensor(
        sensorId: Int?,
    ) {
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        val snapshot = analysisCoordinator.setUpperBackSensor(
            sensorId = sensorId,
            nowEpochMs = System.currentTimeMillis(),
        )
        analysisState.value = snapshot.toUiState(
            updatedAtElapsedMs = elapsedRealtimeMs,
            calibrationMessage = snapshot.calibrationMessage,
        )
        refreshModelingState(
            bleState = controller.uiState.value,
            analysis = analysisState.value,
        )
    }

    fun setLowerBackSensor(
        sensorId: Int?,
    ) {
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        val snapshot = analysisCoordinator.setLowerBackSensor(
            sensorId = sensorId,
            nowEpochMs = System.currentTimeMillis(),
        )
        analysisState.value = snapshot.toUiState(
            updatedAtElapsedMs = elapsedRealtimeMs,
            calibrationMessage = snapshot.calibrationMessage,
        )
        refreshModelingState(
            bleState = controller.uiState.value,
            analysis = analysisState.value,
        )
    }

    fun startRecording() {
        if (recordingState.value.isRecording) {
            return
        }

        viewModelScope.launch {
            try {
                val session = sessionRecorder.startSession(controller.uiState.value.deviceName)
                recordingState.value = RecordingState(
                    isRecording = true,
                    sessionName = session.fileName,
                    sessionPath = session.absolutePath,
                    startedAtElapsedMs = SystemClock.elapsedRealtime(),
                    recordedSampleCount = 0,
                    recordedStatusCount = 0,
                    errorMessage = null,
                )
                lastRecordedSampleReceivedAtElapsedMs = null
                lastRecordedStatusReceivedAtElapsedMs = null
                recordCurrentSnapshots(controller.uiState.value)
                refreshSavedSessions()
            } catch (error: Exception) {
                recordingState.update {
                    it.copy(errorMessage = error.message ?: "Failed to start recording.")
                }
            }
        }
    }

    fun stopRecording() {
        if (!recordingState.value.isRecording) {
            return
        }

        viewModelScope.launch {
            try {
                sessionRecorder.stopSession(reason = "user_stopped")
                recordingState.update {
                    it.copy(
                        isRecording = false,
                        startedAtElapsedMs = null,
                        errorMessage = null,
                    )
                }
                lastRecordedSampleReceivedAtElapsedMs = null
                lastRecordedStatusReceivedAtElapsedMs = null
                refreshSavedSessions()
            } catch (error: Exception) {
                recordingState.update {
                    it.copy(errorMessage = error.message ?: "Failed to stop recording.")
                }
            }
        }
    }

    override fun onCleared() {
        if (recordingState.value.isRecording) {
            runBlocking {
                sessionRecorder.stopSession(reason = "viewmodel_cleared")
            }
        }
        resetAnalysis()
        controller.stop()
        super.onCleared()
    }

    companion object {
        fun factory(applicationContext: Context): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AggregatorViewModel(applicationContext)
                }
            }
    }

    private suspend fun recordIfNeeded(bleState: AggregatorUiState) {
        if (!recordingState.value.isRecording) {
            return
        }

        val sampleTimestamp = bleState.lastSampleReceivedAtElapsedMs
        if (bleState.latestSample != null &&
            sampleTimestamp != null &&
            sampleTimestamp != lastRecordedSampleReceivedAtElapsedMs
        ) {
            val wroteSample = sessionRecorder.appendSample(
                recordedAtEpochMs = System.currentTimeMillis(),
                receivedAtElapsedMs = sampleTimestamp,
                sample = bleState.latestSample,
            )
            if (wroteSample) {
                lastRecordedSampleReceivedAtElapsedMs = sampleTimestamp
                recordingState.update {
                    it.copy(
                        recordedSampleCount = it.recordedSampleCount + 1,
                        errorMessage = null,
                    )
                }
            }
        }

        val statusTimestamp = bleState.lastStatusReceivedAtElapsedMs
        if (bleState.networkStatus != null &&
            statusTimestamp != null &&
            statusTimestamp != lastRecordedStatusReceivedAtElapsedMs
        ) {
            val wroteStatus = sessionRecorder.appendNetworkStatus(
                recordedAtEpochMs = System.currentTimeMillis(),
                receivedAtElapsedMs = statusTimestamp,
                status = bleState.networkStatus,
            )
            if (wroteStatus) {
                lastRecordedStatusReceivedAtElapsedMs = statusTimestamp
                recordingState.update {
                    it.copy(
                        recordedStatusCount = it.recordedStatusCount + 1,
                        errorMessage = null,
                    )
                }
            }
        }
    }

    private suspend fun recordCurrentSnapshots(bleState: AggregatorUiState) {
        recordIfNeeded(bleState)
    }

    private fun analyzeIfNeeded(bleState: AggregatorUiState) {
        val statusTimestamp = bleState.lastStatusReceivedAtElapsedMs
        if (bleState.networkStatus != null &&
            statusTimestamp != null &&
            statusTimestamp != lastAnalyzedStatusReceivedAtElapsedMs
        ) {
            val snapshot = analysisCoordinator.onNetworkStatus(
                receivedAtEpochMs = System.currentTimeMillis(),
                status = bleState.networkStatus,
            )
            lastAnalyzedStatusReceivedAtElapsedMs = statusTimestamp
            analysisState.value = snapshot.toUiState(
                updatedAtElapsedMs = statusTimestamp,
                calibrationMessage = analysisState.value.calibrationMessage,
            )
        }

        val sampleTimestamp = bleState.lastSampleReceivedAtElapsedMs
        if (bleState.latestSample != null &&
            sampleTimestamp != null &&
            sampleTimestamp != lastAnalyzedSampleReceivedAtElapsedMs
        ) {
            val snapshot = analysisCoordinator.onSample(
                receivedAtEpochMs = System.currentTimeMillis(),
                sample = bleState.latestSample,
            )
            lastAnalyzedSampleReceivedAtElapsedMs = sampleTimestamp
            analysisState.value = snapshot.toUiState(
                updatedAtElapsedMs = sampleTimestamp,
                calibrationMessage = analysisState.value.calibrationMessage,
            )
        }
    }

    private suspend fun refreshSavedSessions() {
        recordingState.update {
            it.copy(savedSessions = sessionRecorder.listSessions())
        }
    }

    private fun resetAnalysis() {
        analysisCoordinator.clear()
        analysisState.value = AnalysisUiState()
        latestSamplesBySensor.clear()
        modelingState.value = ModelingUiState()
        lastAnalyzedSampleReceivedAtElapsedMs = null
        lastAnalyzedStatusReceivedAtElapsedMs = null
    }

    private fun updateModelingState(bleState: AggregatorUiState) {
        val sample = bleState.latestSample
        val sampleTimestamp = bleState.lastSampleReceivedAtElapsedMs
        if (sample != null && sampleTimestamp != null) {
            latestSamplesBySensor[sample.sensorId] = TimedSensorSample(
                sample = sample,
                receivedAtElapsedMs = sampleTimestamp,
            )
        }
        refreshModelingState(
            bleState = bleState,
            analysis = analysisState.value,
        )
    }

    private fun refreshModelingState(
        bleState: AggregatorUiState,
        analysis: AnalysisUiState,
    ) {
        val liveSensorIds = bleState.networkStatus?.sensors
            ?.map { it.sensorId }
            ?.toSet()
            .orEmpty()
        val placementBySensorId = analysis.sensorAssignments.associate { assignment ->
            assignment.sensorId to assignment.placement
        }
        val sensorIds = (liveSensorIds + analysis.availableSensorIds + latestSamplesBySensor.keys)
            .toSet()
            .sorted()

        val nodes = sensorIds.map { sensorId ->
            val timedSample = latestSamplesBySensor[sensorId]
            val pose = timedSample?.let { NodePoseMath.fromSample(it.sample) }
            ModeledNodeUiState(
                sensorId = sensorId,
                placement = placementBySensorId[sensorId],
                isLiveInNetworkStatus = sensorId in liveSensorIds,
                seq = timedSample?.sample?.seq,
                pitchDeg = pose?.pitchDeg,
                rollDeg = pose?.rollDeg,
                gravityVector = pose?.gravityVector?.let { gravity ->
                    ModeledGravityVector(
                        x = gravity.x,
                        y = gravity.y,
                        z = gravity.z,
                    )
                },
                lastSampleReceivedAtElapsedMs = timedSample?.receivedAtElapsedMs,
            )
        }

        val statusMessage = when {
            nodes.isEmpty() -> "Waiting for network status and live IMU samples before the modeling scene can render."
            nodes.any { it.gravityVector != null } -> "Orientation-only node scene from the latest per-sensor IMU samples. Positions are schematic until a full spatial model exists."
            else -> "Sensors are known, but the app is still waiting for per-sensor IMU samples to estimate orientation."
        }

        modelingState.value = ModelingUiState(
            nodes = nodes,
            lastUpdatedAtElapsedMs = bleState.lastSampleReceivedAtElapsedMs ?: bleState.lastStatusReceivedAtElapsedMs,
            statusMessage = statusMessage,
        )
    }
}

private data class RecordingState(
    val isRecording: Boolean = false,
    val sessionName: String? = null,
    val sessionPath: String? = null,
    val startedAtElapsedMs: Long? = null,
    val recordedSampleCount: Int = 0,
    val recordedStatusCount: Int = 0,
    val savedSessions: List<SavedSessionSummary> = emptyList(),
    val errorMessage: String? = null,
)

private data class TimedSensorSample(
    val sample: com.example.seniordesignmobileapp.model.ImuSample,
    val receivedAtElapsedMs: Long,
)

private fun com.example.seniordesignmobileapp.domain.PostureAnalysisSnapshot.toUiState(
    updatedAtElapsedMs: Long,
    calibrationMessage: String?,
): AnalysisUiState =
    AnalysisUiState(
        config = config,
        sensorAssignments = sensorAssignments,
        availableSensorIds = availableSensorIds,
        manualUpperBackSensorId = manualUpperBackSensorId,
        manualLowerBackSensorId = manualLowerBackSensorId,
        expectedSensors = expectedSensors,
        expectedSensorsInferred = expectedSensorsInferred,
        sittingCalibration = sittingCalibration,
        windowSummary = windowSummary,
        latestResult = latestResult,
        lastUpdatedAtElapsedMs = updatedAtElapsedMs,
        calibrationMessage = calibrationMessage ?: this.calibrationMessage,
        statusMessage = statusMessage,
    )
