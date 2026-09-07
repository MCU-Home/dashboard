// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.mcuhome.ui.api.ApiError
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.BoardRegistry
import org.mcuhome.ui.api.DoctorReport
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.api.ProjectFile
import org.mcuhome.ui.api.ProjectOption
import org.mcuhome.ui.api.PublicKey
import org.mcuhome.ui.api.ServerInfo
import org.mcuhome.ui.component.ErrorNotice
import org.mcuhome.ui.component.PageHeading
import org.mcuhome.ui.component.PageNote
import org.mcuhome.ui.component.SegmentedControl
import org.mcuhome.ui.component.ThinVerticalScrollbar
import org.mcuhome.ui.editor.EditorDocument
import org.mcuhome.ui.editor.UnsavedChangesDialog
import org.mcuhome.ui.shell.LocalNavigationGuard
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * The project itself: the options every build of it uses, the file they
 * live in, the boards it can be built for, and whether the machine around
 * it is in a state to do so.
 *
 * The four tabs are four views of one project rather than four screens —
 * an option written in the table appears in the file on the next tab, and
 * the doctor's findings point at both.
 */
@Composable
fun ProjectPage(modifier: Modifier = Modifier) {
    val api = LocalMcuHomeApi.current
    val coroutineScope = rememberCoroutineScope()
    val guard = LocalNavigationGuard.current
    val scroll = rememberScrollState()

    var info by remember { mutableStateOf<ServerInfo?>(null) }
    var tab by remember { mutableStateOf(ProjectTab.Options) }
    var options by remember { mutableStateOf(emptyList<ProjectOption>()) }
    var file by remember { mutableStateOf<ProjectFile?>(null) }
    var document by remember { mutableStateOf<EditorDocument?>(null) }
    var boards by remember { mutableStateOf<BoardRegistry?>(null) }
    var doctor by remember { mutableStateOf<DoctorReport?>(null) }
    var publicKey by remember { mutableStateOf<PublicKey?>(null) }
    var leaving by remember { mutableStateOf<(() -> Unit)?>(null) }
    var error by remember { mutableStateOf<ApiError?>(null) }
    val text = remember { TextFieldState() }

    fun call(block: suspend () -> Unit) {
        error = null
        coroutineScope.launch {
            try {
                block()
            } catch (failure: ApiException) {
                error = failure.error
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            info = api.server.info()
            options = api.project.options()
            val loaded = api.project.read()
            file = loaded
            text.setTextAndPlaceCursorAtEnd(loaded.text)
            document = EditorDocument.loaded(loaded.text, loaded.revision)
        } catch (failure: ApiException) {
            error = failure.error
        }
    }

    LaunchedEffect(tab) {
        try {
            when (tab) {
                ProjectTab.Boards -> boards = api.device.boards()

                ProjectTab.Doctor -> {
                    doctor = api.project.doctor()
                    publicKey = api.project.publicKey()
                }

                else -> Unit
            }
        } catch (failure: ApiException) {
            error = failure.error
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { text.text.toString() }.collect { current -> document = document?.edited(current) }
    }

    DisposableEffect(guard, document?.dirty) {
        guard.ask = { proceed ->
            if (document?.dirty == true) {
                leaving = proceed
                true
            } else {
                false
            }
        }
        onDispose { guard.ask = null }
    }

    fun save() {
        val current = document ?: return
        if (!current.canSave) return
        val sent = current.currentText
        document = current.saveStarted()
        call {
            try {
                document = document?.saveFinished(sent, api.project.write(sent, current.revision))
                options = api.project.options()
            } catch (failure: ApiException) {
                document = document?.saveFailed()
                throw failure
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeading(
            title = "Project",
            meta = info?.let { "${it.projectName} · ${it.projectId}" },
        ) {
            SegmentedControl(
                options = ProjectTab.entries,
                selected = tab,
                onSelect = { tab = it },
                label = { it.label },
            )
        }
        PageNote(tabNote(tab))
        error?.let { ErrorNotice(it, Modifier.fillMaxWidth(), onDismiss = { error = null }) }

        val open = file
        if (tab == ProjectTab.Yaml && open != null) {
            ProjectYamlTab(
                path = open.path,
                text = text,
                document = document,
                actions = ProjectYamlActions(
                    onSave = { save() },
                    onReload = {
                        val current = document
                        val conflict = current?.conflict
                        if (conflict != null) {
                            text.setTextAndPlaceCursorAtEnd(conflict.currentText)
                            document = current.reloaded()
                        }
                    },
                    onOverwrite = {
                        document = document?.overwriting()
                        save()
                    },
                ),
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    ProjectTabContent(
                        tab = tab,
                        data = ProjectTabData(options, boards, doctor, publicKey),
                        actions = ProjectOptionActions(
                            onSet = { option, value ->
                                call { options = replaceOption(options, api.project.setOption(option.name, value)) }
                            },
                            onReset = { option ->
                                call { options = replaceOption(options, api.project.unsetOption(option.name)) }
                            },
                        ),
                    )
                }
                ThinVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }

    leaving?.let { proceed ->
        UnsavedChangesDialog(
            onStay = { leaving = null },
            onLeave = {
                leaving = null
                proceed()
            },
        )
    }
}

/** Everything the three table tabs draw, as one value. */
@Immutable
private data class ProjectTabData(
    val options: List<ProjectOption>,
    val boards: BoardRegistry?,
    val doctor: DoctorReport?,
    val publicKey: PublicKey?,
)

/** What the options table can ask the screen to write. */
@Immutable
private data class ProjectOptionActions(
    val onSet: (ProjectOption, String) -> Unit,
    val onReset: (ProjectOption) -> Unit,
)

/** What each tab shows once its data has arrived. */
@Composable
private fun ProjectTabContent(
    tab: ProjectTab,
    data: ProjectTabData,
    actions: ProjectOptionActions,
) {
    val boards = data.boards
    val doctor = data.doctor
    when (tab) {
        ProjectTab.Options -> ProjectOptionsTab(
            options = data.options,
            onSet = actions.onSet,
            onReset = actions.onReset,
            modifier = Modifier.fillMaxWidth(),
        )

        ProjectTab.Boards -> if (boards == null) Loading() else BoardsTab(boards, Modifier.fillMaxWidth())

        ProjectTab.Doctor -> if (doctor == null) {
            Loading()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DoctorChecks(doctor, Modifier.fillMaxWidth())
                data.publicKey?.let { PublicKeyCard(it, Modifier.fillMaxWidth()) }
            }
        }

        ProjectTab.Yaml -> Loading()
    }
}

@Composable
private fun Loading() {
    Text(
        text = "Loading…",
        color = MCUHomeTheme.colors.muted,
        fontFamily = MCUHomeTheme.typography.body,
        fontSize = 14.sp,
    )
}

/** The sentence under the title: what this tab writes, and where. */
private fun tabNote(tab: ProjectTab): String = when (tab) {
    ProjectTab.Options ->
        "Effective build and tool options. Each value shows which layer sets it — edits here write to " +
            "mcuhome.yaml (project layer)."

    ProjectTab.Yaml -> "The project layer as a file. Everything the options table writes ends up here."

    ProjectTab.Boards -> "The boards MCUHome can build for. A planned board is listed with the reason it is not ready."

    ProjectTab.Doctor -> "What MCUHome finds when it looks at this project and the machine around it."
}

/** The answer of a write, put back into the table without asking for the whole list again. */
private fun replaceOption(options: List<ProjectOption>, updated: ProjectOption): List<ProjectOption> =
    options.map { if (it.name == updated.name) updated else it }
