// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Light or dark, decided here because nobody tells us.
 *
 * ADR 0005 decision 5: themes do not cross the ingress iframe boundary —
 * no mechanism exists, and that was checked. So the page follows
 * `prefers-color-scheme` and offers an explicit override that it
 * remembers, which is the escape hatch for the case the ADR names: a
 * dark Home Assistant on a light system theme, where self-detection is
 * right about the system and wrong about the frame it is sitting in.
 *
 * Web Awesome switches on a class on the root element (`wa-light` /
 * `wa-dark`); `color-scheme` on top of it is what makes the browser's
 * own widgets — scrollbars, form controls — follow along.
 */

export type ThemePreference = 'system' | 'light' | 'dark';
export type ResolvedTheme = 'light' | 'dark';

const STORAGE_KEY = 'mcuhome.theme';
const DARK_QUERY = '(prefers-color-scheme: dark)';

export function readPreference(store: Storage | null = safeStorage()): ThemePreference {
  const stored = store?.getItem(STORAGE_KEY);
  return stored === 'light' || stored === 'dark' ? stored : 'system';
}

export function writePreference(
  preference: ThemePreference,
  store: Storage | null = safeStorage(),
): void {
  if (store === null) return;
  try {
    if (preference === 'system') {
      store.removeItem(STORAGE_KEY);
    } else {
      store.setItem(STORAGE_KEY, preference);
    }
  } catch {
    // A browser with storage disabled still gets a working theme, it
    // just forgets the choice on reload.
  }
}

export function systemTheme(win: Window = window): ResolvedTheme {
  return win.matchMedia?.(DARK_QUERY).matches ? 'dark' : 'light';
}

export function resolveTheme(preference: ThemePreference, win: Window = window): ResolvedTheme {
  return preference === 'system' ? systemTheme(win) : preference;
}

/** Put the resolved theme on the document. Idempotent. */
export function applyTheme(
  theme: ResolvedTheme,
  root: HTMLElement = document.documentElement,
): void {
  root.classList.toggle('wa-dark', theme === 'dark');
  root.classList.toggle('wa-light', theme === 'light');
  root.style.colorScheme = theme;
}

/** Call *listener* whenever the system theme changes. */
export function watchSystemTheme(
  listener: (theme: ResolvedTheme) => void,
  win: Window = window,
): () => void {
  const query = win.matchMedia?.(DARK_QUERY);
  if (query === undefined) return () => {};
  const handler = (event: MediaQueryListEvent) => listener(event.matches ? 'dark' : 'light');
  query.addEventListener('change', handler);
  return () => query.removeEventListener('change', handler);
}

function safeStorage(): Storage | null {
  try {
    // A sandboxed iframe or a cookie policy can make this throw on
    // access rather than return null, and a test runner can leave a
    // half-built global behind — hence the shape check as well.
    const storage = globalThis.window?.localStorage as Storage | undefined;
    return typeof storage?.getItem === 'function' ? storage : null;
  } catch {
    return null;
  }
}
