package com.example.seniordesignmobileapp.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.seniordesignmobileapp.analysis.SensorPlacement
import com.example.seniordesignmobileapp.model.AggregatorUiState
import com.example.seniordesignmobileapp.model.ModeledNodeUiState
import com.example.seniordesignmobileapp.model.ModelingUiState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun ModelingPage(
    uiState: AggregatorUiState,
    permissionsGranted: Boolean,
    onGrantPermissions: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageContent(
        title = "Modeling",
        uiState = uiState,
        permissionsGranted = permissionsGranted,
        onGrantPermissions = onGrantPermissions,
        onReconnect = onReconnect,
        modifier = modifier,
    ) { elapsedRealtimeMs ->
        ActivitySetupCard(
            title = "Modeling Scope",
            lines = listOf(
                "This first slice is an orientation-only modeling view built from the latest IMU sample for each sensor.",
                "Node positions are schematic: assigned placements anchor nodes to the body, and unassigned nodes stage to the side.",
                "Use this page to inspect live orientation, mounting consistency, and sensor-role assumptions before deeper 3D work exists.",
            ),
        )
        ModelingOverviewCard(
            uiState = uiState,
            elapsedRealtimeMs = elapsedRealtimeMs,
        )
        ModelingSceneCard(
            modeling = uiState.modeling,
            elapsedRealtimeMs = elapsedRealtimeMs,
        )
        NodePoseCard(
            modeling = uiState.modeling,
            elapsedRealtimeMs = elapsedRealtimeMs,
        )
    }
}

@Composable
private fun ModelingOverviewCard(
    uiState: AggregatorUiState,
    elapsedRealtimeMs: Long,
) {
    val modeling = uiState.modeling
    val assignedCount = modeling.nodes.count { it.placement != null }
    val liveCount = modeling.nodes.count { it.isLiveInNetworkStatus }
    val orientedCount = modeling.nodes.count { it.gravityVector != null }

    DetailCard(title = "Modeling Overview") {
        StatRow("Nodes in scene", modeling.nodes.size.toString())
        StatRow("Assigned placements", assignedCount.toString())
        StatRow("Live in network_status", liveCount.toString())
        StatRow("With orientation", orientedCount.toString())
        StatRow("Updated", formatAge(modeling.lastUpdatedAtElapsedMs, elapsedRealtimeMs))
        Text(
            text = modeling.statusMessage,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ModelingSceneCard(
    modeling: ModelingUiState,
    elapsedRealtimeMs: Long,
) {
    DetailCard(title = "Schematic Node Scene") {
        val assignedColor = MaterialTheme.colorScheme.primary
        val unassignedColor = MaterialTheme.colorScheme.secondary
        val offlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
        val arrowColor = MaterialTheme.colorScheme.onSurface
        val outlineColor = MaterialTheme.colorScheme.outline
        val surfaceColor = MaterialTheme.colorScheme.surface
        val sceneBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)

        Text(
            text = "The torso scaffold is fixed for now. Tilt indicators reflect the latest per-sensor gravity estimate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (modeling.nodes.isEmpty()) {
            Text(
                text = "Waiting for sensors before the scene can render.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@DetailCard
        }

        val sceneNodes = buildSceneNodes(modeling.nodes)
        val labelPaint = Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 11.sp.value * 3f
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(sceneBackgroundColor),
        ) {
            val scale = min(size.width, size.height) * 0.24f
            drawSceneGuides(
                scale = scale,
                outlineColor = outlineColor,
            )

            sceneNodes.forEach { sceneNode ->
                val center = sceneNode.position.project(size, scale)
                val fillColor = when {
                    !sceneNode.node.isLiveInNetworkStatus -> offlineColor
                    sceneNode.node.placement != null -> assignedColor
                    else -> unassignedColor
                }
                val nodeOutlineColor = if (sceneNode.node.gravityVector != null) {
                    surfaceColor
                } else {
                    outlineColor
                }

                drawCircle(
                    color = fillColor,
                    radius = 18.dp.toPx(),
                    center = center,
                )
                drawCircle(
                    color = nodeOutlineColor,
                    radius = 18.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )

                sceneNode.orientationVector?.let { orientationVector ->
                    val tip = (sceneNode.position + orientationVector).project(size, scale)
                    drawLine(
                        color = arrowColor,
                        start = center,
                        end = tip,
                        strokeWidth = 4.dp.toPx(),
                    )
                    drawCircle(
                        color = arrowColor,
                        radius = 4.dp.toPx(),
                        center = tip,
                    )
                }

                drawContext.canvas.nativeCanvas.drawText(
                    sceneNode.node.sensorId.toString(),
                    center.x,
                    center.y + 4.dp.toPx(),
                    labelPaint,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LegendPill("Assigned", assignedColor)
            LegendPill("Unassigned", unassignedColor)
            LegendPill("Offline", offlineColor)
        }
        Text(
            text = "Latest scene update: ${formatAge(modeling.lastUpdatedAtElapsedMs, elapsedRealtimeMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NodePoseCard(
    modeling: ModelingUiState,
    elapsedRealtimeMs: Long,
) {
    DetailCard(title = "Node Poses") {
        if (modeling.nodes.isEmpty()) {
            Text("No nodes to inspect yet.")
            return@DetailCard
        }

        modeling.nodes
            .sortedWith(compareBy<ModeledNodeUiState>({ placementPriority(it.placement) }, { it.sensorId }))
            .forEachIndexed { index, node ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                Text(
                    text = "Sensor ${node.sensorId}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                StatRow("Placement", formatPlacement(node.placement))
                StatRow("Live in status", if (node.isLiveInNetworkStatus) "Yes" else "No")
                StatRow("Last sequence", node.seq?.toString() ?: "Waiting")
                StatRow("Sample age", formatAge(node.lastSampleReceivedAtElapsedMs, elapsedRealtimeMs))
                StatRow("Pitch", formatDegrees(node.pitchDeg))
                StatRow("Roll", formatDegrees(node.rollDeg))
                StatRow("Gravity", formatGravity(node))
            }
    }
}

@Composable
private fun LegendPill(
    label: String,
    color: Color,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private data class SceneNode(
    val node: ModeledNodeUiState,
    val position: SceneVector3,
    val orientationVector: SceneVector3?,
)

private data class SceneVector3(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    fun project(
        canvasSize: Size,
        scale: Float,
    ): Offset {
        val projectedX = canvasSize.width * 0.48f + ((x - (z * 0.7f)) * scale)
        val projectedY = canvasSize.height * 0.72f - (y * scale) + (z * scale * 0.35f)
        return Offset(projectedX, projectedY)
    }

    operator fun plus(other: SceneVector3): SceneVector3 =
        SceneVector3(
            x = x + other.x,
            y = y + other.y,
            z = z + other.z,
        )
}

private fun buildSceneNodes(
    nodes: List<ModeledNodeUiState>,
): List<SceneNode> {
    var stagedIndex = 0
    return nodes
        .sortedWith(compareBy<ModeledNodeUiState>({ placementPriority(it.placement) }, { it.sensorId }))
        .map { node ->
            val position = positionForNode(
                node = node,
                stagedIndex = stagedIndex,
            )
            if (node.placement == null) {
                stagedIndex += 1
            }
            SceneNode(
                node = node,
                position = position,
                orientationVector = orientationVector(node),
            )
        }
}

private fun positionForNode(
    node: ModeledNodeUiState,
    stagedIndex: Int,
): SceneVector3 =
    when (node.placement) {
        SensorPlacement.HEAD -> SceneVector3(0f, 1.35f, 0f)
        SensorPlacement.CHEST -> SceneVector3(0f, 0.95f, -0.04f)
        SensorPlacement.UPPER_BACK -> SceneVector3(0f, 0.75f, 0.08f)
        SensorPlacement.LOWER_BACK -> SceneVector3(0f, 0.22f, 0.12f)
        SensorPlacement.LEFT_HIP -> SceneVector3(-0.38f, -0.05f, 0f)
        SensorPlacement.RIGHT_HIP -> SceneVector3(0.38f, -0.05f, 0f)
        SensorPlacement.LEFT_THIGH -> SceneVector3(-0.42f, -0.72f, 0.05f)
        SensorPlacement.RIGHT_THIGH -> SceneVector3(0.42f, -0.72f, 0.05f)
        SensorPlacement.UNKNOWN, null -> SceneVector3(
            x = 1.2f,
            y = 0.9f - (stagedIndex * 0.38f),
            z = if (stagedIndex % 2 == 0) 0f else 0.14f,
        )
    }

private fun orientationVector(
    node: ModeledNodeUiState,
): SceneVector3? {
    val pitchDeg = node.pitchDeg ?: return null
    val rollDeg = node.rollDeg ?: return null
    val pitchRad = Math.toRadians(pitchDeg.toDouble())
    val rollRad = Math.toRadians(rollDeg.toDouble())
    return SceneVector3(
        x = sin(rollRad).toFloat() * 0.28f,
        y = cos(pitchRad).toFloat() * 0.35f,
        z = sin(pitchRad).toFloat() * 0.28f,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSceneGuides(
    scale: Float,
    outlineColor: Color,
) {
    val upperBack = SceneVector3(0f, 0.75f, 0.08f).project(size, scale)
    val lowerBack = SceneVector3(0f, 0.22f, 0.12f).project(size, scale)
    val leftHip = SceneVector3(-0.38f, -0.05f, 0f).project(size, scale)
    val rightHip = SceneVector3(0.38f, -0.05f, 0f).project(size, scale)
    val leftThigh = SceneVector3(-0.42f, -0.72f, 0.05f).project(size, scale)
    val rightThigh = SceneVector3(0.42f, -0.72f, 0.05f).project(size, scale)

    drawLine(
        color = outlineColor.copy(alpha = 0.6f),
        start = upperBack,
        end = lowerBack,
        strokeWidth = 3.dp.toPx(),
    )
    drawLine(
        color = outlineColor.copy(alpha = 0.45f),
        start = leftHip,
        end = rightHip,
        strokeWidth = 2.dp.toPx(),
    )
    drawLine(
        color = outlineColor.copy(alpha = 0.4f),
        start = leftHip,
        end = leftThigh,
        strokeWidth = 2.dp.toPx(),
    )
    drawLine(
        color = outlineColor.copy(alpha = 0.4f),
        start = rightHip,
        end = rightThigh,
        strokeWidth = 2.dp.toPx(),
    )
}

private fun formatPlacement(
    placement: SensorPlacement?,
): String =
    placement?.name
        ?.lowercase()
        ?.replace('_', ' ')
        ?.replaceFirstChar { it.titlecase() }
        ?: "Unassigned"

private fun formatGravity(
    node: ModeledNodeUiState,
): String =
    node.gravityVector?.let { vector ->
        String.format(
            java.util.Locale.US,
            "(%.2f, %.2f, %.2f)",
            vector.x,
            vector.y,
            vector.z,
        )
    } ?: "Waiting"

private fun placementPriority(
    placement: SensorPlacement?,
): Int =
    when (placement) {
        SensorPlacement.HEAD -> 0
        SensorPlacement.CHEST -> 1
        SensorPlacement.UPPER_BACK -> 2
        SensorPlacement.LOWER_BACK -> 3
        SensorPlacement.LEFT_HIP -> 4
        SensorPlacement.RIGHT_HIP -> 5
        SensorPlacement.LEFT_THIGH -> 6
        SensorPlacement.RIGHT_THIGH -> 7
        SensorPlacement.UNKNOWN -> 8
        null -> 9
    }
