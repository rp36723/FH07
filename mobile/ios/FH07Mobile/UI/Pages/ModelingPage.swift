import SwiftUI

struct ModelingPage: View {
    let uiState: AggregatorUiState
    let permissionsGranted: Bool
    let onGrantPermissions: () -> Void
    let onReconnect: () -> Void

    var body: some View {
        PageContent(
            title: "Modeling",
            uiState: uiState,
            permissionsGranted: permissionsGranted,
            onGrantPermissions: onGrantPermissions,
            onReconnect: onReconnect
        ) { elapsedRealtimeMs in
            ActivitySetupCard(
                title: "Modeling Scope",
                lines: [
                    "This first slice is an orientation-only modeling view built from the latest IMU sample for each sensor.",
                    "Node positions are schematic: assigned placements anchor nodes to the body, and unassigned nodes stage to the side.",
                    "Use this page to inspect live orientation, mounting consistency, and sensor-role assumptions before deeper 3D work exists.",
                ]
            )
            ModelingOverviewCard(
                uiState: uiState,
                elapsedRealtimeMs: elapsedRealtimeMs
            )
            ModelingSceneCard(
                modeling: uiState.modeling,
                elapsedRealtimeMs: elapsedRealtimeMs
            )
            NodePoseCard(
                modeling: uiState.modeling,
                elapsedRealtimeMs: elapsedRealtimeMs
            )
        }
    }
}

private struct ModelingOverviewCard: View {
    let uiState: AggregatorUiState
    let elapsedRealtimeMs: Int64

    var body: some View {
        let modeling = uiState.modeling
        let assignedCount = modeling.nodes.filter { $0.placement != nil }.count
        let liveCount = modeling.nodes.filter { $0.isLiveInNetworkStatus }.count
        let orientedCount = modeling.nodes.filter { $0.gravityVector != nil }.count

        DetailCard(title: "Modeling Overview") {
            StatRow(label: "Nodes in scene", value: String(modeling.nodes.count))
            StatRow(label: "Assigned placements", value: String(assignedCount))
            StatRow(label: "Live in network_status", value: String(liveCount))
            StatRow(label: "With orientation", value: String(orientedCount))
            StatRow(label: "Updated", value: formatAge(modeling.lastUpdatedAtElapsedMs, elapsedRealtimeMs))
            Text(modeling.statusMessage)
                .font(.body)
        }
    }
}

private struct ModelingSceneCard: View {
    let modeling: ModelingUiState
    let elapsedRealtimeMs: Int64

    var body: some View {
        DetailCard(title: "Schematic Node Scene") {
            Text("The torso scaffold is fixed for now. Tilt indicators reflect the latest per-sensor gravity estimate.")
                .font(.caption)
                .foregroundColor(AppTheme.onSurfaceVariant)

            if modeling.nodes.isEmpty {
                Text("Waiting for sensors before the scene can render.")
                    .font(.body)
            } else {
                let sceneNodes = buildSceneNodes(modeling.nodes)
                Canvas { context, size in
                    let scale = min(size.width, size.height) * 0.24
                    drawSceneGuides(context: &context, size: size, scale: scale)

                    for sceneNode in sceneNodes {
                        let center = sceneNode.position.project(canvasSize: size, scale: scale)
                        let fillColor: Color
                        if !sceneNode.node.isLiveInNetworkStatus {
                            fillColor = AppTheme.outline.opacity(0.75)
                        } else if sceneNode.node.placement != nil {
                            fillColor = AppTheme.primary
                        } else {
                            fillColor = AppTheme.secondary
                        }
                        let nodeOutlineColor: Color = sceneNode.node.gravityVector != nil
                            ? AppTheme.surface
                            : AppTheme.outline

                        let radius: CGFloat = 18
                        let circleRect = CGRect(
                            x: center.x - radius,
                            y: center.y - radius,
                            width: radius * 2,
                            height: radius * 2
                        )
                        context.fill(Path(ellipseIn: circleRect), with: .color(fillColor))
                        context.stroke(
                            Path(ellipseIn: circleRect),
                            with: .color(nodeOutlineColor),
                            lineWidth: 2
                        )

                        if let orientationVector = sceneNode.orientationVector {
                            let tip = (sceneNode.position + orientationVector)
                                .project(canvasSize: size, scale: scale)
                            var line = Path()
                            line.move(to: center)
                            line.addLine(to: tip)
                            context.stroke(line, with: .color(Color.primary), lineWidth: 4)

                            let tipRadius: CGFloat = 4
                            let tipRect = CGRect(
                                x: tip.x - tipRadius,
                                y: tip.y - tipRadius,
                                width: tipRadius * 2,
                                height: tipRadius * 2
                            )
                            context.fill(Path(ellipseIn: tipRect), with: .color(Color.primary))
                        }

                        let label = Text(String(sceneNode.node.sensorId))
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                        context.draw(label, at: CGPoint(x: center.x, y: center.y + 4))
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 320)
                .background(AppTheme.surfaceVariant.opacity(0.3))
                .clipShape(RoundedRectangle(cornerRadius: 18))

                Divider().padding(.vertical, 8)
                HStack(spacing: 12) {
                    LegendPill(label: "Assigned", color: AppTheme.primary)
                    LegendPill(label: "Unassigned", color: AppTheme.secondary)
                    LegendPill(label: "Offline", color: AppTheme.outline.opacity(0.75))
                }
                Text("Latest scene update: \(formatAge(modeling.lastUpdatedAtElapsedMs, elapsedRealtimeMs))")
                    .font(.caption)
                    .foregroundColor(AppTheme.onSurfaceVariant)
            }
        }
    }
}

private struct NodePoseCard: View {
    let modeling: ModelingUiState
    let elapsedRealtimeMs: Int64

    var body: some View {
        DetailCard(title: "Node Poses") {
            if modeling.nodes.isEmpty {
                Text("No nodes to inspect yet.")
            } else {
                let sorted = modeling.nodes.sorted { lhs, rhs in
                    let lp = placementPriority(lhs.placement)
                    let rp = placementPriority(rhs.placement)
                    if lp != rp { return lp < rp }
                    return lhs.sensorId < rhs.sensorId
                }
                ForEach(Array(sorted.enumerated()), id: \.element.sensorId) { index, node in
                    if index > 0 {
                        Divider().padding(.vertical, 8)
                    }
                    Text("Sensor \(node.sensorId)")
                        .font(.subheadline.weight(.semibold))
                    StatRow(label: "Placement", value: formatPlacement(node.placement))
                    StatRow(label: "Live in status", value: node.isLiveInNetworkStatus ? "Yes" : "No")
                    StatRow(label: "Last sequence", value: node.seq.map { String($0) } ?? "Waiting")
                    StatRow(label: "Sample age", value: formatAge(node.lastSampleReceivedAtElapsedMs, elapsedRealtimeMs))
                    StatRow(label: "Pitch", value: formatDegrees(node.pitchDeg))
                    StatRow(label: "Roll", value: formatDegrees(node.rollDeg))
                    StatRow(label: "Gravity", value: formatGravity(node))
                }
            }
        }
    }
}

private struct LegendPill: View {
    let label: String
    let color: Color

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(color)
                .frame(width: 10, height: 10)
                .padding(.top, 2)
            Text(label)
                .font(.caption)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(
            Capsule()
                .fill(color.opacity(0.18))
        )
    }
}

// MARK: - Scene math

private struct SceneNode {
    let node: ModeledNodeUiState
    let position: SceneVector3
    let orientationVector: SceneVector3?
}

private struct SceneVector3 {
    let x: Float
    let y: Float
    let z: Float

    func project(canvasSize: CGSize, scale: CGFloat) -> CGPoint {
        let s = CGFloat(scale)
        let px = canvasSize.width * 0.48 + (CGFloat(x - (z * 0.7)) * s)
        let py = canvasSize.height * 0.72 - (CGFloat(y) * s) + (CGFloat(z) * s * 0.35)
        return CGPoint(x: px, y: py)
    }

    static func + (lhs: SceneVector3, rhs: SceneVector3) -> SceneVector3 {
        SceneVector3(x: lhs.x + rhs.x, y: lhs.y + rhs.y, z: lhs.z + rhs.z)
    }
}

private func buildSceneNodes(_ nodes: [ModeledNodeUiState]) -> [SceneNode] {
    var stagedIndex = 0
    let sorted = nodes.sorted { lhs, rhs in
        let lp = placementPriority(lhs.placement)
        let rp = placementPriority(rhs.placement)
        if lp != rp { return lp < rp }
        return lhs.sensorId < rhs.sensorId
    }
    var result: [SceneNode] = []
    for node in sorted {
        let position = positionForNode(node: node, stagedIndex: stagedIndex)
        if node.placement == nil {
            stagedIndex += 1
        }
        result.append(SceneNode(
            node: node,
            position: position,
            orientationVector: orientationVector(node: node)
        ))
    }
    return result
}

private func positionForNode(node: ModeledNodeUiState, stagedIndex: Int) -> SceneVector3 {
    switch node.placement {
    case .head: return SceneVector3(x: 0, y: 1.35, z: 0)
    case .chest: return SceneVector3(x: 0, y: 0.95, z: -0.04)
    case .upperBack: return SceneVector3(x: 0, y: 0.75, z: 0.08)
    case .lowerBack: return SceneVector3(x: 0, y: 0.22, z: 0.12)
    case .leftHip: return SceneVector3(x: -0.38, y: -0.05, z: 0)
    case .rightHip: return SceneVector3(x: 0.38, y: -0.05, z: 0)
    case .leftThigh: return SceneVector3(x: -0.42, y: -0.72, z: 0.05)
    case .rightThigh: return SceneVector3(x: 0.42, y: -0.72, z: 0.05)
    case .unknown, .none:
        return SceneVector3(
            x: 1.2,
            y: 0.9 - (Float(stagedIndex) * 0.38),
            z: stagedIndex % 2 == 0 ? 0 : 0.14
        )
    }
}

private func orientationVector(node: ModeledNodeUiState) -> SceneVector3? {
    guard let pitchDeg = node.pitchDeg, let rollDeg = node.rollDeg else { return nil }
    let pitchRad = Double(pitchDeg) * .pi / 180.0
    let rollRad = Double(rollDeg) * .pi / 180.0
    return SceneVector3(
        x: Float(sin(rollRad)) * 0.28,
        y: Float(cos(pitchRad)) * 0.35,
        z: Float(sin(pitchRad)) * 0.28
    )
}

private func drawSceneGuides(context: inout GraphicsContext, size: CGSize, scale: CGFloat) {
    let upperBack = SceneVector3(x: 0, y: 0.75, z: 0.08).project(canvasSize: size, scale: scale)
    let lowerBack = SceneVector3(x: 0, y: 0.22, z: 0.12).project(canvasSize: size, scale: scale)
    let leftHip = SceneVector3(x: -0.38, y: -0.05, z: 0).project(canvasSize: size, scale: scale)
    let rightHip = SceneVector3(x: 0.38, y: -0.05, z: 0).project(canvasSize: size, scale: scale)
    let leftThigh = SceneVector3(x: -0.42, y: -0.72, z: 0.05).project(canvasSize: size, scale: scale)
    let rightThigh = SceneVector3(x: 0.42, y: -0.72, z: 0.05).project(canvasSize: size, scale: scale)

    func drawLine(_ a: CGPoint, _ b: CGPoint, color: Color, width: CGFloat) {
        var p = Path()
        p.move(to: a)
        p.addLine(to: b)
        context.stroke(p, with: .color(color), lineWidth: width)
    }

    drawLine(upperBack, lowerBack, color: AppTheme.outline.opacity(0.6), width: 3)
    drawLine(leftHip, rightHip, color: AppTheme.outline.opacity(0.45), width: 2)
    drawLine(leftHip, leftThigh, color: AppTheme.outline.opacity(0.4), width: 2)
    drawLine(rightHip, rightThigh, color: AppTheme.outline.opacity(0.4), width: 2)
}

private func formatPlacement(_ placement: SensorPlacement?) -> String {
    guard let placement = placement else { return "Unassigned" }
    let raw = placement.rawValue.lowercased().replacingOccurrences(of: "_", with: " ")
    return raw.prefix(1).uppercased() + raw.dropFirst()
}

private func formatGravity(_ node: ModeledNodeUiState) -> String {
    guard let vector = node.gravityVector else { return "Waiting" }
    return String(format: "(%.2f, %.2f, %.2f)", vector.x, vector.y, vector.z)
}

private func placementPriority(_ placement: SensorPlacement?) -> Int {
    switch placement {
    case .head: return 0
    case .chest: return 1
    case .upperBack: return 2
    case .lowerBack: return 3
    case .leftHip: return 4
    case .rightHip: return 5
    case .leftThigh: return 6
    case .rightThigh: return 7
    case .unknown: return 8
    case .none: return 9
    }
}
