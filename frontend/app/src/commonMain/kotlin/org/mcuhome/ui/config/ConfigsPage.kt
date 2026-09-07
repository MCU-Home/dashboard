// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.mcuhome.ui.api.ApiError
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.ConfigChanged
import org.mcuhome.ui.api.ConfigStatus
import org.mcuhome.ui.api.ConfigUsersReport
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.api.SharedConfigFile
import org.mcuhome.ui.api.SharedConfigSummary
import org.mcuhome.ui.component.ErrorNotice
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Notice
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.SideList
import org.mcuhome.ui.component.SideListItem
import org.mcuhome.ui.editor.EditorDocument
import org.mcuhome.ui.editor.SaveConflictNotice
import org.mcuhome.ui.editor.UnsavedChangesDialog
import org.mcuhome.ui.editor.YamlEditor
import org.mcuhome.ui.shell.LocalNavigationGuard
import org.mcuhome.ui.shell.LocalWindowSize
import org.mcuhome.ui.theme.MCUHomeTheme
import org.mcuhome.ui.time.rememberNowEpochMillis

/** The width of the file list on the left, as the design draws it. */
private val ConfigListWidth = 280.dp

/** What that list is given where the window has less to spare. */
private val NarrowConfigListWidth = 240.dp

/** The sentence under the file list: what these files are and how a device gets one. */
private const val CONFIG_LIST_NOTE =
    "Files in configs/. A device pulls one in with !include; validation runs on the devices that use it."

/**
 * The shared configuration files of the project: the list on the left,
 * the open file in the editor, and what depends on it on the right.
 *
 * A fragment has no validation of its own — `Validate users` checks every
 * device that includes it and reports per device, which is why those
 * findings appear in the rail and not in the gutter beside the text.
 *
 * The open file is part of the address (`#configs/<name>`); when the
 * address names none, the first file is opened and the address is
 * corrected to say which.
 */
@Composable
fun ConfigsPage(
    fileName: String?,
    onOpenConfig: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCloseConfig: () -> Unit = {},
) {
    val api = LocalMcuHomeApi.current
    val window = LocalWindowSize.current
    val scope = rememberCoroutineScope()
    val guard = LocalNavigationGuard.current
    val now by rememberNowEpochMillis()

    var files by remember { mutableStateOf(emptyList<SharedConfigSummary>()) }
    var userStatus by remember { mutableStateOf(emptyMap<String, ConfigStatus>()) }
    var open by remember(fileName) { mutableStateOf<SharedConfigFile?>(null) }
    var document by remember(fileName) { mutableStateOf<EditorDocument?>(null) }
    var report by remember(fileName) { mutableStateOf<ConfigUsersReport?>(null) }
    var validating by remember(fileName) { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<(() -> Unit)?>(null) }
    var error by remember { mutableStateOf<ApiError?>(null) }
    val text = remember(fileName) { TextFieldState() }

    fun call(block: suspend () -> Unit) {
        error = null
        scope.launch {
            try {
                block()
            } catch (failure: ApiException) {
                error = failure.error
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            files = api.config.list()
            userStatus = api.device.list().associate { it.name to it.config }
        } catch (failure: ApiException) {
            error = failure.error
        }
    }

    // A phone shows the list or the file, never both, so it starts on
    // the list; every other window opens the first file straight away,
    // because there is a column for the list beside it either way.
    LaunchedEffect(files, fileName, window.compact) {
        if (window.compact && fileName == null) return@LaunchedEffect
        val opened = openedConfigFile(files, fileName) ?: return@LaunchedEffect
        if (opened != fileName) onOpenConfig(opened)
    }

    LaunchedEffect(fileName) {
        val name = fileName ?: return@LaunchedEffect
        try {
            val loaded = api.config.read(name)
            open = loaded
            text.setTextAndPlaceCursorAtEnd(loaded.text)
            document = EditorDocument.loaded(loaded.text, loaded.revision)
        } catch (failure: ApiException) {
            error = failure.error
        }
    }

    LaunchedEffect(fileName) {
        snapshotFlow { text.text.toString() }.collect { current ->
            document = document?.edited(current)
        }
    }

    LaunchedEffect(Unit) {
        api.events.collect { event ->
            if (event is ConfigChanged) files = api.config.list()
        }
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
        val name = fileName ?: return
        val current = document ?: return
        if (!current.canSave) return
        val sent = current.currentText
        document = current.saveStarted()
        call {
            try {
                document = document?.saveFinished(sent, api.config.write(name, sent, current.revision))
                files = api.config.list()
            } catch (failure: ApiException) {
                document = document?.saveFailed()
                throw failure
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val wanted = event.type == KeyEventType.KeyDown &&
                    event.key == Key.S &&
                    (event.isCtrlPressed || event.isMetaPressed)
                if (wanted) save()
                wanted
            },
    ) {
        val listOnly = window.compact && fileName == null
        if (!window.compact || listOnly) {
            SideList(
                title = "Shared configs",
                width = when {
                    listOnly -> window.width
                    window.expanded -> ConfigListWidth
                    else -> NarrowConfigListWidth
                },
                footer = CONFIG_LIST_NOTE,
                action = {
                    MCUHomeIconButton(
                        icon = MCUHomeIcons.plus,
                        contentDescription = "New shared configuration",
                        onClick = { creating = true },
                        bordered = true,
                    )
                },
            ) {
                files.forEach { summary ->
                    SideListItem(
                        label = summary.fileName,
                        onClick = { onOpenConfig(summary.fileName) },
                        icon = MCUHomeIcons.file,
                        // How many devices use a file is worth a column of
                        // its own only where the name has room beside it.
                        trailing = if (window.medium) null else usedByLabel(summary.usedByDevices.size),
                        selected = summary.fileName == fileName,
                    )
                }
            }
        }

        val file = open
        if (!listOnly) {
            Column(Modifier.weight(1f).fillMaxSize()) {
                if (file == null) {
                    Box(Modifier.fillMaxSize().padding(24.dp)) {
                        val failure = error
                        if (failure != null) {
                            ErrorNotice(failure, Modifier.fillMaxWidth())
                        } else {
                            Placeholder(files.isEmpty())
                        }
                    }
                } else {
                    ConfigHeader(
                        fileName = file.summary.fileName,
                        dirty = document?.dirty == true,
                        saving = document?.saving == true,
                        validating = validating,
                        actions = ConfigHeaderActions(
                            onValidateUsers = {
                                validating = true
                                call {
                                    try {
                                        report = api.config.validateUsers(file.summary.fileName)
                                        userStatus = api.device.list().associate { it.name to it.config }
                                    } finally {
                                        validating = false
                                    }
                                }
                            },
                            onSave = { save() },
                        ),
                        onBack = if (window.compact) onCloseConfig else null,
                    )
                    ConfigNotices(
                        error = error,
                        report = report,
                        conflict = document?.conflict != null,
                        actions = ConfigNoticeActions(
                            onDismissError = { error = null },
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
                    )
                    YamlEditor(state = text, diagnostics = emptyList(), modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }

            // A phone has no column to put the rail in; what it says about
            // the file is what the list's own entry for it already says.
            if (file != null && !window.compact) {
                ConfigStatusRail(
                    file = file,
                    userStatus = userStatus,
                    report = report,
                    nowEpochMillis = now,
                    width = if (window.expanded) ConfigRailWidth else NarrowConfigRailWidth,
                )
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

    if (creating) {
        NewConfigDialog(
            onDismiss = { creating = false },
            onCreated = { summary ->
                creating = false
                call { files = api.config.list() }
                onOpenConfig(summary.fileName)
            },
        )
    }
}

/** What the screen says while nothing is open — or when there is nothing to open. */
@Composable
private fun Placeholder(empty: Boolean) {
    Text(
        text = if (empty) "This project has no shared configuration yet." else "Loading…",
        color = MCUHomeTheme.colors.muted,
        fontFamily = MCUHomeTheme.typography.body,
        fontSize = 14.sp,
    )
}

/** What the statements above the editor can start. */
@Immutable
private data class ConfigNoticeActions(
    val onDismissError: () -> Unit,
    val onReload: () -> Unit,
    val onOverwrite: () -> Unit,
)

/** The statements above the editor: a refused command, the outcome of a check, a lost race. */
@Composable
private fun ConfigNotices(
    error: ApiError?,
    report: ConfigUsersReport?,
    conflict: Boolean,
    actions: ConfigNoticeActions,
) {
    if (error == null && report == null && !conflict) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        error?.let { ErrorNotice(it, Modifier.fillMaxWidth(), onDismiss = actions.onDismissError) }
        if (report != null) {
            Notice(
                tone = if (report.ok) PillTone.Success else PillTone.Error,
                title = usersReportTitle(report),
                message = usersReportMessage(report),
                icon = if (report.ok) MCUHomeIcons.check else MCUHomeIcons.errorCircle,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (conflict) {
            SaveConflictNotice(
                onReload = actions.onReload,
                onOverwrite = actions.onOverwrite,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
