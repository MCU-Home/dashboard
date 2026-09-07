// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.mcuhome.ui.api.ApiError
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.ArtifactInfo
import org.mcuhome.ui.api.Availability
import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.ConfigState
import org.mcuhome.ui.api.ConfigStatus
import org.mcuhome.ui.api.DeviceChanged
import org.mcuhome.ui.api.DeviceDetail
import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.api.DiagnosticSeverity
import org.mcuhome.ui.api.FlashMode
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.component.ErrorNotice
import org.mcuhome.ui.component.NotAvailableNotice
import org.mcuhome.ui.component.SecondaryButton
import org.mcuhome.ui.download.LocalFileDownloader
import org.mcuhome.ui.editor.EditorDocument
import org.mcuhome.ui.editor.SaveConflictNotice
import org.mcuhome.ui.editor.UnsavedChangesDialog
import org.mcuhome.ui.editor.YamlEditor
import org.mcuhome.ui.editor.editorDiagnostics
import org.mcuhome.ui.panel.BuildRun
import org.mcuhome.ui.panel.LocalPanelSession
import org.mcuhome.ui.panel.ModelState
import org.mcuhome.ui.panel.OutputPanel
import org.mcuhome.ui.panel.OutputPanelActions
import org.mcuhome.ui.panel.OutputPanelData
import org.mcuhome.ui.panel.PanelMinimizedBar
import org.mcuhome.ui.panel.PanelMinimizedStrip
import org.mcuhome.ui.panel.PanelTab
import org.mcuhome.ui.shell.LocalNavigationGuard
import org.mcuhome.ui.theme.MCUHomeTheme
import org.mcuhome.ui.time.rememberNowEpochMillis

/** How long the editor waits after a keystroke before it validates again. */
private const val VALIDATE_DEBOUNCE_MILLIS = 300L

/**
 * Below this page width the status rail collapses to its icon strip on
 * its own: the editor, a 260 px rail and a docked panel need room, and
 * the rail is the part whose words can be recovered with one click. It is
 * re-opened by hand and stays open until the window crosses the threshold
 * again.
 */
private val RAIL_COLLAPSE_WIDTH = 1180.dp

/** Which dialog the device page has open; only ever one at a time. */
private sealed interface DeviceDialog {
    data class Flash(val mode: FlashMode) : DeviceDialog

    data object Pairing : DeviceDialog

    data object Rename : DeviceDialog

    data object Delete : DeviceDialog

    /** Leaving the page while the editor holds unsaved text. */
    data class Leave(val proceed: () -> Unit) : DeviceDialog
}

/**
 * One device: its configuration in an editor, everything that is known
 * about it in the rail beside it, and everything it is doing in the panel
 * below or beside them.
 *
 * The screen owns the state; the three areas draw it. Validation follows
 * the typing rather than the file on disk, a build keeps running when the
 * page is left and is picked up again when it is re-entered, and a write
 * that lost a race is answered with a choice rather than with a lost
 * file.
 */
@Composable
fun DevicePage(
    name: String,
    onOpenDevices: () -> Unit,
    onOpenDevice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalMcuHomeApi.current
    val scope = rememberCoroutineScope()
    val downloader = LocalFileDownloader.current
    val session = LocalPanelSession.current
    val guard = LocalNavigationGuard.current
    val now by rememberNowEpochMillis()

    val text = remember(name) { TextFieldState() }
    var detail by remember(name) { mutableStateOf<DeviceDetail?>(null) }
    var document by remember(name) { mutableStateOf<EditorDocument?>(null) }
    var diagnostics by remember(name) { mutableStateOf(emptyList<Diagnostic>()) }
    var build by remember(name) { mutableStateOf(BuildRun()) }
    var buildId by remember(name) { mutableStateOf<String?>(null) }
    var model by remember(name) { mutableStateOf<ModelState>(ModelState.Loading) }
    var logNotAvailable by remember(name) { mutableStateOf<Availability.NotAvailable?>(null) }
    var jumpToLine by remember(name) { mutableStateOf<Int?>(null) }
    var railCollapsed by remember { mutableStateOf(false) }
    var dialog by remember(name) { mutableStateOf<DeviceDialog?>(null) }
    var error by remember(name) { mutableStateOf<ApiError?>(null) }
    var notAvailable by remember(name) { mutableStateOf<Availability.NotAvailable?>(null) }
    var buildMethod by remember { mutableStateOf(BuildMethod.Local) }

    fun call(block: suspend () -> Unit) {
        error = null
        notAvailable = null
        scope.launch {
            try {
                block()
            } catch (failure: ApiException) {
                error = failure.error
            }
        }
    }

    LaunchedEffect(name) {
        try {
            val loaded = api.device.get(name)
            detail = loaded
            text.setTextAndPlaceCursorAtEnd(loaded.yaml)
            document = EditorDocument.loaded(loaded.yaml, loaded.revision)
            diagnostics = loaded.diagnostics
            if (loaded.summary.build.state == BuildState.Building) buildId = loaded.summary.build.buildId
        } catch (failure: ApiException) {
            error = failure.error
        }
    }

    // Validation follows what is on screen, not what is on disk, and waits
    // for a pause in the typing: the gutter then belongs to the text the
    // caret is in without a request per keystroke.
    LaunchedEffect(name) {
        snapshotFlow { text.text.toString() }.collectLatest { current ->
            val open = document ?: return@collectLatest
            document = open.edited(current)
            delay(VALIDATE_DEBOUNCE_MILLIS)
            try {
                diagnostics = api.device.validate(name, current).diagnostics
            } catch (failure: ApiException) {
                error = failure.error
            }
        }
    }

    LaunchedEffect(name) {
        api.events.collect { event ->
            if (event is DeviceChanged && event.device == name) {
                detail = detail?.copy(summary = event.summary)
            }
        }
    }

    LaunchedEffect(buildId) {
        val id = buildId ?: return@LaunchedEffect
        build = BuildRun()
        try {
            api.build.stream(id).collect { event -> build = build.applied(event) }
            detail = api.device.get(name)
        } catch (failure: ApiException) {
            error = failure.error
        }
    }

    LaunchedEffect(session.layout.tab, name, document?.savedText) {
        when (session.layout.tab) {
            PanelTab.Model -> model = try {
                ModelState.Ready(api.device.model(name))
            } catch (failure: ApiException) {
                ModelState.Refused(failure.error)
            }

            PanelTab.DeviceLog -> logNotAvailable = api.log.open(name) as? Availability.NotAvailable

            else -> Unit
        }
    }

    DisposableEffect(guard, document?.dirty) {
        guard.ask = { proceed ->
            if (document?.dirty == true) {
                dialog = DeviceDialog.Leave(proceed)
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
                document = document?.saveFinished(sent, api.device.save(name, sent, current.revision))
            } catch (failure: ApiException) {
                document = document?.saveFailed()
                throw failure
            }
        }
    }

    fun download(artifact: ArtifactInfo) {
        val id = detail?.lastGoodBuild?.buildId ?: buildId ?: return
        call { downloader.download(api.build.download(id, artifact.path)) }
    }

    val open = detail
    if (open == null) {
        LoadingOrError(error, modifier)
        return
    }

    val live = open.copy(summary = open.summary.copy(config = statusOf(diagnostics)))
    val layout = session.layout
    val dirty = document?.dirty == true

    Column(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val save = event.type == KeyEventType.KeyDown &&
                    event.key == Key.S &&
                    (event.isCtrlPressed || event.isMetaPressed)
                if (save) save()
                save
            },
    ) {
        DeviceHeader(
            name = name,
            board = open.summary.board,
            dirty = dirty,
            saving = document?.saving == true,
            defaultBuildMethod = buildMethod,
            actions = DeviceHeaderActions(
                onOpenDevices = onOpenDevices,
                onSave = { save() },
                onValidate = { call { diagnostics = api.device.validate(name).diagnostics } },
                onBuild = { method ->
                    buildMethod = method
                    call { buildId = api.build.start(name, method).buildId }
                },
                onSign = { call { buildId = api.build.sign(name).buildId } },
                onFlash = { mode -> dialog = DeviceDialog.Flash(mode) },
                onPairing = { dialog = DeviceDialog.Pairing },
                onFirstTimeSetup = { call { notAvailable = api.setup.start(name) as? Availability.NotAvailable } },
                onClean = { call { api.device.clean(name) } },
                onRename = { dialog = DeviceDialog.Rename },
                onDelete = { dialog = DeviceDialog.Delete },
                onResolvedModel = { session.layout = layout.showing(PanelTab.Model) },
            ),
        )

        DeviceNotices(
            error = error,
            notAvailable = notAvailable,
            conflict = document?.conflict != null,
            actions = DeviceNoticeActions(
                onDismissError = { error = null },
                onDismissNotAvailable = { notAvailable = null },
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

        BoxWithConstraints(Modifier.weight(1f)) {
            val narrow = maxWidth < RAIL_COLLAPSE_WIDTH
            LaunchedEffect(narrow) { railCollapsed = narrow }
            val railActions = DeviceRailActions(
                onCollapse = { railCollapsed = true },
                onExpand = { railCollapsed = false },
                onDownload = { artifact -> download(artifact) },
                onShowPairing = { dialog = DeviceDialog.Pairing },
                onJumpToLine = { line -> jumpToLine = line },
            )
            val panelData = OutputPanelData(
                build = build,
                diagnostics = diagnostics,
                artifacts = live.artifacts,
                model = model,
                logNotAvailable = logNotAvailable,
            )
            val panelActions = OutputPanelActions(
                onLayout = { updated -> session.layout = updated },
                onJumpToLine = { line -> jumpToLine = line },
                onCancelBuild = { call { buildId?.let { api.build.cancel(it) } } },
                onDownload = { artifact -> download(artifact) },
            )
            DeviceBody(
                layout = layout,
                onResize = { delta -> session.layout = layout.resized(delta) },
                slots = DeviceBodySlots(
                    editor = {
                        YamlEditor(
                            state = text,
                            diagnostics = editorDiagnostics(diagnostics),
                            modifier = Modifier.fillMaxSize(),
                            jumpToLine = jumpToLine,
                            onJumpHandled = { jumpToLine = null },
                        )
                    },
                    rail = {
                        if (railCollapsed) {
                            CollapsedStatusRail(live, diagnostics, build, railActions)
                        } else {
                            DeviceStatusRail(live, diagnostics, build, now, railActions)
                        }
                    },
                    panel = { OutputPanel(layout, panelData, panelActions, Modifier.fillMaxSize()) },
                    minimizedBar = { PanelMinimizedBar(layout, panelData, panelActions) },
                    minimizedStrip = { PanelMinimizedStrip(layout, panelData, panelActions) },
                ),
            )
        }
    }

    DeviceDialogs(
        dialog = dialog,
        detail = live,
        buildRunning = build.running,
        nowEpochMillis = now,
        actions = DeviceDialogActions(
            onDismiss = { dialog = null },
            onOpenDevice = onOpenDevice,
            onOpenDevices = onOpenDevices,
            onDeviceChanged = { call { detail = api.device.get(name) } },
        ),
    )
}

/** What a dialog of the device page can ask the page to do when it closes. */
@Immutable
private data class DeviceDialogActions(
    val onDismiss: () -> Unit,
    val onOpenDevice: (String) -> Unit,
    val onOpenDevices: () -> Unit,
    /**
     * Something outside the editor changed the device — read it again.
     * The open document is deliberately left alone: its revision is what
     * makes the next write notice that the file moved on.
     */
    val onDeviceChanged: () -> Unit,
)

/** The device page's own overlays, kept out of the screen's body. */
@Composable
private fun DeviceDialogs(
    dialog: DeviceDialog?,
    detail: DeviceDetail,
    buildRunning: Boolean,
    nowEpochMillis: Long,
    actions: DeviceDialogActions,
) {
    val onDismiss = actions.onDismiss
    when (dialog) {
        null -> Unit

        is DeviceDialog.Flash -> FlashDialog(
            detail = detail,
            initialMode = dialog.mode,
            nowEpochMillis = nowEpochMillis,
            onDismiss = onDismiss,
            buildRunning = buildRunning,
        )

        DeviceDialog.Pairing -> PairingDialog(
            device = detail.summary.name,
            onDismiss = onDismiss,
            onCredentialsChanged = actions.onDeviceChanged,
        )

        DeviceDialog.Rename -> RenameDeviceDialog(
            device = detail.summary.name,
            onDismiss = onDismiss,
            onRenamed = { renamed ->
                onDismiss()
                actions.onOpenDevice(renamed.name)
            },
        )

        DeviceDialog.Delete -> DeleteDeviceDialog(
            device = detail.summary.name,
            onDismiss = onDismiss,
            onDeleted = {
                onDismiss()
                actions.onOpenDevices()
            },
        )

        is DeviceDialog.Leave -> UnsavedChangesDialog(
            onStay = onDismiss,
            onLeave = {
                onDismiss()
                dialog.proceed()
            },
        )
    }
}

/** What the notices above the editor can start. */
@Immutable
private data class DeviceNoticeActions(
    val onDismissError: () -> Unit,
    val onDismissNotAvailable: () -> Unit,
    val onReload: () -> Unit,
    val onOverwrite: () -> Unit,
)

/** The notices that belong to the file rather than to the whole page. */
@Composable
private fun DeviceNotices(
    error: ApiError?,
    notAvailable: Availability.NotAvailable?,
    conflict: Boolean,
    actions: DeviceNoticeActions,
) {
    if (error == null && notAvailable == null && !conflict) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        error?.let { ErrorNotice(it, Modifier.fillMaxWidth(), onDismiss = actions.onDismissError) }
        notAvailable?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NotAvailableNotice(it, Modifier.weight(1f))
                SecondaryButton(
                    text = "Close",
                    onClick = actions.onDismissNotAvailable,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
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

/** What the page shows before the device has arrived — or instead of it. */
@Composable
private fun LoadingOrError(error: ApiError?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp)) {
        if (error != null) {
            ErrorNotice(error, Modifier.fillMaxWidth())
        } else {
            Text(
                text = "Loading…",
                color = MCUHomeTheme.colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 14.sp,
            )
        }
    }
}

/** The config pill of a device that is being edited: what the last validation found. */
private fun statusOf(diagnostics: List<Diagnostic>): ConfigStatus {
    val errors = diagnostics.count { it.severity == DiagnosticSeverity.Error }
    val warnings = diagnostics.count { it.severity == DiagnosticSeverity.Warning }
    val state = when {
        errors > 0 -> ConfigState.Errors
        warnings > 0 -> ConfigState.Warnings
        else -> ConfigState.Valid
    }
    return ConfigStatus(state, errorCount = errors, warningCount = warnings)
}
