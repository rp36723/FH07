package com.example.seniordesignmobileapp.ble

import com.example.seniordesignmobileapp.model.ActiveSensorStatus
import com.example.seniordesignmobileapp.model.ImuSample
import com.example.seniordesignmobileapp.model.NetworkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleProtocolTest {
    @Test
    fun decodeImuSample_returnsExpectedValues() {
        val payload = bytes(
            0x01, 0x07, 0x2A, 0x00, 0x39, 0x05, 0x00, 0x00,
            0x01, 0x00, 0xFE, 0xFF, 0x03, 0x00, 0xFC, 0xFF,
            0x05, 0x00, 0xFA, 0xFF,
        )

        val decoded = decodeImuSample(payload)

        assertEquals(
            ImuSample(
                version = 1,
                sensorId = 7,
                seq = 42,
                timestampMs = 1_337,
                ax = 1,
                ay = -2,
                az = 3,
                gx = -4,
                gy = 5,
                gz = -6,
            ),
            decoded,
        )
    }

    @Test
    fun decodeImuSample_rejectsWrongVersion() {
        val payload = bytes(
            0x02, 0x07, 0x2A, 0x00, 0x39, 0x05, 0x00, 0x00,
            0x01, 0x00, 0xFE, 0xFF, 0x03, 0x00, 0xFC, 0xFF,
            0x05, 0x00, 0xFA, 0xFF,
        )

        assertNull(decodeImuSample(payload))
    }

    @Test
    fun decodeImuSample_rejectsWrongLength() {
        val tooShortPayload = bytes(
            0x01, 0x07, 0x2A, 0x00, 0x39, 0x05, 0x00, 0x00,
            0x01, 0x00, 0xFE, 0xFF, 0x03, 0x00, 0xFC, 0xFF,
            0x05, 0x00, 0xFA,
        )
        val tooLongPayload = bytes(
            0x01, 0x07, 0x2A, 0x00, 0x39, 0x05, 0x00, 0x00,
            0x01, 0x00, 0xFE, 0xFF, 0x03, 0x00, 0xFC, 0xFF,
            0x05, 0x00, 0xFA, 0xFF, 0x00,
        )

        assertNull(decodeImuSample(tooShortPayload))
        assertNull(decodeImuSample(tooLongPayload))
    }

    @Test
    fun decodeNetworkStatus_returnsExpectedValues() {
        val payload = bytes(
            0x01, 0xF4, 0x01, 0x00, 0x00, 0x02,
            0x01, 0x04, 0x00, 0xF4, 0x01,
            0x03, 0x07, 0x00, 0x14, 0x00,
        )

        val decoded = decodeNetworkStatus(payload)

        assertEquals(
            NetworkStatus(
                version = 1,
                uptimeMs = 500,
                activeSensorCount = 2,
                sensors = listOf(
                    ActiveSensorStatus(sensorId = 1, seq = 4, ageMs = 500),
                    ActiveSensorStatus(sensorId = 3, seq = 7, ageMs = 20),
                ),
            ),
            decoded,
        )
    }

    @Test
    fun decodeNetworkStatus_acceptsZeroSensors() {
        val payload = bytes(
            0x01, 0x39, 0x30, 0x00, 0x00, 0x00,
        )

        val decoded = decodeNetworkStatus(payload)

        assertEquals(
            NetworkStatus(
                version = 1,
                uptimeMs = 12_345,
                activeSensorCount = 0,
                sensors = emptyList(),
            ),
            decoded,
        )
    }

    @Test
    fun decodeNetworkStatus_rejectsTruncatedPayload() {
        val payload = bytes(
            0x01, 0xF4, 0x01, 0x00, 0x00, 0x02,
            0x01, 0x04, 0x00, 0xF4, 0x01,
        )

        assertNull(decodeNetworkStatus(payload))
    }

    @Test
    fun decodeNetworkStatus_rejectsWrongVersion() {
        val payload = bytes(
            0x02, 0xF4, 0x01, 0x00, 0x00, 0x00,
        )

        assertNull(decodeNetworkStatus(payload))
    }

    @Test
    fun decodeNetworkStatus_rejectsMismatchedEntryCount() {
        val tooFewEntries = bytes(
            0x01, 0xF4, 0x01, 0x00, 0x00, 0x01,
        )
        val extraTrailingBytes = bytes(
            0x01, 0xF4, 0x01, 0x00, 0x00, 0x01,
            0x01, 0x04, 0x00, 0xF4, 0x01,
            0xFF,
        )

        assertNull(decodeNetworkStatus(tooFewEntries))
        assertNull(decodeNetworkStatus(extraTrailingBytes))
    }

    @Test
    fun toHexString_formatsBytesAsUppercaseHex() {
        val payload = bytes(0x00, 0x0A, 0x8F, 0xFF)

        assertEquals("00 0A 8F FF", payload.toHexString())
    }

    private fun bytes(vararg values: Int): ByteArray =
        values.map(Int::toByte).toByteArray()
}
