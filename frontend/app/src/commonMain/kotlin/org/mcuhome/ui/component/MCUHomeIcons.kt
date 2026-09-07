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
 * whatever the font happens to ship; drawing the shapes the interface
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

    /** An informational notice. */
    val infoCircle: ImageVector by lazy {
        icon("infoCircle") {
            moveTo(3f, 12f)
            arcTo(9f, 9f, 0f, true, true, 21f, 12f)
            arcTo(9f, 9f, 0f, true, true, 3f, 12f)
            moveTo(12f, 11f)
            lineTo(12f, 16.5f)
            moveTo(12f, 7.5f)
            lineTo(12f, 7.6f)
        }
    }

    /** The Build action, and the Build tab of the output panel. */
    val hammer: ImageVector by lazy {
        icon("hammer") {
            moveTo(12.5f, 4f)
            lineTo(19.5f, 11f)
            lineTo(17f, 13.5f)
            lineTo(10f, 6.5f)
            close()
            moveTo(11.2f, 9.3f)
            lineTo(4.5f, 16f)
            lineTo(7.5f, 19f)
            lineTo(14.2f, 12.3f)
        }
    }

    /** Signing an image, and the "signed" row of the device rail. */
    val key: ImageVector by lazy {
        icon("key") {
            moveTo(10.5f, 9.5f)
            arcTo(4f, 4f, 0f, true, true, 10.5f, 9.6f)
            moveTo(13.5f, 12.5f)
            lineTo(20f, 19f)
            moveTo(17.5f, 16.5f)
            lineTo(15.5f, 18.5f)
        }
    }

    /** Flashing an image onto a board. */
    val bolt: ImageVector by lazy {
        icon("bolt") {
            moveTo(13f, 3f)
            lineTo(6f, 13.5f)
            lineTo(11.5f, 13.5f)
            lineTo(10.5f, 21f)
            lineTo(17.5f, 10.5f)
            lineTo(12f, 10.5f)
            close()
        }
    }

    /** Matter pairing: the commissioning code as a shape. */
    val qr: ImageVector by lazy {
        icon("qr") {
            moveTo(4f, 4f)
            lineTo(10f, 4f)
            lineTo(10f, 10f)
            lineTo(4f, 10f)
            close()
            moveTo(14f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 10f)
            lineTo(14f, 10f)
            close()
            moveTo(4f, 14f)
            lineTo(10f, 14f)
            lineTo(10f, 20f)
            lineTo(4f, 20f)
            close()
            moveTo(14f, 14f)
            lineTo(16f, 14f)
            moveTo(19f, 14f)
            lineTo(20f, 14f)
            moveTo(14f, 17f)
            lineTo(14f, 20f)
            moveTo(17.5f, 17.5f)
            lineTo(20f, 17.5f)
            moveTo(17.5f, 20f)
            lineTo(17.5f, 20.1f)
        }
    }

    /** Fetching an artifact. */
    val download: ImageVector by lazy {
        icon("download") {
            moveTo(12f, 4f)
            lineTo(12f, 15f)
            moveTo(7.5f, 10.5f)
            lineTo(12f, 15f)
            lineTo(16.5f, 10.5f)
            moveTo(4.5f, 19.5f)
            lineTo(19.5f, 19.5f)
        }
    }

    /** Copying a pairing code to the clipboard. */
    val copy: ImageVector by lazy {
        icon("copy") {
            moveTo(9f, 8.5f)
            lineTo(19.5f, 8.5f)
            lineTo(19.5f, 19.5f)
            lineTo(9f, 19.5f)
            close()
            moveTo(5.5f, 15.5f)
            lineTo(4.5f, 15.5f)
            lineTo(4.5f, 4.5f)
            lineTo(15f, 4.5f)
            lineTo(15f, 5.5f)
        }
    }

    /** Revealing a masked value. */
    val eye: ImageVector by lazy {
        icon("eye") {
            moveTo(2.5f, 12f)
            curveTo(6f, 6.5f, 18f, 6.5f, 21.5f, 12f)
            curveTo(18f, 17.5f, 6f, 17.5f, 2.5f, 12f)
            close()
            moveTo(9.5f, 12f)
            arcTo(2.5f, 2.5f, 0f, true, true, 9.5f, 12.1f)
        }
    }

    /** Hiding a value that was revealed. */
    val eyeOff: ImageVector by lazy {
        icon("eyeOff") {
            moveTo(2.5f, 12f)
            curveTo(6f, 6.5f, 18f, 6.5f, 21.5f, 12f)
            curveTo(18f, 17.5f, 6f, 17.5f, 2.5f, 12f)
            close()
            moveTo(9.5f, 12f)
            arcTo(2.5f, 2.5f, 0f, true, true, 9.5f, 12.1f)
            moveTo(4f, 20f)
            lineTo(20f, 4f)
        }
    }

    /** A warning: the Diagnostics list and the collapsed rail's marker. */
    val warningTriangle: ImageVector by lazy {
        icon("warningTriangle") {
            moveTo(12f, 3.5f)
            lineTo(21.5f, 20f)
            lineTo(2.5f, 20f)
            close()
            moveTo(12f, 10f)
            lineTo(12f, 14.5f)
            moveTo(12f, 17f)
            lineTo(12f, 17.1f)
        }
    }

    /**
     * Moving the output panel to the other edge, as one button rather than
     * two: a window cut along its diagonal, carrying the bottom dock's
     * horizontal split in the lower left half and the right dock's
     * vertical split in the upper right half. One glyph therefore shows
     * both places the panel can be, and the tooltip says which of them the
     * click leads to.
     */
    val dockToggle: ImageVector by lazy {
        icon("dockToggle") {
            moveTo(3.5f, 4.5f)
            lineTo(20.5f, 4.5f)
            lineTo(20.5f, 19.5f)
            lineTo(3.5f, 19.5f)
            close()
            moveTo(3.5f, 4.5f)
            lineTo(20.5f, 19.5f)
            moveTo(3.5f, 14f)
            lineTo(13.4f, 14f)
            moveTo(14f, 4.5f)
            lineTo(14f, 13.2f)
        }
    }

    /**
     * Putting something away towards the right edge: the status rail, and
     * the output panel while it is docked there. The bar is the edge the
     * chevron pushes it against, which is what tells it apart from the
     * plain chevron that brings it back.
     */
    val collapseRight: ImageVector by lazy {
        icon("collapseRight") {
            moveTo(10f, 6.5f)
            lineTo(15.5f, 12f)
            lineTo(10f, 17.5f)
            moveTo(19.5f, 5.5f)
            lineTo(19.5f, 18.5f)
        }
    }

    /** Putting the output panel away towards the bottom edge. */
    val collapseDown: ImageVector by lazy {
        icon("collapseDown") {
            moveTo(6.5f, 10f)
            lineTo(12f, 15.5f)
            lineTo(17.5f, 10f)
            moveTo(5.5f, 19.5f)
            lineTo(18.5f, 19.5f)
        }
    }

    /** The Device log tab: lines of a log, as the strip shows it. */
    val logLines: ImageVector by lazy {
        icon("logLines") {
            moveTo(4.5f, 7f)
            lineTo(19.5f, 7f)
            moveTo(4.5f, 12f)
            lineTo(19.5f, 12f)
            moveTo(4.5f, 17f)
            lineTo(13.5f, 17f)
        }
    }

    /** Bringing the status rail or a minimized panel back. */
    val chevronLeft: ImageVector by lazy {
        icon("chevronLeft") {
            moveTo(14.5f, 5.5f)
            lineTo(8f, 12f)
            lineTo(14.5f, 18.5f)
        }
    }

    /** The resolved model: a file the interface only reads. */
    val file: ImageVector by lazy {
        icon("file") {
            moveTo(6f, 3.5f)
            lineTo(14f, 3.5f)
            lineTo(18f, 7.5f)
            lineTo(18f, 20.5f)
            lineTo(6f, 20.5f)
            close()
            moveTo(14f, 3.5f)
            lineTo(14f, 7.5f)
            lineTo(18f, 7.5f)
        }
    }

    /** Changing a value that is already there: a secret, an option. */
    val pencil: ImageVector by lazy {
        icon("pencil") {
            moveTo(4.5f, 19.5f)
            lineTo(4.5f, 15.5f)
            lineTo(15.5f, 4.5f)
            lineTo(19.5f, 8.5f)
            lineTo(8.5f, 19.5f)
            close()
            moveTo(13.5f, 6.5f)
            lineTo(17.5f, 10.5f)
        }
    }

    /** Removing something for good: a secret, a file. */
    val trash: ImageVector by lazy {
        icon("trash") {
            moveTo(4.5f, 6.5f)
            lineTo(19.5f, 6.5f)
            moveTo(9.5f, 6.5f)
            lineTo(9.5f, 4.5f)
            lineTo(14.5f, 4.5f)
            lineTo(14.5f, 6.5f)
            moveTo(6.5f, 6.5f)
            lineTo(7.5f, 20.5f)
            lineTo(16.5f, 20.5f)
            lineTo(17.5f, 6.5f)
            moveTo(10.5f, 10f)
            lineTo(10.5f, 17f)
            moveTo(13.5f, 10f)
            lineTo(13.5f, 17f)
        }
    }

    /** Opening the navigation menu on narrow layouts. */
    val menu: ImageVector by lazy {
        icon("menu") {
            moveTo(4f, 7f)
            lineTo(20f, 7f)
            moveTo(4f, 12f)
            lineTo(20f, 12f)
            moveTo(4f, 17f)
            lineTo(20f, 17f)
        }
    }

    /** Opening the status rail on narrow layouts. */
    val sidebar: ImageVector by lazy {
        icon("sidebar") {
            moveTo(3f, 5f)
            lineTo(21f, 5f)
            lineTo(21f, 19f)
            lineTo(3f, 19f)
            close()
            moveTo(15f, 5f)
            lineTo(15f, 19f)
        }
    }

    /** The YAML editor's indent action. */
    val indent: ImageVector by lazy {
        icon("indent") {
            moveTo(4f, 12f)
            lineTo(7f, 12f)
            moveTo(5.5f, 9f)
            lineTo(9f, 12f)
            lineTo(5.5f, 15f)
            moveTo(12f, 7f)
            lineTo(20f, 7f)
            moveTo(12f, 12f)
            lineTo(20f, 12f)
            moveTo(12f, 17f)
            lineTo(20f, 17f)
        }
    }

    /** The YAML editor's outdent action. */
    val outdent: ImageVector by lazy {
        icon("outdent") {
            moveTo(9f, 12f)
            lineTo(6f, 12f)
            moveTo(7.5f, 9f)
            lineTo(4f, 12f)
            lineTo(7.5f, 15f)
            moveTo(12f, 7f)
            lineTo(20f, 7f)
            moveTo(12f, 12f)
            lineTo(20f, 12f)
            moveTo(12f, 17f)
            lineTo(20f, 17f)
        }
    }

    /** The YAML editor's undo action. */
    val undo: ImageVector by lazy {
        icon("undo") {
            moveTo(15f, 6f)
            arcTo(7f, 7f, 0f, true, true, 6f, 17f)
            moveTo(3.5f, 15.5f)
            lineTo(6f, 17f)
            lineTo(4f, 19.5f)
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
