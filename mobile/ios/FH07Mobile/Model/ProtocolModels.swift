import Foundation

struct ImuSample: Equatable {
    let version: Int
    let sensorId: Int
    let seq: Int
    let timestampMs: Int64
    let ax: Int
    let ay: Int
    let az: Int
    let gx: Int
    let gy: Int
    let gz: Int
}

struct ActiveSensorStatus: Equatable {
    let sensorId: Int
    let seq: Int
    let ageMs: Int
}

struct NetworkStatus: Equatable {
    let version: Int
    let uptimeMs: Int64
    let activeSensorCount: Int
    let sensors: [ActiveSensorStatus]
}
