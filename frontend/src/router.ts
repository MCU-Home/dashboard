// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Routing in the fragment, because the path is not ours.
 *
 * The same build has to serve `/`, a reverse-proxy sub-path and
 * `/api/hassio_ingress/<token>/` (ADR 0005 decision 4), and the prefix
 * only becomes known at runtime. A History-API router would have to
 * subtract that prefix from `location.pathname` on every navigation and
 * on every reload, and get it right for a token that changes per
 * session. The fragment is never sent to a server, never rewritten by a
 * proxy and never needs the prefix at all.
 *
 * `<a href="#/devices/x">` still resolves against the injected
 * `<base href>`, so links keep working as ordinary links — no click
 * interception, no `preventDefault`, and middle-click opens a new tab
 * that lands in the right place.
 */

export type Route =
  { view: 'devices' } | { view: 'device'; name: string } | { view: 'not-found'; path: string };

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#/, '');
  if (path === '' || path === '/' || path === '/devices') {
    return { view: 'devices' };
  }
  const device = /^\/devices\/([^/]+)\/?$/.exec(path);
  if (device?.[1] !== undefined) {
    return { view: 'device', name: decodeURIComponent(device[1]) };
  }
  return { view: 'not-found', path };
}

export function devicesHref(): string {
  return '#/devices';
}

export function deviceHref(name: string): string {
  return `#/devices/${encodeURIComponent(name)}`;
}

/** Call *listener* on every fragment change, and once immediately. */
export function watchRoute(listener: (route: Route) => void, win: Window = window): () => void {
  const handler = () => listener(parseRoute(win.location.hash));
  win.addEventListener('hashchange', handler);
  handler();
  return () => win.removeEventListener('hashchange', handler);
}
