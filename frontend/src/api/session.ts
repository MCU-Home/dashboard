// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The two REST calls a browser needs, and the reachability probe.
 *
 * ADR 0004 decision 4 keeps REST to "where a browser primitive needs a
 * URL", and a login is exactly that: a WebSocket cannot set a cookie,
 * and a password held in JavaScript instead of an `HttpOnly` cookie is
 * strictly worse. Everything else goes over `/ws`.
 *
 * The CSRF token from a successful login lives in memory for as long as
 * the page does — deliberately not in storage, where an XSS would find
 * it next to the cookie it is supposed to be independent of.
 */

import { httpUrl } from '../base-path';

const CSRF_HEADER = 'X-CSRF-Token';

let csrfToken: string | null = null;

export class LoginError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'LoginError';
    this.status = status;
  }
}

/** True when the backend answers at all — see `WsClientOptions.probe`. */
export async function serverReachable(): Promise<boolean> {
  try {
    const response = await fetch(httpUrl('/health'), { credentials: 'same-origin' });
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * Exchange the password for a session cookie.
 *
 * The cookie is set by the server and never read here: it is `HttpOnly`,
 * which is the point.
 */
export async function login(password: string): Promise<void> {
  const response = await fetch(httpUrl('/auth/login'), {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  });

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new LoginError(response.status, text.trim() || 'The dashboard refused this password.');
  }

  const body = (await response.json()) as { csrf_token?: unknown };
  csrfToken = typeof body.csrf_token === 'string' ? body.csrf_token : null;
}

export async function logout(): Promise<void> {
  const headers: Record<string, string> = {};
  if (csrfToken !== null) {
    headers[CSRF_HEADER] = csrfToken;
  }
  await fetch(httpUrl('/auth/logout'), {
    method: 'POST',
    credentials: 'same-origin',
    headers,
  });
  csrfToken = null;
}

/** Exported for tests; nothing else needs to know a token exists. */
export function hasSessionToken(): boolean {
  return csrfToken !== null;
}

export function forgetSessionToken(): void {
  csrfToken = null;
}
