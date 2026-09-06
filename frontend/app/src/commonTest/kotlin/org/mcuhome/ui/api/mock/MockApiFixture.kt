// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.test.TestScope

/**
 * A mock for one test.
 *
 * It runs in the test's own background scope, so anything it starts stops
 * with the test, and its waits are the test scheduler's virtual time — a
 * four-second build takes no real time at all. The build the mock starts
 * for itself is off by default: a test that asserts on the initial state
 * wants the state the sample describes, not one a build has already moved.
 */
internal fun TestScope.mockApi(startingBuild: Boolean = false): MockApi =
    MockApi(scope = backgroundScope, startingBuild = startingBuild)
