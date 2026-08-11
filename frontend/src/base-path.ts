// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Every URL this application builds, built from the prefix the browser
 * actually sees.
 *
 * The dominant deployment is an iframe behind Home Assistant ingress,
 * which serves the page from `/api/hassio_ingress/<token>/` while the
 * backend still sees `/`. The backend reads `X-Ingress-Path` per request
 * and injects two things into the document: a `<base href>` — which
 * takes care of relative asset and link resolution for free — and
 * `window.MCUHOME_BASE_PATH`, which is for the URLs `<base>` does not
 * cover.
 *
 * `new WebSocket()` is exactly that case: it resolves against the
 * document URL and ignores `<base>` entirely, so the one URL the whole
 * UI depends on is the one that has to be assembled by hand. ADR 0005
 * decision 4.
 */

declare global {
  interface Window {
    MCUHOME_BASE_PATH?: string;
  }
}

/**
 * The prefix the browser sees, without a trailing slash.
 *
 * `""` in a standalone deployment, which makes every URL below
 * root-relative — the same code path, not a special case.
 */
export function basePath(win: Window = window): string {
  const raw = win.MCUHOME_BASE_PATH;
  if (typeof raw !== 'string' || raw === '' || raw === '/') {
    return '';
  }
  const withLeadingSlash = raw.startsWith('/') ? raw : `/${raw}`;
  return withLeadingSlash.replace(/\/+$/, '');
}

/** An absolute path on this origin, for `fetch`. */
export function httpUrl(path: string, win: Window = window): string {
  const suffix = path.startsWith('/') ? path : `/${path}`;
  return `${basePath(win)}${suffix}`;
}

/**
 * The download URL of one build artifact, relative on purpose.
 *
 * `<a download href>` is resolved by the browser against the `<base>`
 * element the backend injects, so a *relative* URL is already correct
 * under ingress, behind a reverse-proxy sub-path and at a bare root
 * without this module having to know which — the one case where the
 * document does the work {@link httpUrl} exists to do by hand.
 *
 * Both segments are user-supplied (a build id, a path the build
 * declared) and are encoded per path segment, so a name with a space or
 * a `#` in it addresses the file rather than a fragment.
 */
export function artifactUrl(buildId: string, artifactPath: string): string {
  const tail = artifactPath
    .split('/')
    .filter((segment) => segment !== '')
    .map((segment) => encodeURIComponent(segment))
    .join('/');
  return `api/builds/${encodeURIComponent(buildId)}/artifacts/${tail}`;
}

/**
 * The `/ws` URL, absolute, because a WebSocket needs one.
 *
 * The scheme follows the page: a dashboard behind a TLS-terminating
 * reverse proxy must not open a plaintext socket, and a plain-HTTP LAN
 * deployment cannot open a TLS one.
 */
export function webSocketUrl(win: Window = window): string {
  const location = win.location;
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${location.host}${basePath(win)}/ws`;
}
