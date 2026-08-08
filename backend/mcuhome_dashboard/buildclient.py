# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The dashboard's client for a build server (ADR 0003, ADR 0006).

ADR 0003 decision 2: **the dashboard never compiles.** Not even when
both Apps run on the same Home Assistant instance — that case is not a
special case, it talks to the build server over the protocol exactly as
it would to a machine on the other side of the internet. One code path
is therefore tested by every user, on every deployment, from day one.

This module is that code path. It owns one WebSocket to one build
server and turns its frames into the dashboard's own event bus, so that
everything above it — the commands, the browser — sees a build the same
way it sees a file changing on disk.

Three things it does that are worth reading the code for.

**Negotiation before submission** (ADR 0006 decision 4). ``GET
/capabilities`` is fetched and checked *before* a job is sent: builder
version, ``model_version`` range, and whether the builder can take a
resolved model at all. A mismatch is a refusal that names both sides'
numbers — never a silent fallback, and never a failure ten minutes into
a compile.

**Resolution happens here** (ADR 0007 decision 1). Stages 1-3 run
in-process against the configuration tree, and only the resolved
``device-model.json`` crosses. The build server never learns that a
configuration tree exists, never sees ``secrets.yaml`` and never sees a
file name. What it does necessarily see is the device's commissioning
passcode, because that is compile-time Kconfig — which is why
:func:`describe` puts the trust statement in front of the user who
configured the server.

**The signature is applied on this side** (ADR 0007 decision 3, ADR
0008). The job goes out with the *public* key and ``--no-sign``; when
the artifacts come back, the manifest says ``signed: false`` and this
side runs ``mcuhome sign``. A user gets a flashable image without ever
having sent a private key anywhere, and that hand-off is automatic
rather than a button, because an unsigned image is not a product.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import hashlib
import json
import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit, urlunsplit

import aiohttp

from mcuhome_dashboard import versions
from mcuhome_dashboard.events import EventBus, build_job_output, build_job_state
from mcuhome_dashboard.signing import SigningError, manifest_is_signed, public_key, sign_build

__all__ = [
    "BuildServerClient",
    "BuildServerError",
    "NotConfiguredError",
    "http_url",
    "ws_url",
]

logger = logging.getLogger(__name__)

#: How long a command may wait for its answer. Generous, because the
#: server may be reading a build directory; bounded, because a hung
#: build server must not hang a browser tab for ever.
CALL_TIMEOUT = 60.0

#: How long ``/capabilities`` is trusted before it is asked again. The
#: build server can be restarted with a new builder underneath it.
CAPABILITIES_TTL = 30.0


class BuildServerError(RuntimeError):
    """The build server refused, or could not be reached."""

    def __init__(self, message: str, *, code: str = "unavailable", **detail: Any) -> None:
        super().__init__(message)
        self.message = message
        self.code = code
        self.detail = detail


class NotConfiguredError(BuildServerError):
    """No build server is configured, said in words a user can act on."""

    def __init__(self) -> None:
        super().__init__(
            "No build server is configured, and the dashboard never compiles "
            "firmware itself. Set the build server's address and token — "
            "MCUHOME_DASHBOARD_BUILD_SERVER_URL and "
            "MCUHOME_DASHBOARD_BUILD_SERVER_TOKEN, or --build-server-url and "
            "--build-server-token — and try again. Two Apps on one Home "
            "Assistant instance find each other automatically through "
            "/share/mcuhome/build-server.token.",
            code="unavailable",
        )


def _normalized(url: str, *, scheme_map: dict[str, str], path: str) -> str:
    parts = urlsplit(url if "://" in url else f"http://{url}")
    scheme = scheme_map.get(parts.scheme, parts.scheme)
    base = parts.path.rstrip("/")
    return urlunsplit((scheme, parts.netloc, f"{base}{path}", "", ""))


def http_url(url: str, path: str = "") -> str:
    """The REST form of a configured build-server URL."""
    return _normalized(url, scheme_map={"ws": "http", "wss": "https"}, path=path)


def ws_url(url: str) -> str:
    """The WebSocket form of a configured build-server URL."""
    return _normalized(url, scheme_map={"http": "ws", "https": "wss"}, path="/ws")


@dataclass
class LocalArtifacts:
    """One job's artifacts, downloaded and signed on this side."""

    job_id: str
    directory: Path
    files: tuple[dict[str, Any], ...]
    signed: bool
    signing: dict[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "job_id": self.job_id,
            "directory": str(self.directory),
            "files": [dict(item) for item in self.files],
            "signed": self.signed,
            "signing": self.signing,
        }


class BuildServerClient:
    """One connection to one build server, and the jobs it is watching."""

    def __init__(
        self,
        *,
        url: str | None,
        token: str | None,
        artifact_root: Path,
        signing_key: Path,
        bus: EventBus,
        builder_command: tuple[str, ...] = ("mcuhome",),
    ) -> None:
        self.url = url
        self.token = token
        self.artifact_root = artifact_root
        self.signing_key = signing_key
        self.bus = bus
        self.builder_command = builder_command

        self._session: aiohttp.ClientSession | None = None
        self._ws: aiohttp.ClientWebSocketResponse | None = None
        self._reader: asyncio.Task[None] | None = None
        self._pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._next_id = 0
        self._connect_lock = asyncio.Lock()
        self._capabilities: dict[str, Any] | None = None
        self._capabilities_at = 0.0
        self._followed: set[str] = set()
        self._finishing: set[str] = set()

    # -- configuration -----------------------------------------------

    @property
    def configured(self) -> bool:
        return bool(self.url and self.token)

    def _require(self) -> tuple[str, str]:
        if not self.url or not self.token:
            raise NotConfiguredError()
        return self.url, self.token

    def describe(self) -> dict[str, Any]:
        """What ``server/info`` says about the build side of this install."""
        return {
            "configured": self.configured,
            "url": http_url(self.url) if self.url else None,
            "connected": self._ws is not None and not self._ws.closed,
            "capabilities": self._capabilities,
            "signing_key": str(self.signing_key),
            "artifact_root": str(self.artifact_root),
            # ADR 0007 decision 2, where a user chooses a build server.
            "trust_note": (
                "A build server learns the Matter commissioning passcode of every "
                "device it builds, because those credentials are compiled into the "
                "firmware. Operate it as a trusted machine."
            ),
        }

    # -- lifecycle ---------------------------------------------------

    async def start(self) -> None:
        if self.configured:
            logger.info("build server: %s", http_url(self.url or ""))
        else:
            logger.info(
                "no build server configured; build commands will refuse until "
                "MCUHOME_DASHBOARD_BUILD_SERVER_URL and _TOKEN are set"
            )

    async def stop(self) -> None:
        reader, self._reader = self._reader, None
        if reader is not None:
            reader.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await reader
        if self._ws is not None and not self._ws.closed:
            with contextlib.suppress(Exception):
                await self._ws.close()
        self._ws = None
        if self._session is not None and not self._session.closed:
            await self._session.close()
        self._session = None
        for future in self._pending.values():
            if not future.done():
                future.set_exception(BuildServerError("The dashboard is shutting down."))
        self._pending.clear()

    async def _get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession()
        return self._session

    # -- REST --------------------------------------------------------

    async def capabilities(self, *, refresh: bool = False) -> dict[str, Any]:
        """``GET /capabilities`` — the negotiation of ADR 0006 decision 4."""
        url, token = self._require()
        fresh = time.monotonic() - self._capabilities_at < CAPABILITIES_TTL
        if self._capabilities is not None and fresh and not refresh:
            return self._capabilities

        session = await self._get_session()
        try:
            async with session.get(
                http_url(url, "/capabilities"),
                headers={"Authorization": f"Bearer {token}"},
                timeout=aiohttp.ClientTimeout(total=15),
            ) as response:
                if response.status in (401, 403):
                    raise BuildServerError(
                        "The build server rejected this dashboard's token. Check "
                        "MCUHOME_DASHBOARD_BUILD_SERVER_TOKEN against the token the "
                        "build server logged at startup.",
                        code="unauthorized",
                    )
                response.raise_for_status()
                payload = await response.json()
        except aiohttp.ClientError as error:
            raise BuildServerError(
                f"The build server at {http_url(url)} could not be reached: {error}."
            ) from error
        except TimeoutError as error:
            raise BuildServerError(
                f"The build server at {http_url(url)} did not answer in time."
            ) from error

        self._capabilities = payload
        self._capabilities_at = time.monotonic()
        return payload

    def check_compatibility(self, capabilities: dict[str, Any]) -> None:
        """Refuse a build server this dashboard cannot talk to.

        Both halves of ADR 0006 decision 4 in one place: the model
        handshake of ADR 0007 decision 4, and whether the builder over
        there can do the job at all. Every refusal names both sides.
        """
        model = capabilities.get("model_version") or {}
        low = model.get("min")
        high = model.get("max")
        if (
            isinstance(low, int)
            and isinstance(high, int)
            and not (low <= versions.MODEL_VERSION <= high)
        ):
            raise BuildServerError(
                f"This dashboard sends device-model version "
                f"{versions.MODEL_VERSION} and that build server accepts "
                f"{low}-{high}. Upgrade whichever side is behind; neither may "
                "guess.",
                code="unsupported",
                sends=versions.MODEL_VERSION,
                supported={"min": low, "max": high},
            )

        builder = capabilities.get("builder") or {}
        if builder and not builder.get("usable", True):
            mcuhome = (capabilities.get("mcuhome") or {}).get("version", "unknown")
            raise BuildServerError(
                f"The build server is running mcuhome {mcuhome}, whose builder "
                "cannot take a resolved device model. Its /capabilities says "
                f"{json.dumps(builder)}. Nothing can be built until that build "
                "server has a builder release that can.",
                code="unsupported",
                builder=builder,
            )

    # -- WebSocket ---------------------------------------------------

    async def _connect(self) -> aiohttp.ClientWebSocketResponse:
        if self._ws is not None and not self._ws.closed:
            return self._ws
        async with self._connect_lock:
            if self._ws is not None and not self._ws.closed:
                return self._ws
            url, token = self._require()
            session = await self._get_session()
            try:
                ws = await session.ws_connect(
                    ws_url(url),
                    headers={"Authorization": f"Bearer {token}"},
                    heartbeat=30.0,
                    timeout=aiohttp.ClientWSTimeout(ws_close=15.0),
                )
            except aiohttp.ClientError as error:
                raise BuildServerError(
                    f"The build server at {http_url(url)} could not be reached: {error}."
                ) from error
            self._ws = ws
            # Followed jobs live on a connection, so a reconnect has lost
            # them; the next `follow` re-establishes them from an offset.
            self._followed.clear()
            self._reader = asyncio.create_task(self._read_loop(ws), name="mcuhome-build-reader")
            return ws

    async def _read_loop(self, ws: aiohttp.ClientWebSocketResponse) -> None:
        try:
            async for message in ws:
                if message.type is not aiohttp.WSMsgType.TEXT:
                    continue
                try:
                    frame = json.loads(message.data)
                except ValueError:
                    logger.warning("the build server sent a frame that is not JSON")
                    continue
                if not isinstance(frame, dict):
                    continue
                self._dispatch(frame)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("the build-server connection failed")
        finally:
            for future in list(self._pending.values()):
                if not future.done():
                    future.set_exception(
                        BuildServerError("The connection to the build server dropped.")
                    )
            self._pending.clear()
            self._followed.clear()

    def _dispatch(self, frame: dict[str, Any]) -> None:
        if frame.get("type") == "event":
            self._on_event(frame.get("event"), frame.get("payload") or {})
            return
        frame_id = frame.get("id")
        future = self._pending.pop(str(frame_id), None)
        if future is None or future.done():
            return
        if frame.get("type") == "error":
            error = frame.get("error") or {}
            future.set_exception(
                BuildServerError(
                    str(error.get("message") or "The build server refused."),
                    code=str(error.get("code") or "unavailable"),
                    **{k: v for k, v in error.items() if k not in {"code", "message"}},
                )
            )
        else:
            future.set_result(frame.get("payload") or {})

    def _on_event(self, name: str | None, payload: dict[str, Any]) -> None:
        if name == "job_state_changed":
            job = payload.get("job") or {}
            self.bus.publish(build_job_state(job))
            if job.get("state") == "succeeded" and job.get("id"):
                # An unsigned image is not a product: fetch and sign as
                # soon as the build says it is done (ADR 0007/0008).
                self._spawn_finish(str(job["id"]))
        elif name == "job_output":
            self.bus.publish(
                build_job_output(
                    str(payload.get("job_id") or ""),
                    int(payload.get("offset") or 0),
                    str(payload.get("text") or ""),
                )
            )

    async def call(self, command: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        """Send one command and wait for the frame that answers it."""
        ws = await self._connect()
        self._next_id += 1
        frame_id = str(self._next_id)
        future: asyncio.Future[dict[str, Any]] = asyncio.get_running_loop().create_future()
        self._pending[frame_id] = future
        try:
            await ws.send_json({"id": frame_id, "type": command, "payload": payload or {}})
            return await asyncio.wait_for(future, timeout=CALL_TIMEOUT)
        except TimeoutError as error:
            raise BuildServerError(
                f'The build server did not answer "{command}" within {CALL_TIMEOUT:.0f} seconds.'
            ) from error
        except (aiohttp.ClientError, ConnectionResetError) as error:
            raise BuildServerError(f"The build server connection failed: {error}.") from error
        finally:
            self._pending.pop(frame_id, None)

    # -- operations --------------------------------------------------

    async def submit(
        self, *, model: dict[str, Any], options: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        """Send one resolved model, after checking the two sides agree."""
        self._require()
        self.check_compatibility(await self.capabilities())
        pem, created = await asyncio.to_thread(public_key, self.signing_key)

        result = await self.call(
            "submit_job",
            {
                "model": model,
                "model_version": versions.MODEL_VERSION,
                "public_key": pem,
                "no_sign": True,
                "options": options or {},
            },
        )
        job_id = str(result.get("job_id") or "")
        if job_id:
            await self.follow(job_id, offset=0)
        return {**result, "created_signing_key": created}

    async def cancel(self, job_id: str) -> dict[str, Any]:
        self._require()
        return await self.call("cancel_job", {"job_id": job_id})

    async def status(self, *, limit: int = 50) -> dict[str, Any]:
        self._require()
        return await self.call("queue_status", {"limit": limit})

    async def follow(self, job_id: str, *, offset: int = 0) -> dict[str, Any]:
        """Ask for log output from *offset*, history first (ADR 0006 §6)."""
        self._require()
        result = await self.call("follow_job", {"job_id": job_id, "offset": offset})
        if result.get("live"):
            self._followed.add(job_id)
        return result

    # -- artifacts ---------------------------------------------------

    def local_directory(self, job_id: str) -> Path:
        return self.artifact_root / job_id

    def _spawn_finish(self, job_id: str) -> None:
        if job_id in self._finishing:
            return
        self._finishing.add(job_id)

        async def run() -> None:
            try:
                await self.fetch_artifacts(job_id)
            except (BuildServerError, SigningError, OSError):
                logger.exception("could not collect the artifacts of job %s", job_id)
            finally:
                self._finishing.discard(job_id)

        asyncio.create_task(run(), name=f"mcuhome-build-finish-{job_id}")

    async def fetch_artifacts(self, job_id: str) -> LocalArtifacts:
        """Download one job's artifacts, verify them, and sign the image.

        Verification is both hashes ADR 0006 decision 7 provides: each
        chunk against its own SHA-256, and the assembled file against the
        one the *build* computed. A mismatch is a refusal — a corrupted
        artifact that got signed would be a corrupted artifact with a
        valid signature.
        """
        self._require()
        index = await self.call("download_artifacts", {"job_id": job_id})
        directory = self.local_directory(job_id)
        directory.mkdir(parents=True, exist_ok=True)

        entries = index.get("artifacts") or []
        written: list[dict[str, Any]] = []
        for entry in entries:
            path = entry.get("path")
            if not isinstance(path, str):
                continue
            target = directory / path
            if not target.resolve().is_relative_to(directory.resolve()):
                raise BuildServerError(
                    f"The build server offered an artifact path outside the job "
                    f'directory: "{path}".',
                    code="bad_request",
                )
            data = await self._download(job_id, path)
            expected = entry.get("sha256")
            actual = hashlib.sha256(data).hexdigest()
            if isinstance(expected, str) and expected and expected != actual:
                raise BuildServerError(
                    f'The artifact "{path}" does not match the hash its build '
                    "manifest states. It was not written; build again.",
                    code="conflict",
                )
            target.parent.mkdir(parents=True, exist_ok=True)
            await asyncio.to_thread(target.write_bytes, data)
            written.append({**entry, "sha256": actual})

        signed = await asyncio.to_thread(manifest_is_signed, directory)
        signing_result = None
        if not signed:
            # ADR 0007 decision 3: the build server returned the image
            # unsigned on purpose, and this is the other half of it.
            result = await sign_build(directory, key=self.signing_key, command=self.builder_command)
            signing_result = result.to_dict()
            signed = True

        artifacts = LocalArtifacts(
            job_id=job_id,
            directory=directory,
            files=tuple(written),
            signed=signed,
            signing=signing_result,
        )
        self.bus.publish(build_job_state({"id": job_id, "artifacts": artifacts.to_dict()}))
        logger.info("job %s: %d artifacts collected and signed", job_id, len(written))
        return artifacts

    async def _download(self, job_id: str, path: str) -> bytes:
        assembled = bytearray()
        offset = 0
        while True:
            chunk = await self.call(
                "download_artifacts", {"job_id": job_id, "path": path, "offset": offset}
            )
            data = base64.b64decode(chunk.get("data") or "")
            expected = chunk.get("sha256")
            if isinstance(expected, str) and hashlib.sha256(data).hexdigest() != expected:
                raise BuildServerError(
                    f'A chunk of "{path}" did not survive the transfer. Try again.',
                    code="conflict",
                )
            assembled += data
            offset = int(chunk.get("next_offset") or (offset + len(data)))
            if chunk.get("eof"):
                return bytes(assembled)
            if not data:  # pragma: no cover - a server that never ends
                raise BuildServerError(
                    f'The build server stopped sending "{path}" without finishing it.'
                )
