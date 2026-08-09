# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The build-server settings that outlived their client.

ADR 0012 decision 3 dismantled the job-protocol client but carried ADR
0006's transport and threat model forward: a URL, a bearer token, and
the pairing file that makes two Apps on one Home Assistant instance find
each other without anybody configuring anything. Nothing in the package
reads those settings today — which is exactly why they are tested here
rather than through a client, and why they are still worth testing: the
session client of ADR 0012 has to find them intact.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from mcuhome_dashboard.config import (
    DEFAULT_BUILD_SERVER_URL,
    ENV_PREFIX,
    http_url,
    load_config,
    resolve_build_server_token,
    ws_url,
)


@pytest.mark.parametrize(
    ("configured", "http", "socket"),
    [
        ("http://host:8100", "http://host:8100", "ws://host:8100/ws"),
        ("https://host", "https://host", "wss://host/ws"),
        ("ws://host:8100", "http://host:8100", "ws://host:8100/ws"),
        ("wss://host/build/", "https://host/build", "wss://host/build/ws"),
        ("host:8100", "http://host:8100", "ws://host:8100/ws"),
    ],
)
def test_a_configured_url_becomes_both_forms(configured: str, http: str, socket: str) -> None:
    # A user writes one address in whichever scheme they have in front of
    # them; both forms are derived rather than demanded.
    assert http_url(configured) == http
    assert ws_url(configured) == socket


def test_an_explicit_token_beats_every_file(tmp_path: Path) -> None:
    pair = tmp_path / "pair.token"
    pair.write_text("from-the-pairing-file\n", encoding="utf-8")
    token, paired = resolve_build_server_token("typed-by-hand", None, env={}, pair_file=pair)
    assert (token, paired) == ("typed-by-hand", False)


def test_the_environment_beats_the_pairing_file(tmp_path: Path) -> None:
    pair = tmp_path / "pair.token"
    pair.write_text("from-the-pairing-file\n", encoding="utf-8")
    token, paired = resolve_build_server_token(
        None,
        None,
        env={ENV_PREFIX + "BUILD_SERVER_TOKEN": "from-the-environment"},
        pair_file=pair,
    )
    assert (token, paired) == ("from-the-environment", False)


def test_a_token_file_is_read_and_stripped(tmp_path: Path) -> None:
    explicit = tmp_path / "token"
    explicit.write_text("  from-a-file  \n", encoding="utf-8")
    token, paired = resolve_build_server_token(None, explicit, env={}, pair_file=tmp_path / "none")
    assert (token, paired) == ("from-a-file", False)


def test_two_apps_on_one_host_pair_themselves(tmp_path: Path, monkeypatch) -> None:
    """ADR 0006 decision 8, carried forward by ADR 0012 decision 3.

    The build server writes its token to a shared file; the dashboard
    finds it and assumes the build server's default port, so the common
    installation configures nothing. Removing this with the job protocol
    would have silently un-paired every existing installation.
    """
    pair = tmp_path / "build-server.token"
    pair.write_text("paired-token\n", encoding="utf-8")
    monkeypatch.setattr("mcuhome_dashboard.config.DEFAULT_PAIR_FILE", pair)

    config = load_config(["--config-root", str(tmp_path)], env={})
    assert config.build_server_token == "paired-token"
    assert config.build_server_url == DEFAULT_BUILD_SERVER_URL
    assert config.build_server_configured is True


def test_nothing_configured_is_no_build_server(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr("mcuhome_dashboard.config.DEFAULT_PAIR_FILE", tmp_path / "absent")
    config = load_config(["--config-root", str(tmp_path)], env={})
    assert config.build_server_url is None
    assert config.build_server_token is None
    assert config.build_server_configured is False
