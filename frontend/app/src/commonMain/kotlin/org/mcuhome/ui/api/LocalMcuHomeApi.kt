// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The API the composition talks to.
 *
 * Passing it as a parameter would put an `api` argument on every composable
 * between the application root and the one screen that needs it, and most
 * of those would only hand it on. It is also constant for the life of the
 * composition — which is exactly what a static composition local is for:
 * reading it costs nothing and no recomposition is tracked for it.
 *
 * The entry point still decides which implementation is used: it is
 * provided once, in [org.mcuhome.ui.App], from the instance the platform
 * hands in. A preview or a test provides its own with
 * `CompositionLocalProvider(LocalMcuHomeApi provides …)`.
 */
val LocalMcuHomeApi = staticCompositionLocalOf<McuHomeApi> {
    error("No McuHomeApi was provided — App() installs one; a preview or a test provides its own.")
}
