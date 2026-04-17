package com.example.seniordesignmobileapp.modeling

import com.example.seniordesignmobileapp.model.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodePoseMathTest {
    @Test
    fun fromSample_returnsFlatPoseForPureZGravity() {
        val pose = NodePoseMath.fromSample(
            sample(
                ax = 0,
                ay = 0,
                az = 1000,
            ),
        )

        requireNotNull(pose)
        assertEquals(0f, pose.pitchDeg, 0.01f)
        assertEquals(0f, pose.rollDeg, 0.01f)
        assertEquals(0f, pose.gravityVector.x, 0.001f)
        assertEquals(0f, pose.gravityVector.y, 0.001f)
        assertEquals(1f, pose.gravityVector.z, 0.001f)
    }

    @Test
    fun fromSample_returnsPositivePitchForForwardTilt() {
        val pose = NodePoseMath.fromSample(
            sample(
                ax = 1000,
                ay = 0,
                az = 0,
            ),
        )

        requireNotNull(pose)
        assertEquals(90f, pose.pitchDeg, 0.01f)
        assertEquals(0f, pose.rollDeg, 0.01f)
    }

    @Test
    fun fromSample_returnsNullForZeroVector() {
        val pose = NodePoseMath.fromSample(
            sample(
                ax = 0,
                ay = 0,
                az = 0,
            ),
        )

        assertNull(pose)
    }

    private fun sample(
        ax: Int,
        ay: Int,
        az: Int,
    ): ImuSample =
        ImuSample(
            version = 1,
            sensorId = 3,
            seq = 10,
            timestampMs = 123,
            ax = ax,
            ay = ay,
            az = az,
            gx = 0,
            gy = 0,
            gz = 0,
        )
}
