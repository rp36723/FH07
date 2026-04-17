package com.example.seniordesignmobileapp.ble

import com.example.seniordesignmobileapp.model.ActiveSensorStatus
import com.example.seniordesignmobileapp.model.ImuSample
import com.example.seniordesignmobileapp.model.NetworkStatus
import java.util.Locale
import java.util.UUID

const val AGGREGATOR_DEVICE_NAME = "BLE-Aggregator"
const val PROTOCOL_VERSION = 1
const val SAMPLE_PAYLOAD_LEN = 20

val GATT_SERVICE_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abc0000")
val SAMPLE_STREAM_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abc0001")
val NETWORK_STATUS_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abc0002")

private const val NETWORK_STATUS_HEADER_LEN = 6
private const val NETWORK_STATUS_ENTRY_LEN = 5

fun decodeImuSample(payload: ByteArray): ImuSample? {
    if (payload.size != SAMPLE_PAYLOAD_LEN) {
        return null
    }

    return ImuSample(
        version = payload.readU8(0),
        sensorId = payload.readU8(1),
        seq = payload.readU16Le(2),
        timestampMs = payload.readU32Le(4),
        ax = payload.readI16Le(8),
        ay = payload.readI16Le(10),
        az = payload.readI16Le(12),
        gx = payload.readI16Le(14),
        gy = payload.readI16Le(16),
        gz = payload.readI16Le(18),
    ).takeIf { it.version == PROTOCOL_VERSION }
}

fun decodeNetworkStatus(payload: ByteArray): NetworkStatus? {
    if (payload.size < NETWORK_STATUS_HEADER_LEN) {
        return null
    }

    val version = payload.readU8(0)
    val uptimeMs = payload.readU32Le(1)
    val activeSensorCount = payload.readU8(5)
    val expectedLength = NETWORK_STATUS_HEADER_LEN + activeSensorCount * NETWORK_STATUS_ENTRY_LEN
    if (payload.size != expectedLength || version != PROTOCOL_VERSION) {
        return null
    }

    val sensors = buildList(activeSensorCount) {
        var offset = NETWORK_STATUS_HEADER_LEN
        repeat(activeSensorCount) {
            add(
                ActiveSensorStatus(
                    sensorId = payload.readU8(offset),
                    seq = payload.readU16Le(offset + 1),
                    ageMs = payload.readU16Le(offset + 3),
                )
            )
            offset += NETWORK_STATUS_ENTRY_LEN
        }
    }

    return NetworkStatus(
        version = version,
        uptimeMs = uptimeMs,
        activeSensorCount = activeSensorCount,
        sensors = sensors,
    )
}

fun ByteArray.toHexString(): String =
    joinToString(separator = " ") { byte ->
        String.format(Locale.US, "%02X", byte.toInt() and 0xFF)
    }

private fun ByteArray.readU8(offset: Int): Int = get(offset).toInt() and 0xFF

private fun ByteArray.readI16Le(offset: Int): Int =
    ((get(offset + 1).toInt() shl 8) or (get(offset).toInt() and 0xFF)).toShort().toInt()

private fun ByteArray.readU16Le(offset: Int): Int =
    ((get(offset + 1).toInt() and 0xFF) shl 8) or (get(offset).toInt() and 0xFF)

private fun ByteArray.readU32Le(offset: Int): Long =
    ((get(offset + 3).toLong() and 0xFF) shl 24) or
        ((get(offset + 2).toLong() and 0xFF) shl 16) or
        ((get(offset + 1).toLong() and 0xFF) shl 8) or
        (get(offset).toLong() and 0xFF)
