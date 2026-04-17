package com.example.seniordesignmobileapp.recording

import android.content.Context
import com.example.seniordesignmobileapp.model.ActiveSensorStatus
import com.example.seniordesignmobileapp.model.ImuSample
import com.example.seniordesignmobileapp.model.NetworkStatus
import com.example.seniordesignmobileapp.model.SavedSessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingSessionInfo(
    val fileName: String,
    val absolutePath: String,
    val startedAtEpochMs: Long,
)

class SessionRecorder(
    context: Context,
) {
    private val sessionsDirectory = File(context.filesDir, "sessions").apply { mkdirs() }
    private val mutex = Mutex()
    private var activeSession: ActiveSession? = null

    suspend fun startSession(deviceName: String): RecordingSessionInfo =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(activeSession == null) { "A recording session is already active." }

                val startedAtEpochMs = System.currentTimeMillis()
                val timestamp = FILE_NAME_FORMAT.format(Date(startedAtEpochMs))
                val fileName = "session-$timestamp.jsonl"
                val file = File(sessionsDirectory, fileName)
                val writer = file.bufferedWriter()
                activeSession = ActiveSession(file = file, writer = writer, startedAtEpochMs = startedAtEpochMs)

                writer.appendLine(
                    JSONObject()
                        .put("type", "session_start")
                        .put("recorded_at_epoch_ms", startedAtEpochMs)
                        .put("device_name", deviceName)
                        .toString()
                )
                writer.flush()

                RecordingSessionInfo(
                    fileName = file.name,
                    absolutePath = file.absolutePath,
                    startedAtEpochMs = startedAtEpochMs,
                )
            }
        }

    suspend fun appendSample(
        recordedAtEpochMs: Long,
        receivedAtElapsedMs: Long,
        sample: ImuSample,
    ): Boolean = appendEvent(
        JSONObject()
            .put("type", "sample")
            .put("recorded_at_epoch_ms", recordedAtEpochMs)
            .put("received_at_elapsed_ms", receivedAtElapsedMs)
            .put("sample", sample.toJson()),
    )

    suspend fun appendNetworkStatus(
        recordedAtEpochMs: Long,
        receivedAtElapsedMs: Long,
        status: NetworkStatus,
    ): Boolean = appendEvent(
        JSONObject()
            .put("type", "network_status")
            .put("recorded_at_epoch_ms", recordedAtEpochMs)
            .put("received_at_elapsed_ms", receivedAtElapsedMs)
            .put("network_status", status.toJson()),
    )

    suspend fun stopSession(reason: String): RecordingSessionInfo? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val session = activeSession ?: return@withLock null
                val stoppedAtEpochMs = System.currentTimeMillis()
                session.writer.appendLine(
                    JSONObject()
                        .put("type", "session_end")
                        .put("recorded_at_epoch_ms", stoppedAtEpochMs)
                        .put("reason", reason)
                        .toString()
                )
                session.writer.flush()
                session.writer.close()
                activeSession = null

                RecordingSessionInfo(
                    fileName = session.file.name,
                    absolutePath = session.file.absolutePath,
                    startedAtEpochMs = session.startedAtEpochMs,
                )
            }
        }

    suspend fun listSessions(limit: Int = 10): List<SavedSessionSummary> =
        withContext(Dispatchers.IO) {
            sessionsDirectory
                .listFiles()
                .orEmpty()
                .filter { file -> file.isFile && file.extension == "jsonl" }
                .sortedByDescending(File::lastModified)
                .take(limit)
                .map { file ->
                    SavedSessionSummary(
                        fileName = file.name,
                        absolutePath = file.absolutePath,
                        sizeBytes = file.length(),
                        modifiedAtEpochMs = file.lastModified(),
                    )
                }
        }

    fun getSessionFile(fileName: String): File? {
        val candidate = File(sessionsDirectory, fileName)
        return candidate.takeIf { it.exists() && it.isFile }
    }

    private suspend fun appendEvent(event: JSONObject): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val session = activeSession ?: return@withLock false
                session.writer.appendLine(event.toString())
                session.writer.flush()
                true
            }
        }

    private data class ActiveSession(
        val file: File,
        val writer: BufferedWriter,
        val startedAtEpochMs: Long,
    )

    private companion object {
        val FILE_NAME_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}

private fun ImuSample.toJson(): JSONObject =
    JSONObject()
        .put("version", version)
        .put("sensor_id", sensorId)
        .put("seq", seq)
        .put("timestamp_ms", timestampMs)
        .put("ax", ax)
        .put("ay", ay)
        .put("az", az)
        .put("gx", gx)
        .put("gy", gy)
        .put("gz", gz)

private fun NetworkStatus.toJson(): JSONObject =
    JSONObject()
        .put("version", version)
        .put("uptime_ms", uptimeMs)
        .put("active_sensor_count", activeSensorCount)
        .put(
            "sensors",
            JSONArray().apply {
                sensors.forEach { put(it.toJson()) }
            }
        )

private fun ActiveSensorStatus.toJson(): JSONObject =
    JSONObject()
        .put("sensor_id", sensorId)
        .put("seq", seq)
        .put("age_ms", ageMs)
