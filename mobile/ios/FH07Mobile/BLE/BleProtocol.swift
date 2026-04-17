import Foundation
import CoreBluetooth

let AGGREGATOR_DEVICE_NAME = "BLE-Aggregator"
let PROTOCOL_VERSION = 1
let SAMPLE_PAYLOAD_LEN = 20

let GATT_SERVICE_UUID = CBUUID(string: "12345678-1234-5678-1234-56789abc0000")
let SAMPLE_STREAM_UUID = CBUUID(string: "12345678-1234-5678-1234-56789abc0001")
let NETWORK_STATUS_UUID = CBUUID(string: "12345678-1234-5678-1234-56789abc0002")

private let NETWORK_STATUS_HEADER_LEN = 6
private let NETWORK_STATUS_ENTRY_LEN = 5

func decodeImuSample(_ payload: Data) -> ImuSample? {
    guard payload.count == SAMPLE_PAYLOAD_LEN else { return nil }

    let sample = ImuSample(
        version: payload.readU8(0),
        sensorId: payload.readU8(1),
        seq: payload.readU16Le(2),
        timestampMs: payload.readU32Le(4),
        ax: payload.readI16Le(8),
        ay: payload.readI16Le(10),
        az: payload.readI16Le(12),
        gx: payload.readI16Le(14),
        gy: payload.readI16Le(16),
        gz: payload.readI16Le(18)
    )
    return sample.version == PROTOCOL_VERSION ? sample : nil
}

func decodeNetworkStatus(_ payload: Data) -> NetworkStatus? {
    guard payload.count >= NETWORK_STATUS_HEADER_LEN else { return nil }

    let version = payload.readU8(0)
    let uptimeMs = payload.readU32Le(1)
    let activeSensorCount = payload.readU8(5)
    let expectedLength = NETWORK_STATUS_HEADER_LEN + activeSensorCount * NETWORK_STATUS_ENTRY_LEN
    guard payload.count == expectedLength, version == PROTOCOL_VERSION else {
        return nil
    }

    var sensors: [ActiveSensorStatus] = []
    sensors.reserveCapacity(activeSensorCount)
    var offset = NETWORK_STATUS_HEADER_LEN
    for _ in 0..<activeSensorCount {
        sensors.append(
            ActiveSensorStatus(
                sensorId: payload.readU8(offset),
                seq: payload.readU16Le(offset + 1),
                ageMs: payload.readU16Le(offset + 3)
            )
        )
        offset += NETWORK_STATUS_ENTRY_LEN
    }

    return NetworkStatus(
        version: version,
        uptimeMs: uptimeMs,
        activeSensorCount: activeSensorCount,
        sensors: sensors
    )
}

extension Data {
    func toHexString() -> String {
        map { String(format: "%02X", $0) }.joined(separator: " ")
    }

    fileprivate func readU8(_ offset: Int) -> Int {
        Int(self[self.startIndex + offset])
    }

    fileprivate func readI16Le(_ offset: Int) -> Int {
        let lo = Int(self[self.startIndex + offset])
        let hi = Int(self[self.startIndex + offset + 1])
        let unsigned = (hi << 8) | lo
        let signed = Int16(truncatingIfNeeded: unsigned)
        return Int(signed)
    }

    fileprivate func readU16Le(_ offset: Int) -> Int {
        let lo = Int(self[self.startIndex + offset])
        let hi = Int(self[self.startIndex + offset + 1])
        return (hi << 8) | lo
    }

    fileprivate func readU32Le(_ offset: Int) -> Int64 {
        let b0 = Int64(self[self.startIndex + offset])
        let b1 = Int64(self[self.startIndex + offset + 1])
        let b2 = Int64(self[self.startIndex + offset + 2])
        let b3 = Int64(self[self.startIndex + offset + 3])
        return (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
    }
}
