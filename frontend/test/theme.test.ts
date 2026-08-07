// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Self-detection plus an override (ADR 0005 decision 5).
 *
 * The override is not a nicety: no theme crosses the ingress iframe
 * boundary, so a dark Home Assistant on a light desktop starts light and
 * the only fix is the user saying so.
 */

import { beforeEach, describe, expect, it } from 'vitest';

import {
  applyTheme,
  readPreference,
  resolveTheme,
  systemTheme,
  writePreference,
} from '../src/state/theme';

function fakeWindow(prefersDark: boolean) {
  return {
    matchMedia: (query: string) => ({ matches: prefersDark && query.includes('dark') }),
  } as unknown as Window;
}

/** An in-memory `Storage`, so these tests do not depend on the runner's. */
function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() {
      return values.size;
    },
    clear: () => values.clear(),
    getItem: (key: string) => values.get(key) ?? null,
    key: (index: number) => [...values.keys()][index] ?? null,
    removeItem: (key: string) => void values.delete(key),
    setItem: (key: string, value: string) => void values.set(key, value),
  };
}

let store: Storage;

beforeEach(() => {
  store = memoryStorage();
});

describe('the preference', () => {
  it('follows the system until somebody says otherwise', () => {
    expect(readPreference(store)).toBe('system');
  });

  it('is remembered', () => {
    writePreference('dark', store);
    expect(readPreference(store)).toBe('dark');
  });

  it('is forgotten again by choosing "system"', () => {
    writePreference('dark', store);
    writePreference('system', store);
    expect(readPreference(store)).toBe('system');
  });

  it('ignores a stored value that is not a theme', () => {
    store.setItem('mcuhome.theme', 'chartreuse');
    expect(readPreference(store)).toBe('system');
  });

  it('works without storage at all', () => {
    // A sandboxed iframe or a cookie policy: the theme still works, the
    // choice is just forgotten on reload.
    expect(() => writePreference('dark', null)).not.toThrow();
    expect(readPreference(null)).toBe('system');
  });
});

describe('resolving', () => {
  it('reads prefers-color-scheme when the preference is "system"', () => {
    expect(systemTheme(fakeWindow(true))).toBe('dark');
    expect(resolveTheme('system', fakeWindow(true))).toBe('dark');
    expect(resolveTheme('system', fakeWindow(false))).toBe('light');
  });

  it('lets an explicit choice win over the system', () => {
    expect(resolveTheme('light', fakeWindow(true))).toBe('light');
    expect(resolveTheme('dark', fakeWindow(false))).toBe('dark');
  });
});

describe('applying', () => {
  it('sets the class Web Awesome switches on, and the browser hint next to it', () => {
    const root = document.createElement('html');
    applyTheme('dark', root);
    expect(root.classList.contains('wa-dark')).toBe(true);
    expect(root.classList.contains('wa-light')).toBe(false);
    expect(root.style.colorScheme).toBe('dark');

    applyTheme('light', root);
    expect(root.classList.contains('wa-light')).toBe(true);
    expect(root.classList.contains('wa-dark')).toBe(false);
  });
});
