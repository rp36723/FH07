import Foundation

struct RecordingSessionInfo: Equatable {
    let fileName: String
    let absolutePath: String
    let startedAtEpochMs: Int64
}

actor SessionRecorder {
    private let sessionsDirectory: URL
    private var activeSession: ActiveSession?

    private static let fileNameFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone.current
        return formatter
    }()

    init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        self.sessionsDirectory = documents.appendingPathComponent("sessions", isDirectory: true)
        try? FileManager.default.createDirectory(at: sessionsDirectory, withIntermediateDirectories: true)
    }

    func startSession(deviceName: String) throws -> RecordingSessionInfo {
        if activeSession != nil {
            throw RecorderError.alreadyActive
        }

        let startedAtEpochMs = Int64(Date().timeIntervalSince1970 * 1000)
        let timestamp = Self.fileNameFormatter.string(from: Date(timeIntervalSince1970: TimeInterval(startedAtEpochMs) / 1000))
        let fileName = "session-\(timestamp).jsonl"
        let fileURL = sessionsDirectory.appendingPathComponent(fileName)

        FileManager.default.createFile(atPath: fileURL.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: fileURL) else {
            throw RecorderError.cannotOpenFile
        }
        let session = ActiveSession(file: fileURL, handle: handle, startedAtEpochMs: startedAtEpochMs)
        activeSession = session

        let startEvent: [String: Any] = [
            "type": "session_start",
            "recorded_at_epoch_ms": startedAtEpochMs,
            "device_name": deviceName
        ]
        try writeJsonLine(handle: handle, object: startEvent)

        return RecordingSessionInfo(
            fileName: fileURL.lastPathComponent,
            absolutePath: fileURL.path,
            startedAtEpochMs: startedAtEpochMs
        )
    }

    func appendSample(recordedAtEpochMs: Int64, receivedAtElapsedMs: Int64, sample: ImuSample) -> Bool {
        let event: [String: Any] = [
            "type": "sample",
            "recorded_at_epoch_ms": recordedAtEpochMs,
            "received_at_elapsed_ms": receivedAtElapsedMs,
            "sample": sample.toJsonDictionary()
        ]
        return appendEvent(event)
    }

    func appendNetworkStatus(recordedAtEpochMs: Int64, receivedAtElapsedMs: Int64, status: NetworkStatus) -> Bool {
        let event: [String: Any] = [
            "type": "network_status",
            "recorded_at_epoch_ms": recordedAtEpochMs,
            "received_at_elapsed_ms": receivedAtElapsedMs,
            "network_status": status.toJsonDictionary()
        ]
        return appendEvent(event)
    }

    func stopSession(reason: String) -> RecordingSessionInfo? {
        guard let session = activeSession else { return nil }
        let stoppedAtEpochMs = Int64(Date().timeIntervalSince1970 * 1000)
        let endEvent: [String: Any] = [
            "type": "session_end",
            "recorded_at_epoch_ms": stoppedAtEpochMs,
            "reason": reason
        ]
        try? writeJsonLine(handle: session.handle, object: endEvent)
        try? session.handle.close()
        activeSession = nil

        return RecordingSessionInfo(
            fileName: session.file.lastPathComponent,
            absolutePath: session.file.path,
            startedAtEpochMs: session.startedAtEpochMs
        )
    }

    func listSessions(limit: Int = 10) -> [SavedSessionSummary] {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(
            at: sessionsDirectory,
            includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey, .isRegularFileKey]
        ) else {
            return []
        }

        let summaries: [SavedSessionSummary] = files.compactMap { url in
            let values = try? url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey, .isRegularFileKey])
            guard values?.isRegularFile == true, url.pathExtension == "jsonl" else { return nil }
            let modifiedEpochMs = Int64((values?.contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000)
            let size = Int64(values?.fileSize ?? 0)
            return SavedSessionSummary(
                fileName: url.lastPathComponent,
                absolutePath: url.path,
                sizeBytes: size,
                modifiedAtEpochMs: modifiedEpochMs
            )
        }
        return Array(summaries.sorted(by: { $0.modifiedAtEpochMs > $1.modifiedAtEpochMs }).prefix(limit))
    }

    func getSessionFile(fileName: String) -> URL? {
        let candidate = sessionsDirectory.appendingPathComponent(fileName)
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: candidate.path, isDirectory: &isDir), !isDir.boolValue else {
            return nil
        }
        return candidate
    }

    private func appendEvent(_ event: [String: Any]) -> Bool {
        guard let session = activeSession else { return false }
        do {
            try writeJsonLine(handle: session.handle, object: event)
            return true
        } catch {
            return false
        }
    }

    private func writeJsonLine(handle: FileHandle, object: [String: Any]) throws {
        let data = try JSONSerialization.data(withJSONObject: object, options: [])
        handle.write(data)
        handle.write(Data([0x0A])) // newline
    }

    private struct ActiveSession {
        let file: URL
        let handle: FileHandle
        let startedAtEpochMs: Int64
    }

    enum RecorderError: Error {
        case alreadyActive
        case cannotOpenFile
    }
}

private extension ImuSample {
    func toJsonDictionary() -> [String: Any] {
        [
            "version": version,
            "sensor_id": sensorId,
            "seq": seq,
            "timestamp_ms": timestampMs,
            "ax": ax,
            "ay": ay,
            "az": az,
            "gx": gx,
            "gy": gy,
            "gz": gz
        ]
    }
}

private extension NetworkStatus {
    func toJsonDictionary() -> [String: Any] {
        [
            "version": version,
            "uptime_ms": uptimeMs,
            "active_sensor_count": activeSensorCount,
            "sensors": sensors.map { $0.toJsonDictionary() }
        ]
    }
}

private extension ActiveSensorStatus {
    func toJsonDictionary() -> [String: Any] {
        [
            "sensor_id": sensorId,
            "seq": seq,
            "age_ms": ageMs
        ]
    }
}
