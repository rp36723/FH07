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
import com.example.seniordesignmobileapp.model.SavedSessionSummary
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
    private var lastRecordedSampleReceivedAtElapsedMs: Long? = null
    private var lastRecordedStatusReceivedAtElapsedMs: Long? = null
    private var lastAnalyzedSampleReceivedAtElapsedMs: Long? = null
    private var lastAnalyzedStatusReceivedAtElapsedMs: Long? = null

    val uiState: StateFlow<AggregatorUiState> = combine(
        controller.uiState,
        recordingState,
        analysisState,
    ) { bleState, recording, analysis ->
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
            analysisState.value = snapshot.toUiState(updatedAtElapsedMs = statusTimestamp)
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
            analysisState.value = snapshot.toUiState(updatedAtElapsedMs = sampleTimestamp)
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
        lastAnalyzedSampleReceivedAtElapsedMs = null
        lastAnalyzedStatusReceivedAtElapsedMs = null
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

private fun com.example.seniordesignmobileapp.domain.PostureAnalysisSnapshot.toUiState(
    updatedAtElapsedMs: Long,
): AnalysisUiState =
    AnalysisUiState(
        config = config,
        expectedSensors = expectedSensors,
        expectedSensorsInferred = expectedSensorsInferred,
        windowSummary = windowSummary,
        latestResult = latestResult,
        lastUpdatedAtElapsedMs = updatedAtElapsedMs,
        statusMessage = statusMessage,
    )
