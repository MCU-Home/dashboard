// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The entry point. Two imports and a custom element.
 *
 * Nothing here reads the base path: the backend injects `<base href>`
 * and `window.MCUHOME_BASE_PATH` into the document it serves, and
 * `src/base-path.ts` is the only module that touches the latter.
 */

import './webawesome';
import './components/mh-app';
