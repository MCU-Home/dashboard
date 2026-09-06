// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

// Every literal below is a coordinate, radius or stroke width taken
// from the logo artwork on its 64 by 64 grid. Naming them would not
// explain anything the drawing does not already say.
@file:Suppress("MagicNumber")

package org.mcuhome.ui.brand

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.mcuhome.ui.theme.MCUHomeTheme
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The MCUHome mark, drawn from the geometry of the brand's logo artwork:
 * a house with a door cut out of it, four antenna pins at its sides, and
 * three signal arcs above the roof, laid out on a 64 by 64 grid.
 *
 * It is drawn rather than shipped as an image so that it takes the accent
 * and pin colors of the color scheme in force and stays sharp at any
 * size. The two colors are the only difference between the light and the
 * dark artwork.
 */
@Composable
fun MCUHomeMark(
    modifier: Modifier = Modifier,
    accent: Color = MCUHomeTheme.colors.accent,
    pin: Color = MCUHomeTheme.colors.pinGray,
) {
    Canvas(modifier.semantics { contentDescription = "MCUHome" }) {
        val unit = min(size.width, size.height) / GRID
        withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
            drawSignalArcs(accent)
            drawPins(pin)
            drawHouse(accent)
        }
    }
}

private const val GRID = 64f

private fun DrawScope.drawSignalArcs(accent: Color) {
    val arcs = Path().apply {
        signalArc(startX = 27.1f, startY = 17.1f, endX = 36.9f, endY = 17.1f, radius = 7f)
        signalArc(startX = 23.5f, startY = 13.5f, endX = 40.5f, endY = 13.5f, radius = 12f)
        signalArc(startX = 20f, startY = 10f, endX = 44f, endY = 10f, radius = 17f)
    }
    drawPath(arcs, accent, style = Stroke(width = 3.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
}

private fun DrawScope.drawPins(pin: Color) {
    val pins = Path().apply {
        pinAt(x = 5f, y = 39f)
        pinAt(x = 5f, y = 48f)
        pinAt(x = 51f, y = 39f)
        pinAt(x = 51f, y = 48f)
    }
    drawPath(pins, pin)
}

private fun DrawScope.drawHouse(accent: Color) {
    val house = Path().apply {
        moveTo(16f, 36f)
        lineTo(32f, 22f)
        lineTo(48f, 36f)
        lineTo(48f, 57f)
        lineTo(16f, 57f)
        close()
    }
    val door = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(offset = Offset(27f, 43f), size = Size(10f, 14f)),
                cornerRadius = CornerRadius(3f, 3f),
            ),
        )
    }
    // The artwork widens the house body with a round-joined stroke of its
    // own color, which is what gives the roof and the corners their
    // radius; the door is cut out of body and stroke alike.
    clipPath(door, clipOp = ClipOp.Difference) {
        drawPath(house, accent)
        drawPath(house, accent, style = Stroke(width = 6f, join = StrokeJoin.Round))
    }
}

private fun Path.pinAt(x: Float, y: Float) = addRoundRect(
    RoundRect(
        rect = Rect(offset = Offset(x, y), size = Size(8f, 5f)),
        cornerRadius = CornerRadius(2f, 2f),
    ),
)

/**
 * Appends one signal arc, given the way the artwork states it: two end
 * points and a radius, taking the shorter of the two possible arcs and
 * bending it away from the house. Compose describes an arc by its
 * bounding box and two angles, so the center has to be reconstructed.
 */
private fun Path.signalArc(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    radius: Float,
) {
    val chord = hypot(endX - startX, endY - startY)
    val halfChord = chord / 2f
    val distanceToCenter = sqrt((radius * radius - halfChord * halfChord).coerceAtLeast(0f))
    val directionX = (endX - startX) / chord
    val directionY = (endY - startY) / chord
    val centerX = (startX + endX) / 2f - directionY * distanceToCenter
    val centerY = (startY + endY) / 2f + directionX * distanceToCenter

    val startAngle = degrees(atan2(startY - centerY, startX - centerX))
    var sweep = degrees(atan2(endY - centerY, endX - centerX)) - startAngle
    while (sweep <= 0f) sweep += 360f

    arcTo(
        rect = Rect(
            left = centerX - radius,
            top = centerY - radius,
            right = centerX + radius,
            bottom = centerY + radius,
        ),
        startAngleDegrees = startAngle,
        sweepAngleDegrees = sweep,
        forceMoveTo = true,
    )
}

private fun degrees(radians: Float): Float = radians * 180f / kotlin.math.PI.toFloat()
