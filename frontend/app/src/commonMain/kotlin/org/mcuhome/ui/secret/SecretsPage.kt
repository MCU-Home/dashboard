// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.secret

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.mcuhome.ui.api.ApiError
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.api.PublicKey
import org.mcuhome.ui.api.SecretEntry
import org.mcuhome.ui.api.SecretList
import org.mcuhome.ui.api.SecretScopeIndex
import org.mcuhome.ui.component.ErrorNotice
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.PageHeading
import org.mcuhome.ui.component.PageNote
import org.mcuhome.ui.component.PrimaryButton
import org.mcuhome.ui.component.SegmentedControl
import org.mcuhome.ui.component.ThinVerticalScrollbar
import org.mcuhome.ui.project.PublicKeyCard
import org.mcuhome.ui.shell.LocalWindowSize
import org.mcuhome.ui.theme.MCUHomeTheme

/** Which overlay the screen has open; only ever one at a time. */
private sealed interface SecretDialogState {
    data object Add : SecretDialogState

    data class Edit(val key: String) : SecretDialogState

    data class Delete(val entry: SecretEntry) : SecretDialogState
}

/**
 * The project's secrets, one file at a time.
 *
 * The rule the screen is built around: a list carries keys and dots, and
 * a value arrives only from a reveal request for exactly that key. That
 * is why the eye is a request and not a toggle over something already
 * downloaded, and why changing the scope forgets every value that was
 * shown.
 */
@Composable
fun SecretsPage(modifier: Modifier = Modifier) {
    val api = LocalMcuHomeApi.current
    val window = LocalWindowSize.current
    val coroutineScope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var index by remember { mutableStateOf(SecretScopeIndex()) }
    var kind by remember { mutableStateOf(SecretScopeKind.Project) }
    var name by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<SecretList?>(null) }
    var revealed by remember { mutableStateOf(RevealedSecrets()) }
    var ascending by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<SecretDialogState?>(null) }
    var publicKey by remember { mutableStateOf<PublicKey?>(null) }
    var error by remember { mutableStateOf<ApiError?>(null) }

    val names = scopeNames(kind, index)
    val chosen = name?.takeIf { it in names } ?: names.firstOrNull()
    val scope = secretScope(kind, chosen)

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
            index = api.secret.scopes()
            publicKey = api.project.publicKey()
        } catch (failure: ApiException) {
            error = failure.error
        }
    }

    LaunchedEffect(scope) {
        revealed = revealed.cleared()
        list = null
        val open = scope ?: return@LaunchedEffect
        try {
            list = api.secret.list(open)
        } catch (failure: ApiException) {
            error = failure.error
        }
    }

    fun reload() {
        val open = scope ?: return
        call { list = api.secret.list(open) }
    }

    val entries = list?.entries.orEmpty()
    val rows = remember(entries, ascending) { sortedSecrets(entries, ascending) }

    Column(
        modifier = modifier.fillMaxSize().padding(
            horizontal = if (window.expanded) 32.dp else 16.dp,
            vertical = if (window.expanded) 24.dp else 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeading(title = "Secrets") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SegmentedControl(
                    options = SecretScopeKind.entries,
                    selected = kind,
                    onSelect = {
                        kind = it
                        name = null
                    },
                    label = { it.label },
                )
                PrimaryButton(
                    text = "Add secret",
                    onClick = { dialog = SecretDialogState.Add },
                    icon = MCUHomeIcons.plus,
                    enabled = scope != null,
                )
            }
        }
        if (names.isNotEmpty()) {
            SegmentedControl(options = names, selected = chosen ?: names.first(), onSelect = { name = it })
        }
        PageNote(scopeNote(list, kind))
        error?.let { ErrorNotice(it, Modifier.fillMaxWidth(), onDismiss = { error = null }) }

        Box(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (kind == SecretScopeKind.FirmwareKey) {
                    publicKey?.let { PublicKeyCard(it, Modifier.fillMaxWidth()) }
                }
                if (scope == null) {
                    Text(
                        text = "This project has no file of that kind yet.",
                        color = MCUHomeTheme.colors.muted,
                        fontFamily = MCUHomeTheme.typography.body,
                        fontSize = 14.sp,
                    )
                } else {
                    SecretTable(
                        entries = rows,
                        revealed = revealed,
                        ascending = ascending,
                        onSort = { ascending = !ascending },
                        actions = SecretRowActions(
                            onToggleReveal = { entry ->
                                if (revealed.isRevealed(entry.key)) {
                                    revealed = revealed.hiding(entry.key)
                                } else {
                                    call { revealed = revealed.with(entry.key, api.secret.reveal(scope, entry.key)) }
                                }
                            },
                            onEdit = { entry -> dialog = SecretDialogState.Edit(entry.key) },
                            onDelete = { entry -> dialog = SecretDialogState.Delete(entry) },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        withUsedBy = !window.compact,
                    )
                }
            }
            ThinVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }

    val open = scope
    val path = list?.path
    if (open != null && path != null) {
        when (val current = dialog) {
            null -> Unit

            SecretDialogState.Add -> SecretDialog(
                scope = open,
                path = path,
                existingKey = null,
                onDismiss = { dialog = null },
                onWritten = {
                    dialog = null
                    reload()
                },
            )

            is SecretDialogState.Edit -> SecretDialog(
                scope = open,
                path = path,
                existingKey = current.key,
                onDismiss = { dialog = null },
                onWritten = {
                    dialog = null
                    revealed = revealed.hiding(current.key)
                    reload()
                },
            )

            is SecretDialogState.Delete -> DeleteSecretDialog(
                scope = open,
                key = current.entry.key,
                usedBy = if (current.entry.unused) null else usedByLabel(current.entry),
                onDismiss = { dialog = null },
                onDeleted = {
                    dialog = null
                    reload()
                },
            )
        }
    }
}

/** The sentence under the title: which file this is, and the two rules that hold for all of them. */
private fun scopeNote(list: SecretList?, kind: SecretScopeKind): String {
    val where = list?.path?.let { "Values from $it." } ?: "${kind.label}: no file yet."
    return "$where Referenced from any YAML as !secret <key>. " +
        "Values are never sent to the browser unless revealed."
}
