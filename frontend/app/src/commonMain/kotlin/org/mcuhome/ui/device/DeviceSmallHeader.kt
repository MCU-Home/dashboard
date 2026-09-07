// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.DeviceDetail
import org.mcuhome.ui.api.DeviceSummary
import org.mcuhome.ui.api.FlashMode
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PrimaryButton
import org.mcuhome.ui.component.SecondaryButton
import org.mcuhome.ui.component.bottomBorder
import org.mcuhome.ui.panel.BuildRun
import org.mcuhome.ui.shell.LocalWindowSize
import org.mcuhome.ui.theme.MCUHomeTheme

/** The bar a tablet gives a device: the same height as the top bar above it. */
val TabletHeaderHeight = 48.dp

/** The bar a phone gives a device — it replaces the top bar rather than joining it. */
val PhoneHeaderHeight = 56.dp

/** What is left of that bar while the keyboard is up, upright and sideways. */
val PhoneEditingHeaderHeight = 44.dp
val PhoneLandscapeHeaderHeight = 40.dp

/** How a phone's header is arranged, which is a question of what there is room for. */
enum class PhoneHeaderMode {
    /** Upright, keyboard closed: the name over the board, the jobs, the menu. */
    Portrait,

    /** Sideways, keyboard closed: the name and the board on one line, the actions beside them. */
    Landscape,

    /** The editor has the focus: the name, one state, the menu, and nothing else. */
    Editing,
}

/**
 * The bar above a device on a tablet: the way back to the list, which
 * device this is, and the three actions that are used most.
 *
 * The breadcrumb of a desktop window becomes a back button here — a
 * tablet is used with a thumb, and "Devices / garage-door" is two words
 * where an arrow is one target. Signing and pairing move behind the "…",
 * which is where the actions that are used least belong anyway.
 */
@Composable
fun DeviceTabletHeader(
    name: String,
    state: DeviceHeaderState,
    actions: DeviceHeaderActions,
    modifier: Modifier = Modifier,
    defaultBuildMethod: BuildMethod = BuildMethod.Local,
) {
    val colors = MCUHomeTheme.colors
    HeaderBar(modifier, TabletHeaderHeight) {
        BackButton(actions.onOpenDevices)
        Text(
            text = name,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
        )
        Box(Modifier.weight(1f))
        if (state.dirty) {
            SecondaryButton(
                text = if (state.saving) "Saving…" else "Save",
                onClick = actions.onSave,
                enabled = !state.saving,
            )
        }
        DeviceBarActions(actions, defaultBuildMethod)
        DeviceMoreButton(actions, extended = true)
    }
}

/**
 * The bar above a device on a phone, in the three shapes it takes.
 *
 * [statePill] is the one thing the editing mode has room to say about the
 * device; the rest of what the strip below the header shows is one tap
 * away in the rail. [jobsChip] is here because a phone's device page has
 * no top bar to carry it, and a build that is running has to stay
 * reachable from wherever the user is.
 */
@Composable
fun DevicePhoneHeader(
    summary: DeviceSummary,
    mode: PhoneHeaderMode,
    landscape: Boolean,
    state: DeviceHeaderState,
    actions: DeviceHeaderActions,
    modifier: Modifier = Modifier,
    defaultBuildMethod: BuildMethod = BuildMethod.Local,
    statePill: @Composable () -> Unit = {},
    jobsChip: @Composable () -> Unit = {},
) {
    val colors = MCUHomeTheme.colors
    val name = summary.name
    val board = summary.board
    val height = when {
        mode == PhoneHeaderMode.Editing && landscape -> PhoneLandscapeHeaderHeight
        mode == PhoneHeaderMode.Editing -> PhoneEditingHeaderHeight
        landscape -> PhoneLandscapeHeaderHeight
        else -> PhoneHeaderHeight
    }
    HeaderBar(modifier, height) {
        BackButton(actions.onOpenDevices)
        if (mode == PhoneHeaderMode.Portrait) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = colors.ink,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                )
                Text(
                    text = board,
                    color = colors.muted,
                    fontFamily = MCUHomeTheme.typography.mono,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                )
            }
        } else {
            Text(
                text = name,
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.body,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
            )
            if (landscape) {
                Text(
                    text = board,
                    color = colors.muted,
                    fontFamily = MCUHomeTheme.typography.mono,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                )
            }
            Box(Modifier.weight(1f))
        }

        when (mode) {
            PhoneHeaderMode.Editing -> statePill()

            PhoneHeaderMode.Landscape -> DeviceBarActions(actions, defaultBuildMethod)

            PhoneHeaderMode.Portrait -> {
                if (state.dirty) {
                    PrimaryButton(
                        text = if (state.saving) "Saving…" else "Save",
                        onClick = actions.onSave,
                        enabled = !state.saving,
                        height = SaveButtonHeight,
                    )
                }
                jobsChip()
            }
        }
        DeviceMoreButton(actions, extended = true)
    }
}

/** The height the Save button takes in a phone's header: a finger's target, inside a 56 px bar. */
private val SaveButtonHeight = 40.dp

/** The frame every one of those bars shares. */
@Composable
private fun HeaderBar(
    modifier: Modifier,
    height: Dp,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(colors.surface)
            .bottomBorder()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The way back to the device list, as an arrow rather than as a word. */
@Composable
private fun BackButton(onClick: () -> Unit) {
    MCUHomeIconButton(
        icon = MCUHomeIcons.chevronLeft,
        contentDescription = "Back to the devices",
        onClick = onClick,
        tint = MCUHomeTheme.colors.ink,
        size = 40.dp,
    )
}

/** Validate, Build and Flash, as a narrow bar draws them. */
@Composable
private fun DeviceBarActions(actions: DeviceHeaderActions, defaultBuildMethod: BuildMethod) {
    SecondaryButton(text = "Validate", onClick = actions.onValidate, icon = MCUHomeIcons.check)
    PrimaryButton(
        text = "Build",
        onClick = { actions.onBuild(defaultBuildMethod) },
        icon = MCUHomeIcons.hammer,
    )
    SecondaryButton(
        text = "Flash",
        onClick = { actions.onFlash(FlashMode.Recovery) },
        icon = MCUHomeIcons.bolt,
    )
}

/** Whether the file in the editor has unsaved changes, and whether the page is being typed into. */
@Immutable
data class DeviceHeaderState(val editing: Boolean, val dirty: Boolean, val saving: Boolean)

/**
 * The bar above a device, whichever bar the window calls for.
 *
 * One place decides between the desktop window's breadcrumb, the tablet's
 * back bar and the phone's three, so that the device page itself says
 * "the header" and not "the header, unless".
 */
@Composable
fun DeviceScreenHeader(
    detail: DeviceDetail,
    build: BuildRun,
    state: DeviceHeaderState,
    actions: DeviceHeaderActions,
    modifier: Modifier = Modifier,
    buildMethod: BuildMethod = BuildMethod.Local,
    jobsChip: @Composable () -> Unit = {},
) {
    val window = LocalWindowSize.current
    val summary = detail.summary
    when {
        window.compact -> DevicePhoneHeader(
            summary = summary,
            mode = phoneHeaderMode(state.editing, window.landscape),
            landscape = window.landscape,
            state = state,
            actions = actions,
            modifier = modifier,
            defaultBuildMethod = buildMethod,
            statePill = { DeviceSinglePill(detail, build) },
            jobsChip = jobsChip,
        )

        window.medium -> DeviceTabletHeader(
            name = summary.name,
            state = state,
            actions = actions,
            modifier = modifier,
            defaultBuildMethod = buildMethod,
        )

        else -> DeviceHeader(
            name = summary.name,
            board = summary.board,
            dirty = state.dirty,
            saving = state.saving,
            actions = actions,
            modifier = modifier,
            defaultBuildMethod = buildMethod,
        )
    }
}

/**
 * The one thing the editing mode says about the device.
 *
 * It is the same choice the phone's device list makes for its rows —
 * something broken before something running before something worth
 * knowing — except that a running build says which stage it is in,
 * because that is the number the user is watching.
 */
@Composable
private fun DeviceSinglePill(detail: DeviceDetail, build: BuildRun) {
    val state = deviceRowState(detail.summary)
    val label = if (state == DeviceRowState.Building) buildRunningLabel(build) else deviceRowLabel(detail.summary)
    Pill(text = label, tone = deviceRowTone(state), dot = state == DeviceRowState.Building)
}
