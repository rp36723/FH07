package com.example.seniordesignmobileapp.modeling

import com.example.seniordesignmobileapp.model.ImuSample
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

data class NodePoseEstimate(
    val pitchDeg: Float,
    val rollDeg: Float,
    val gravityVector: NormalizedVector3,
)

data class NormalizedVector3(
    val x: Float,
    val y: Float,
    val z: Float,
)

object NodePoseMath {
    fun fromSample(
        sample: ImuSample,
    ): NodePoseEstimate? {
        val ax = sample.ax.toDouble()
        val ay = sample.ay.toDouble()
        val az = sample.az.toDouble()
        val magnitude = sqrt(ax.pow(2) + ay.pow(2) + az.pow(2))
        if (magnitude == 0.0) {
            return null
        }

        val gravityVector = NormalizedVector3(
            x = (ax / magnitude).toFloat(),
            y = (ay / magnitude).toFloat(),
            z = (az / magnitude).toFloat(),
        )
        val pitchDeg = Math.toDegrees(
            atan2(
                gravityVector.x.toDouble(),
                sqrt(gravityVector.y.toDouble().pow(2) + gravityVector.z.toDouble().pow(2)),
            )
        ).toFloat()
        val rollDeg = Math.toDegrees(
            atan2(
                gravityVector.y.toDouble(),
                gravityVector.z.toDouble(),
            )
        ).toFloat()

        return NodePoseEstimate(
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            gravityVector = gravityVector,
        )
    }
}
