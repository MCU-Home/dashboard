// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import androidx.compose.runtime.Immutable
import org.mcuhome.ui.api.ApiError
import org.mcuhome.ui.api.ArtifactInfo
import org.mcuhome.ui.api.Availability
import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.api.ResolvedModel

/**
 * Everything the output panel shows, gathered by the screen that owns it.
 *
 * The panel asks the API for nothing itself: the device page already
 * holds the build, the diagnostics and the artifacts, and a second reader
 * would only be a second answer to the same question.
 */
@Immutable
data class OutputPanelData(
    val build: BuildRun = BuildRun(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val artifacts: List<ArtifactInfo> = emptyList(),
    val model: ModelState = ModelState.Loading,
    val logNotAvailable: Availability.NotAvailable? = null,
)

/** The resolved model, which a configuration with errors does not have. */
sealed interface ModelState {
    data object Loading : ModelState

    data class Ready(val model: ResolvedModel) : ModelState

    /** The server refused: the configuration does not resolve yet, and why. */
    data class Refused(val error: ApiError) : ModelState
}

/** What the panel can ask the screen around it to do. */
@Immutable
data class OutputPanelActions(
    val onLayout: (PanelLayout) -> Unit = {},
    val onJumpToLine: (Int) -> Unit = {},
    val onCancelBuild: () -> Unit = {},
    val onDownload: (ArtifactInfo) -> Unit = {},
)
