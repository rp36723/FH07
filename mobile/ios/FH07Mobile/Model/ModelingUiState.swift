import Foundation

struct ModelingUiState: Equatable {
    var nodes: [ModeledNodeUiState] = []
    var lastUpdatedAtElapsedMs: Int64? = nil
    var statusMessage: String = "Waiting for sensor samples to render the modeling scene."
}

struct ModeledNodeUiState: Equatable, Identifiable {
    let sensorId: Int
    var placement: SensorPlacement? = nil
    var isLiveInNetworkStatus: Bool = false
    var seq: Int? = nil
    var pitchDeg: Float? = nil
    var rollDeg: Float? = nil
    var gravityVector: ModeledGravityVector? = nil
    var lastSampleReceivedAtElapsedMs: Int64? = nil

    var id: Int { sensorId }
}

struct ModeledGravityVector: Equatable {
    let x: Float
    let y: Float
    let z: Float
}
