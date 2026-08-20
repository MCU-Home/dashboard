# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Static assets, SPA fallback and the ingress base path.

**`X-Ingress-Path` is not a mount prefix.** Home Assistant strips the
prefix before it proxies, so this server sees ``/``, ``/ws`` and
``/assets/…`` exactly as it would standalone. What it does *not* see is
what the browser's address bar says — and the browser needs that to
resolve relative URLs and to open the WebSocket. ADR 0004 records that
no framework solves this: the header is read per request and applied by
us, which is what :func:`base_path` and :func:`render_index` do.

The prefix is therefore a **per-request** value, never process state.
Two Home Assistant instances proxying the same process would otherwise
poison each other's pages, and, more mundanely, it is an
attacker-controllable header on the public site — which is why
:func:`base_path` validates its shape and the injection escapes it.
"""

from __future__ import annotations

import html
import json
import logging
import re
from pathlib import Path

from aiohttp import web

__all__ = [
    "INGRESS_PATH_HEADER",
    "base_path",
    "render_index",
    "static_handler",
]

logger = logging.getLogger(__name__)

INGRESS_PATH_HEADER = "X-Ingress-Path"

#: A base path is an absolute path of plain segments. Anything with a
#: scheme, a host, a query, a fragment or a ``..`` is not one.
_BASE_PATH_RE = re.compile(r"^/(?:[A-Za-z0-9._~!$&'()*+,;=:@%-]+/?)*$")

#: The opening ``<head>`` tag. ``\b`` is load-bearing: without it
#: ``<header class="…">`` matches, because ``head`` is a prefix of it.
_HEAD_RE = re.compile(r"<head\b[^>]*>", re.IGNORECASE)

#: Extensionless paths are SPA routes; anything with a suffix that is
#: not on disk is a genuine 404, so a mistyped asset URL fails loudly
#: instead of silently returning HTML to a <script> tag.
_ASSET_SUFFIX_RE = re.compile(r"\.[A-Za-z0-9]{1,8}$")


def base_path(request: web.Request) -> str:
    """The prefix the browser sees, without a trailing slash.

    Returns ``""`` when there is no ingress in front, which is the
    normal standalone case and makes every generated URL root-relative.
    """
    raw = request.headers.get(INGRESS_PATH_HEADER)
    if not raw:
        return ""
    candidate = raw.rstrip("/")
    if not candidate:
        return ""
    if ".." in candidate or not _BASE_PATH_RE.match(candidate + "/"):
        logger.warning("ignoring an unusable %s header: %r", INGRESS_PATH_HEADER, raw)
        return ""
    return candidate


def _injection(prefix: str) -> str:
    """The two things the page needs, and nothing more.

    ``<base>`` fixes relative asset and link resolution for free.
    ``window.MCUHOME_BASE_PATH`` is for the code that builds URLs by
    hand — the WebSocket URL above all, since ``<base>`` does not apply
    to ``new WebSocket()``.

    Two different escapings, because they land in two different parsers:
    an HTML attribute wants entity escaping, a ``<script>`` body is raw
    text where entities mean nothing and only ``</`` could break out.
    """
    href = html.escape(f"{prefix}/", quote=True)
    literal = json.dumps(prefix).replace("</", "<\\/")
    return f'<base href="{href}"><script>window.MCUHOME_BASE_PATH={literal};</script>'


def _head_end(source: str) -> int | None:
    """Offset just past the document's real ``<head …>`` tag, or ``None``.

    "Real" is the whole point: a ``<head>`` written inside an HTML
    comment is text, not an element, and injecting after it would put a
    ``<base>`` and a ``<script>`` into the comment — where the browser
    never sees them and the page silently loads its assets from the
    wrong prefix. A banner comment mentioning ``<head>`` is an ordinary
    thing for a bundler or a licence header to emit, so the scan steps
    over comments instead of trusting the first match.
    """
    position = 0
    while True:
        comment = source.find("<!--", position)
        match = _HEAD_RE.search(source, position)
        if match is not None and (comment == -1 or match.start() < comment):
            return match.end()
        if comment == -1:
            return None
        closed = source.find("-->", comment + 4)
        if closed == -1:
            # An unterminated comment: a browser treats the rest of the
            # document as comment text, so there is no head element in
            # it either, whatever the characters spell.
            return None
        position = closed + 3


def render_index(source: str, prefix: str) -> str:
    """Insert the base path into an ``index.html``.

    Works on the diagnostic page and on whatever Vite emits, because it
    keys off the document's ``<head>`` element rather than a marker only
    our own file would carry. A document without one gets the injection
    in front of everything — a fallback for a fragment, not a shape the
    frontend build produces.
    """
    injection = _injection(prefix)
    end = _head_end(source)
    if end is None:
        return injection + source
    return source[:end] + injection + source[end:]


def _index_file(static_root: Path) -> Path:
    return static_root / "index.html"


async def _index_response(request: web.Request, static_root: Path) -> web.Response:
    index = _index_file(static_root)
    try:
        source = index.read_text(encoding="utf-8")
    except OSError:
        raise web.HTTPNotFound(
            text=(
                "No frontend is installed. Build it with `npm run build` in frontend/, "
                f"or point --static-root at a directory containing index.html "
                f"(looked in {static_root})."
            )
        ) from None
    return web.Response(
        text=render_index(source, base_path(request)),
        content_type="text/html",
        charset="utf-8",
        headers={"Cache-Control": "no-store"},
    )


def static_handler(static_root: Path):
    """Serve built frontend assets with an SPA fallback.

    Written by hand rather than with ``web.static()`` because the
    fallback and the per-request base-path injection are the whole job,
    and ``add_static`` does neither.
    """

    async def handler(request: web.Request) -> web.StreamResponse:
        relative = request.match_info.get("path", "").lstrip("/")
        if not relative:
            return await _index_response(request, static_root)

        root = static_root.resolve()
        candidate = (root / relative).resolve()
        if not candidate.is_relative_to(root):
            # A traversal attempt. Not a 403: telling the caller which
            # of its guesses escaped the root is free reconnaissance.
            raise web.HTTPNotFound()
        if candidate.is_file():
            return web.FileResponse(candidate)
        if _ASSET_SUFFIX_RE.search(relative):
            raise web.HTTPNotFound()
        return await _index_response(request, static_root)

    return handler
