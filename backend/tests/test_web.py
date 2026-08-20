# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Static assets, the SPA fallback and the ingress base path."""

from __future__ import annotations

from pathlib import Path

import pytest

from mcuhome.ui.app import AppState, create_app
from mcuhome.ui.config import Config
from mcuhome.ui.security import TrustMode
from mcuhome.ui.web import INGRESS_PATH_HEADER, render_index
from tests.conftest import call

INGRESS_PREFIX = "/api/hassio_ingress/UEhpJ4z0y7"


async def test_the_shell_is_served_at_the_root(client) -> None:
    response = await client.get("/")
    assert response.status == 200
    assert response.content_type == "text/html"
    body = await response.text()
    assert "MCUHome Dashboard" in body
    # No ingress in front: URLs stay root-relative.
    assert '<base href="/">' in body


async def test_the_ingress_prefix_reaches_the_page(client) -> None:
    response = await client.get("/", headers={INGRESS_PATH_HEADER: INGRESS_PREFIX})
    body = await response.text()

    assert f'<base href="{INGRESS_PREFIX}/">' in body
    assert f'window.MCUHOME_BASE_PATH="{INGRESS_PREFIX}"' in body


async def test_the_prefix_is_per_request_not_process_state(client) -> None:
    first = await (await client.get("/", headers={INGRESS_PATH_HEADER: INGRESS_PREFIX})).text()
    second = await (await client.get("/")).text()

    assert INGRESS_PREFIX in first
    assert INGRESS_PREFIX not in second


@pytest.mark.parametrize(
    "header",
    [
        "https://evil.example/x",
        "/api/../../etc",
        "not-absolute",
        '"><script>alert(1)</script>',
    ],
)
async def test_an_unusable_ingress_header_is_ignored(client, header: str) -> None:
    body = await (await client.get("/", headers={INGRESS_PATH_HEADER: header})).text()
    assert '<base href="/">' in body
    assert "<script>alert(1)</script>" not in body


def test_the_injection_goes_inside_head_wherever_head_is() -> None:
    rendered = render_index('<html><head lang="en"><title>x</title></head></html>', "/pre/fix")
    assert rendered.index("<base") > rendered.index("<head")
    assert rendered.index("<base") < rendered.index("<title>")

    # A document without a <head> still gets the base path.
    assert render_index("<p>hi</p>", "").startswith("<base")


@pytest.mark.parametrize(
    "source",
    [
        # A banner comment naming <head> — a licence header or a bundler
        # note is an ordinary place for those five characters to appear.
        "<!-- injected after <head> --><html><head><title>x</title></head></html>",
        # Several comments before the real thing.
        "<!-- a --><!-- <head> --><html><head><title>x</title></head></html>",
        # <header> is not <head>, and a prefix match would say it is.
        "<html><header>nav</header><head><title>x</title></head></html>",
    ],
)
def test_the_injection_is_not_fooled_by_a_head_that_is_not_one(source: str) -> None:
    # Injecting into a comment yields a page that renders fine and loads
    # every asset from the wrong prefix — the failure is silent, which is
    # why it is worth a test rather than a glance.
    rendered = render_index(source, "/pre/fix")

    assert "<head><base href=" in rendered
    assert rendered.count("<base") == 1
    assert rendered.index("<base") < rendered.index("<title>")
    # Whatever was in front of the real head is passed through untouched.
    assert rendered.startswith(source[: source.index("<head>")])


def test_an_unterminated_comment_swallows_the_document() -> None:
    # A browser treats everything after an unclosed <!-- as comment text,
    # so there is no head to inject into and the fallback is the honest
    # answer rather than a <base> nobody will ever parse.
    assert render_index("<!-- <html><head>", "/pre/fix").startswith("<base")


async def test_unknown_routes_fall_back_to_the_shell(client) -> None:
    response = await client.get("/devices/bench-node/edit")
    assert response.status == 200
    assert "MCUHome Dashboard" in await response.text()


async def test_a_missing_asset_is_a_404_and_not_html(client) -> None:
    # Returning the shell for a missing .js would turn a broken build
    # into a mystifying syntax error in the browser console.
    assert (await client.get("/assets/index-abc123.js")).status == 404


async def test_an_existing_asset_is_served(client, tmp_path: Path, aiohttp_client, tree) -> None:
    static = tmp_path / "static"
    (static / "assets").mkdir(parents=True)
    (static / "index.html").write_text("<html><head></head><body>shell</body></html>")
    (static / "assets" / "app.js").write_text("console.log('hi');\n")

    state = AppState(Config(config_root=tree, static_root=static, poll_interval=0.0))
    await state.start()
    try:
        other = await aiohttp_client(create_app(state, TrustMode.PUBLIC))
        response = await other.get("/assets/app.js")
        assert response.status == 200
        assert "console.log" in await response.text()
    finally:
        await state.stop()


async def test_a_path_traversal_finds_nothing(client) -> None:
    response = await client.get("/../../../../etc/passwd")
    assert response.status in {200, 404}
    if response.status == 200:
        # Normalized away by the client/server before it reached us —
        # what matters is that /etc/passwd did not come back.
        assert "root:" not in await response.text()


async def test_a_missing_frontend_says_what_to_do(aiohttp_client, tmp_path: Path, tree) -> None:
    state = AppState(
        Config(config_root=tree, static_root=tmp_path / "not-built", poll_interval=0.0)
    )
    await state.start()
    try:
        client = await aiohttp_client(create_app(state, TrustMode.PUBLIC))
        response = await client.get("/")
        assert response.status == 404
        assert "frontend" in (await response.text()).lower()
    finally:
        await state.stop()


async def test_the_base_path_is_reported_over_the_websocket_too(client) -> None:
    async with client.ws_connect("/ws", headers={INGRESS_PATH_HEADER: INGRESS_PREFIX}) as ws:
        info = (await call(ws, "server/info"))["payload"]
    assert info["deployment"]["base_path"] == INGRESS_PREFIX


async def test_responses_carry_the_cheap_security_headers(client) -> None:
    response = await client.get("/")
    assert response.headers["X-Content-Type-Options"] == "nosniff"
    assert response.headers["Referrer-Policy"] == "same-origin"
