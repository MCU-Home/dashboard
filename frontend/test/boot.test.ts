// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The entry point loads, and registers what it promised to.
 *
 * Cheap, and it catches the class of mistake the rest of this suite
 * cannot: a Web Awesome component whose deep import path is wrong
 * type-checks perfectly and fails only in a browser, because the package
 * ships one module per component and nothing here re-exports them.
 *
 * The shell is deliberately not *mounted* here. jsdom has no
 * `ElementInternals.setFormValue`, so any form-associated Web Awesome
 * element throws on its first update — a gap in the test environment,
 * not in the application, and not one worth papering over with a stub
 * that would then be the thing under test.
 */

import { describe, expect, it } from 'vitest';

describe('main', () => {
  it('registers the application shell and the Web Awesome components it uses', async () => {
    await import('../src/main');

    for (const tag of [
      'mh-app',
      'mh-device-list',
      'mh-device-page',
      'mh-yaml-editor',
      'mh-commissioning',
      'mh-login',
    ]) {
      expect(customElements.get(tag), tag).toBeDefined();
    }

    // The commissioning view renders the QR code with this one, and it
    // is the reason ADR 0005 picked this component library at all.
    expect(customElements.get('wa-qr-code')).toBeDefined();
    for (const tag of ['wa-button', 'wa-callout', 'wa-card', 'wa-input', 'wa-select', 'wa-tag']) {
      expect(customElements.get(tag), tag).toBeDefined();
    }
  });
});
