// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

// Every literal below is a coordinate on the icons' shared 24 by 24
// viewport. Naming them would not explain anything the drawing does not
// already say.
@file:Suppress("MagicNumber")

package org.mcuhome.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The interface's complete icon set, drawn as vector paths rather than
 * taken from an icon font or emoji. A font would pull in glyphs the
 * interface never uses and would tie every icon's weight and alignment to
 * whatever the font happens to ship; drawing the nine shapes the interface
 * actually needs keeps them consistent with each other and with the rest
 * of the theme, which tints them through `Icon`'s `tint` parameter.
 */
object MCUHomeIcons {
    /** The "valid" pill. */
    val check: ImageVector by lazy {
        icon("check") {
            moveTo(5f, 12.5f)
            lineTo(9.5f, 17.5f)
            lineTo(19f, 6.5f)
        }
    }

    /** The "n errors" pill. */
    val errorCircle: ImageVector by lazy {
        icon("errorCircle") {
            moveTo(3f, 12f)
            arcTo(9f, 9f, 0f, true, true, 21f, 12f)
            arcTo(9f, 9f, 0f, true, true, 3f, 12f)
            moveTo(12f, 7.5f)
            lineTo(12f, 12.8f)
            moveTo(12f, 16.3f)
            lineTo(12f, 16.4f)
        }
    }

    /** A row's menu button. */
    val dots: ImageVector by lazy {
        icon("dots") {
            moveTo(5.5f, 12f)
            lineTo(5.6f, 12f)
            moveTo(12f, 12f)
            lineTo(12.1f, 12f)
            moveTo(18.4f, 12f)
            lineTo(18.5f, 12f)
        }
    }

    /** Sort direction in a table header, and the jobs chip. */
    val chevronDown: ImageVector by lazy {
        icon("chevronDown") {
            moveTo(6f, 9.5f)
            lineTo(12f, 15.5f)
            lineTo(18f, 9.5f)
        }
    }

    /** Sort direction in a table header, and the jobs chip. */
    val chevronUp: ImageVector by lazy {
        icon("chevronUp") {
            moveTo(6f, 14.5f)
            lineTo(12f, 8.5f)
            lineTo(18f, 14.5f)
        }
    }

    /** Jumping to a device from the jobs popover. */
    val chevronRight: ImageVector by lazy {
        icon("chevronRight") {
            moveTo(9.5f, 5.5f)
            lineTo(16f, 12f)
            lineTo(9.5f, 18.5f)
        }
    }

    /** "New device" and "Create device". */
    val plus: ImageVector by lazy {
        icon("plus") {
            moveTo(12f, 5f)
            lineTo(12f, 19f)
            moveTo(5f, 12f)
            lineTo(19f, 12f)
        }
    }

    /** The filter field and board-search fields. */
    val search: ImageVector by lazy {
        icon("search") {
            moveTo(4f, 10.5f)
            arcTo(6.5f, 6.5f, 0f, true, true, 17f, 10.5f)
            arcTo(6.5f, 6.5f, 0f, true, true, 4f, 10.5f)
            moveTo(15.3f, 15.3f)
            lineTo(20f, 20f)
        }
    }

    /** Closing a dialog, and cancelling a running job. */
    val close: ImageVector by lazy {
        icon("close") {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }

    /**
     * Builds one icon on the shared 24 by 24 viewport, as a single
     * round-capped, round-joined stroke so the same geometry stays
     * legible at every size the caller renders it at. The stroke color is
     * `Color.Black`, a neutral placeholder that `Icon`'s `tint` parameter
     * always replaces at the call site, not a color from the theme's
     * token table.
     */
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()
}
