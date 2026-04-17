import Foundation

struct NodePoseEstimate: Equatable {
    let pitchDeg: Float
    let rollDeg: Float
    let gravityVector: NormalizedVector3
}

struct NormalizedVector3: Equatable {
    let x: Float
    let y: Float
    let z: Float
}

enum NodePoseMath {
    static func fromSample(_ sample: ImuSample) -> NodePoseEstimate? {
        let ax = Double(sample.ax)
        let ay = Double(sample.ay)
        let az = Double(sample.az)
        let magnitude = (ax * ax + ay * ay + az * az).squareRoot()
        if magnitude == 0 { return nil }

        let gravityVector = NormalizedVector3(
            x: Float(ax / magnitude),
            y: Float(ay / magnitude),
            z: Float(az / magnitude)
        )
        let gx = Double(gravityVector.x)
        let gy = Double(gravityVector.y)
        let gz = Double(gravityVector.z)
        let pitchDeg = Float(atan2(gx, (gy * gy + gz * gz).squareRoot()) * 180.0 / .pi)
        let rollDeg = Float(atan2(gy, gz) * 180.0 / .pi)

        return NodePoseEstimate(pitchDeg: pitchDeg, rollDeg: rollDeg, gravityVector: gravityVector)
    }
}
