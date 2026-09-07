// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.shell

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A way for the open screen to be asked before the shell navigates away
 * from it.
 *
 * Only one screen can hold unsaved work at a time, and only that screen
 * knows whether it does, so it is the screen that answers the question —
 * the shell asks it and either navigates or hands over the decision. A
 * screen that installs nothing is navigated away from immediately, which
 * is what every screen without an editor wants.
 *
 * The browser's own back button is not routed through here: it is the
 * browser's navigation, not the application's, and Compose's history
 * binding does not offer a veto.
 */
class NavigationGuard {
    /**
     * Returns true when the screen has taken the decision over — it then
     * calls [proceed] itself once the user has answered.
     */
    var ask: ((proceed: () -> Unit) -> Boolean)? = null

    fun navigate(proceed: () -> Unit) {
        if (ask?.invoke(proceed) != true) proceed()
    }
}

val LocalNavigationGuard = staticCompositionLocalOf { NavigationGuard() }
