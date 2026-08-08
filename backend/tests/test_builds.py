# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The build commands, against a build server that is not one.

The fake here is an aiohttp application that speaks the ADR 0006 frames
and produces bytes — deliberately written from the ADR rather than
imported from ``mcuhome_buildserver``. Two reasons: the dashboard must
be testable without the build-server package installed (they are
separate products, ADR 0003 decision 1), and a test that drove the real
implementation would pass whenever the two agreed with each other,
including when both were wrong about the protocol.

Nothing here compiles or signs for real either: ``mcuhome sign`` is a
small script that does what the real command does to the manifest.
"""

from __future__ import annotations

import base64
import hashlib
import json
import stat
import sys
from collections.abc import Iterator
from pathlib import Path

import pytest
from aiohttp import WSMsgType, web

from mcuhome_dashboard.app import AppState, create_app
from mcuhome_dashboard.buildclient import http_url, ws_url
from mcuhome_dashboard.config import Config
from mcuhome_dashboard.security import TrustMode
from tests.conftest import call

TOKEN = "build-server-token-0000000000000"

BOOTLOADER = bytes(range(256)) * 900
APPLICATION = bytes(range(255, -1, -1)) * 1200

FAKE_SIGN = '''\
#!/usr/bin/env python3
"""What `mcuhome sign` does to a build directory, and nothing else."""
import json, os, sys

assert sys.argv[1] == "sign", sys.argv
build_dir = sys.argv[2]
path = os.path.join(build_dir, "build-manifest.json")
manifest = json.load(open(path))
manifest["signing"]["signed"] = True
for kind, source in manifest["signing"]["inputs"].items():
    target = manifest["signing"]["outputs"][kind]
    full = os.path.join(build_dir, target)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "wb") as handle:
        handle.write(open(os.path.join(build_dir, source), "rb").read() + b"SIGNATURE")
json.dump(manifest, open(path, "w"), indent=2)
print("signed", build_dir)
'''


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class FakeBuildServer:
    """An ADR 0006 build server with the compiler taken out."""

    def __init__(self) -> None:
        self.submissions: list[dict] = []
        self.cancelled: list[str] = []
        self.capabilities: dict = {
            "server": {"name": "mcuhome-build-server", "version": "0.1.0.dev0"},
            "protocol_version": 1,
            "mcuhome": {"version": "0.1.0.dev0"},
            "builder": {"usable": True, "model_version_input": True},
            "model_version": {"min": 1, "max": 1},
            "arch": "x86_64",
            "jobs": {"slots": 1, "queued": 0, "running": 0},
            "signing": {"detached_only": True},
        }
        self.log = b"".join(f"[{n}/4] compiling\n".encode() for n in range(1, 5))
        self.job_id = "20260808-120000-abc123"
        self.files: dict[str, bytes] = {}
        self.refuse: dict | None = None
        #: Make the artifact index state a hash the bytes do not have.
        self.corrupt_index = False

    # -- the artifact set a successful build leaves ------------------

    def manifest(self) -> dict:
        return {
            "manifest_version": 1,
            "builder": {"version": "0.1.0.dev0", "model_version": 1},
            "device": {"name": "bench-node", "board": "nrf7002dk/nrf5340/cpuapp"},
            "images": [
                {
                    "name": "mcuboot",
                    "role": "bootloader",
                    "files": [
                        {
                            "path": "build/mcuboot/zephyr/zephyr.hex",
                            "size": len(BOOTLOADER),
                            "sha256": _sha(BOOTLOADER),
                        }
                    ],
                },
                {
                    "name": "app",
                    "role": "application",
                    "files": [
                        {
                            "path": "build/app/zephyr/zephyr.bin",
                            "size": len(APPLICATION),
                            "sha256": _sha(APPLICATION),
                        }
                    ],
                },
            ],
            "merged": None,
            "signing": {
                "tool": "imgtool",
                "image": "app",
                # The build server never signs (ADR 0007 decision 3).
                "signed": False,
                "signed_by_the_build": False,
                "signature_type": "ecdsa-p256",
                "arguments": {
                    "version": "0.0.0+0",
                    "header-size": 512,
                    "align": 4,
                    "slot-size": 786432,
                },
                "inputs": {"bin": "build/app/zephyr/zephyr.bin"},
                "outputs": {"bin": "build/app/zephyr/zephyr.signed.bin"},
            },
        }

    def artifact_bytes(self) -> dict[str, bytes]:
        manifest = json.dumps(self.manifest(), indent=2).encode()
        return {
            "build/mcuboot/zephyr/zephyr.hex": BOOTLOADER,
            "build/app/zephyr/zephyr.bin": APPLICATION,
            "build-manifest.json": manifest,
        }

    def job(self, state: str) -> dict:
        return {
            "id": self.job_id,
            "device": "bench-node",
            "board": "nrf7002dk/nrf5340/cpuapp",
            "model_version": 1,
            "state": state,
            "manifest": self.manifest() if state == "succeeded" else None,
            "log_bytes": len(self.log),
            "error": None,
            "exit_code": 0 if state == "succeeded" else None,
        }

    # -- the HTTP surface --------------------------------------------

    def app(self) -> web.Application:
        app = web.Application()
        app.router.add_get("/capabilities", self._capabilities)
        app.router.add_get("/ws", self._ws)
        return app

    def _authorized(self, request: web.Request) -> bool:
        return request.headers.get("Authorization") == f"Bearer {TOKEN}"

    async def _capabilities(self, request: web.Request) -> web.Response:
        if not self._authorized(request):
            raise web.HTTPUnauthorized(text="token")
        return web.json_response(self.capabilities)

    async def _ws(self, request: web.Request) -> web.StreamResponse:
        if not self._authorized(request):
            raise web.HTTPUnauthorized(text="token")
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        async for message in ws:
            if message.type is not WSMsgType.TEXT:
                continue
            frame = json.loads(message.data)
            await self._handle(ws, frame)
        return ws

    async def _handle(self, ws, frame: dict) -> None:
        frame_id = frame.get("id")
        command = frame.get("type")
        payload = frame.get("payload") or {}

        if self.refuse is not None:
            await ws.send_json({"id": frame_id, "type": "error", "error": self.refuse})
            return

        if command == "submit_job":
            self.submissions.append(payload)
            await ws.send_json(
                {
                    "id": frame_id,
                    "type": "result",
                    "payload": {"job_id": self.job_id, "job": self.job("queued")},
                }
            )
            for state in ("running", "succeeded"):
                await ws.send_json(
                    {
                        "type": "event",
                        "event": "job_state_changed",
                        "payload": {"job": self.job(state)},
                    }
                )
        elif command == "cancel_job":
            self.cancelled.append(payload.get("job_id", ""))
            await ws.send_json(
                {"id": frame_id, "type": "result", "payload": {"job": self.job("cancelled")}}
            )
        elif command == "queue_status":
            await ws.send_json(
                {
                    "id": frame_id,
                    "type": "result",
                    "payload": {
                        "slots": 1,
                        "queued": [],
                        "running": [],
                        "jobs": [self.job("succeeded")],
                    },
                }
            )
        elif command == "follow_job":
            offset = int(payload.get("offset") or 0)
            await ws.send_json(
                {
                    "id": frame_id,
                    "type": "result",
                    "payload": {
                        "job_id": payload.get("job_id"),
                        "state": "succeeded",
                        "offset": offset,
                        "text": self.log[offset:].decode(),
                        "next_offset": len(self.log),
                        "eof": True,
                        "live": False,
                    },
                }
            )
        elif command == "download_artifacts":
            await self._download(ws, frame_id, payload)
        else:  # pragma: no cover - the client sends nothing else
            await ws.send_json(
                {
                    "id": frame_id,
                    "type": "error",
                    "error": {"code": "unknown_command", "message": command},
                }
            )

    async def _download(self, ws, frame_id, payload: dict) -> None:
        blobs = self.artifact_bytes()
        path = payload.get("path")
        if path is None:
            manifest = self.manifest()
            artifacts = [
                {
                    "path": name,
                    "size": len(data),
                    "sha256": "0" * 64 if self.corrupt_index else _sha(data),
                    "role": "image",
                }
                for name, data in blobs.items()
                if name != "build-manifest.json"
            ]
            artifacts.append(
                {
                    "path": "build-manifest.json",
                    "size": len(blobs["build-manifest.json"]),
                    "sha256": None,
                    "role": "manifest",
                }
            )
            await ws.send_json(
                {
                    "id": frame_id,
                    "type": "result",
                    "payload": {
                        "job_id": payload.get("job_id"),
                        "manifest": manifest,
                        "artifacts": artifacts,
                        "chunk_bytes": 65536,
                    },
                }
            )
            return

        data = blobs.get(path)
        if data is None:
            await ws.send_json(
                {
                    "id": frame_id,
                    "type": "error",
                    "error": {"code": "not_found", "message": f"no artifact {path}"},
                }
            )
            return
        offset = int(payload.get("offset") or 0)
        chunk = data[offset : offset + 65536]
        await ws.send_json(
            {
                "id": frame_id,
                "type": "result",
                "payload": {
                    "job_id": payload.get("job_id"),
                    "path": path,
                    "offset": offset,
                    "length": len(chunk),
                    "next_offset": offset + len(chunk),
                    "eof": offset + len(chunk) >= len(data),
                    "sha256": _sha(chunk),
                    "data": base64.b64encode(chunk).decode(),
                },
            }
        )


# --------------------------------------------------------------------------
# Fixtures
# --------------------------------------------------------------------------


@pytest.fixture
def fake_server() -> FakeBuildServer:
    return FakeBuildServer()


@pytest.fixture
async def server_url(aiohttp_server, fake_server: FakeBuildServer) -> str:
    server = await aiohttp_server(fake_server.app())
    return f"http://127.0.0.1:{server.port}"


@pytest.fixture
def fake_mcuhome(tmp_path: Path) -> Path:
    path = tmp_path / "fake-mcuhome-sign"
    path.write_text(FAKE_SIGN, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return path


@pytest.fixture
def build_config(config: Config, server_url: str) -> Config:
    from dataclasses import replace

    return replace(config, build_server_url=server_url, build_server_token=TOKEN)


@pytest.fixture
async def build_state(build_config: Config, fake_mcuhome: Path) -> Iterator[AppState]:
    state = AppState(build_config)
    state.builds.builder_command = (sys.executable, str(fake_mcuhome))
    await state.start()
    yield state
    await state.stop()


@pytest.fixture
async def build_client(aiohttp_client, build_state: AppState):
    return await aiohttp_client(create_app(build_state, TrustMode.PUBLIC))


async def _await_artifacts(state: AppState, job_id: str, tries: int = 400) -> Path:
    """Wait for the automatic fetch-and-sign to finish."""
    import asyncio

    directory = state.builds.local_directory(job_id)
    for _ in range(tries):
        if (directory / "build-manifest.json").is_file() and not state.builds._finishing:
            return directory
        await asyncio.sleep(0.01)
    raise AssertionError("the artifacts never arrived")


# --------------------------------------------------------------------------
# Without a build server
# --------------------------------------------------------------------------


async def test_without_a_build_server_the_commands_refuse_in_plain_language(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/submit", {"name": "bench-node"})

    assert frame["error"]["code"] == "unavailable"
    message = frame["error"]["message"]
    # The refusal has to name the configuration, or it is just a "no".
    assert "MCUHOME_DASHBOARD_BUILD_SERVER_URL" in message
    assert "MCUHOME_DASHBOARD_BUILD_SERVER_TOKEN" in message
    assert "never compiles" in message


async def test_server_info_says_whether_a_build_is_possible(client) -> None:
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "server/info"))["payload"]

    assert payload["build_server"]["configured"] is False
    # ADR 0007 decision 2, in front of whoever configures the server.
    assert "passcode" in payload["build_server"]["trust_note"]


async def test_build_status_reports_the_absence_rather_than_failing(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/status")

    # Not an error frame: "there is no build server" is a valid answer to
    # "what is the build server doing".
    assert frame["type"] == "result"
    assert frame["payload"]["queue"] is None
    assert "MCUHOME_DASHBOARD_BUILD_SERVER_URL" in frame["payload"]["message"]


# --------------------------------------------------------------------------
# With one
# --------------------------------------------------------------------------


async def test_a_build_submits_the_resolved_model_and_the_public_key(
    build_client, build_state, fake_server: FakeBuildServer
) -> None:
    async with build_client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/submit", {"name": "bench-node"})

    assert frame["type"] == "result", frame
    assert frame["payload"]["ok"] is True
    assert frame["payload"]["job_id"] == fake_server.job_id

    (submission,) = fake_server.submissions
    # ADR 0007 decision 1: the *resolved* model crosses, not the tree.
    assert submission["model"]["device"]["name"] == "bench-node"
    assert submission["model"]["model_version"] == 1
    assert submission["model_version"] == 1
    # ADR 0007 decision 3: the public half, and only the public half.
    assert submission["public_key"].startswith("-----BEGIN PUBLIC KEY-----")
    assert "PRIVATE" not in submission["public_key"]
    assert submission["no_sign"] is True
    # And the private key stayed here.
    key = build_state.config.data_dir / "signing.key"
    assert key.is_file()
    assert key.stat().st_mode & 0o077 == 0


async def test_a_configuration_that_does_not_resolve_never_reaches_the_wire(
    build_client, fake_server: FakeBuildServer
) -> None:
    async with build_client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/submit", {"name": "broken-node"})

    # A successful command carrying diagnostics, like device/validate —
    # and a compile that never started, ten minutes not wasted.
    assert frame["type"] == "result"
    assert frame["payload"]["ok"] is False
    assert frame["payload"]["job_id"] is None
    assert frame["payload"]["errors"][0]["line"] == 3
    assert fake_server.submissions == []


async def test_an_unknown_device_is_not_found(build_client) -> None:
    async with build_client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/submit", {"name": "nope"})
    assert frame["error"]["code"] == "not_found"


async def test_job_events_reach_the_browser(build_client, fake_server) -> None:
    # Not `call()`: that helper discards frames it is not waiting for,
    # and the events are exactly those frames.
    async with build_client.ws_connect("/ws") as ws:
        await ws.send_json({"id": "1", "type": "build/submit", "payload": {"name": "bench-node"}})

        result = None
        states: list[str] = []
        while result is None or "succeeded" not in states:
            frame = await ws.receive_json(timeout=15)
            if frame.get("id") == "1":
                result = frame
            elif frame.get("event") == "build_job_changed":
                states.append(frame["payload"]["job"].get("state"))

    assert result["type"] == "result"
    # Every transition the build server reported, relayed onto the
    # dashboard's own bus and out to the browser.
    assert "running" in states
    assert "succeeded" in states


async def test_a_model_version_mismatch_is_refused_naming_both(
    build_client, fake_server: FakeBuildServer
) -> None:
    fake_server.capabilities["model_version"] = {"min": 2, "max": 3}

    async with build_client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/submit", {"name": "bench-node"})

    assert frame["error"]["code"] == "unsupported"
    assert "1" in frame["error"]["message"]
    assert "2-3" in frame["error"]["message"]
    assert fake_server.submissions == []


async def test_a_build_server_whose_builder_cannot_build_is_refused_early(
    build_client, fake_server: FakeBuildServer
) -> None:
    fake_server.capabilities["builder"] = {"usable": False, "model_input": False}

    async with build_client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/submit", {"name": "bench-node"})

    assert frame["error"]["code"] == "unsupported"
    assert "cannot take a resolved device model" in frame["error"]["message"]
    assert fake_server.submissions == []


async def test_a_wrong_token_says_which_setting_is_wrong(
    aiohttp_client, build_config: Config
) -> None:
    from dataclasses import replace

    state = AppState(replace(build_config, build_server_token="wrong"))
    await state.start()
    try:
        client = await aiohttp_client(create_app(state, TrustMode.PUBLIC))
        async with client.ws_connect("/ws") as ws:
            frame = await call(ws, "build/submit", {"name": "bench-node"})
        assert frame["error"]["code"] == "unauthorized"
        assert "MCUHOME_DASHBOARD_BUILD_SERVER_TOKEN" in frame["error"]["message"]
    finally:
        await state.stop()


async def test_an_unreachable_build_server_is_a_refusal_not_a_traceback(
    aiohttp_client, config: Config
) -> None:
    from dataclasses import replace

    # Port 1 on loopback: nothing listens there and nothing will.
    state = AppState(
        replace(config, build_server_url="http://127.0.0.1:1", build_server_token=TOKEN)
    )
    await state.start()
    try:
        client = await aiohttp_client(create_app(state, TrustMode.PUBLIC))
        async with client.ws_connect("/ws") as ws:
            frame = await call(ws, "build/submit", {"name": "bench-node"})
        assert frame["error"]["code"] == "unavailable"
        assert "could not be reached" in frame["error"]["message"]
    finally:
        await state.stop()


async def test_cancel_and_status_pass_through(build_client, fake_server: FakeBuildServer) -> None:
    async with build_client.ws_connect("/ws") as ws:
        cancel = await call(ws, "build/cancel", {"job_id": fake_server.job_id}, frame_id="c")
        status = await call(ws, "build/status", frame_id="s")

    assert fake_server.cancelled == [fake_server.job_id]
    assert cancel["payload"]["job"]["state"] == "cancelled"
    assert status["payload"]["queue"]["slots"] == 1
    assert status["payload"]["server"]["configured"] is True
    assert status["payload"]["server"]["capabilities"]["arch"] == "x86_64"


async def test_build_log_passes_the_offset_through(
    build_client, fake_server: FakeBuildServer
) -> None:
    async with build_client.ws_connect("/ws") as ws:
        whole = (await call(ws, "build/log", {"job_id": fake_server.job_id}, frame_id="a"))[
            "payload"
        ]
        rest = (
            await call(ws, "build/log", {"job_id": fake_server.job_id, "offset": 10}, frame_id="b")
        )["payload"]

    assert whole["text"] == fake_server.log.decode()
    assert rest["text"] == fake_server.log[10:].decode()


async def test_a_negative_offset_is_refused(build_client, fake_server) -> None:
    async with build_client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/log", {"job_id": fake_server.job_id, "offset": -5})
    assert frame["error"]["code"] == "bad_request"


# --------------------------------------------------------------------------
# Artifacts and the signing hand-off
# --------------------------------------------------------------------------


async def test_artifacts_are_fetched_verified_and_signed_by_themselves(
    build_client, build_state, fake_server: FakeBuildServer
) -> None:
    async with build_client.ws_connect("/ws") as ws:
        await call(ws, "build/submit", {"name": "bench-node"})
        directory = await _await_artifacts(build_state, fake_server.job_id)

    # Downloaded and byte-exact.
    assert (directory / "build/app/zephyr/zephyr.bin").read_bytes() == APPLICATION
    assert (directory / "build/mcuboot/zephyr/zephyr.hex").read_bytes() == BOOTLOADER

    # ADR 0007 decision 3: the build server returned it unsigned and this
    # side applied the signature.
    manifest = json.loads((directory / "build-manifest.json").read_text())
    assert manifest["signing"]["signed"] is True
    assert manifest["signing"]["signed_by_the_build"] is False
    signed = directory / "build/app/zephyr/zephyr.signed.bin"
    assert signed.read_bytes() == APPLICATION + b"SIGNATURE"


async def test_a_corrupted_artifact_is_refused_rather_than_signed(
    build_client, build_state, fake_server: FakeBuildServer
) -> None:
    from mcuhome_dashboard.buildclient import BuildServerError

    # The build says the artifact hashes to one thing; the bytes hash to
    # another. Whichever half is wrong, the answer is not "sign it".
    fake_server.corrupt_index = True

    with pytest.raises(BuildServerError) as caught:
        await build_state.builds.fetch_artifacts(fake_server.job_id)

    assert "does not match the hash" in caught.value.message
    # A corrupted artifact that got signed would be a corrupted artifact
    # with a valid signature.
    directory = build_state.builds.local_directory(fake_server.job_id)
    assert not (directory / "build/app/zephyr/zephyr.signed.bin").exists()


async def test_the_rest_endpoint_serves_the_signed_local_copy(
    build_client, build_state, fake_server: FakeBuildServer
) -> None:
    async with build_client.ws_connect("/ws") as ws:
        await call(ws, "build/submit", {"name": "bench-node"})
        await _await_artifacts(build_state, fake_server.job_id)
        listing = (await call(ws, "build/artifacts", {"job_id": fake_server.job_id}, frame_id="a"))[
            "payload"
        ]

    assert listing["signed"] is True
    urls = {item["path"]: item["url"] for item in listing["files"]}
    response = await build_client.get(urls["build/app/zephyr/zephyr.signed.bin"])
    assert response.status == 200
    assert await response.read() == APPLICATION + b"SIGNATURE"
    assert "attachment" in response.headers["Content-Disposition"]


@pytest.mark.parametrize(
    "path",
    [
        # Percent-encoded, because an HTTP client resolves `../` in the
        # URL before it sends anything — which would test the client and
        # not the handler.
        "..%2F..%2F..%2Fetc%2Fpasswd",
        "build%2F..%2F..%2F..%2F..%2Fetc%2Fpasswd",
        "nope.bin",
        "build",  # a directory, not a file
    ],
)
async def test_the_rest_endpoint_serves_nothing_outside_the_job(
    build_client, build_state, fake_server: FakeBuildServer, path: str
) -> None:
    from yarl import URL

    async with build_client.ws_connect("/ws") as ws:
        await call(ws, "build/submit", {"name": "bench-node"})
        await _await_artifacts(build_state, fake_server.job_id)

    response = await build_client.get(
        URL(f"/api/builds/{fake_server.job_id}/artifacts/{path}", encoded=True)
    )
    assert response.status == 404


async def test_asking_for_artifacts_that_are_not_here_yet(build_client) -> None:
    async with build_client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/artifacts", {"job_id": "20260101-000000-aaaaaa"})
    assert frame["error"]["code"] == "not_found"


# --------------------------------------------------------------------------
# URL handling
# --------------------------------------------------------------------------


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
    assert http_url(configured) == http
    assert ws_url(configured) == socket
