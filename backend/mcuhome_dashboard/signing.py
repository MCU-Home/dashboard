# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The dashboard's firmware signing key, and the detached signature.

ADR 0007 decision 3 splits one operation across two machines: the build
server compiles the **public** key into MCUboot and returns the
application image unsigned, and the dashboard applies the signature
where the private key is. ADR 0008 decision 2 puts that key at
``/data/signing.key`` — the App's private volume, mode ``0600``, never
in the configuration tree, because the tree is the thing users sync to
git and paste into forum posts.

Two halves, deliberately implemented differently:

**Key custody is in-process**, through ``mcuhome.signing``. That module
is the builder's answer to "where the key lives, how one is generated,
and what is refused" (its README says so), and a dashboard-side
re-implementation of any of it would be a second opinion about the most
consequential secret in the system. It is not part of ``mcuhome.api``
yet; that is named in :mod:`mcuhome_dashboard.builder`'s docstring
alongside the other two reaches past the public surface.

**Signing itself is a subprocess**, ``mcuhome sign <build dir>``. The
CLI already does exactly this operation — it reads the manifest's
``imgtool`` parameters, runs the signature, and folds the result back
into the manifest so the directory describes itself afterwards. Calling
it keeps ``imgtool``'s dependency tree out of the dashboard's import
graph and keeps one implementation of the step that has to produce the
same bytes as an inline build.

**A key is generated on first need, and the dashboard says so.** That is
the builder's own behaviour and the alternative is worse: refusing to
build until the user has visited a key-management screen teaches nothing
and blocks the first success. What must never happen quietly is the
*second* generation — a changed key orphans every device already
bootstrapped with the old one (ADR 0008 decision 3) — so creation is
logged at warning level and reported in the result.

**Nothing calls this module at the moment, and that is not a reason to
delete it.** The build-server client that did was removed with the job
protocol; ADR 0012 decision 3 names user key handling and detached
signing as what the dashboard keeps when everything else about the
build path is replaced. The one sentence above that describes a
*mechanism* rather than a decision — the build server compiling the
public key in and returning the image unsigned — is the job protocol's
phrasing of it; the session protocol arranges the same split
differently, and the split itself is what stands.
"""

from __future__ import annotations

import asyncio
import logging
import os
from dataclasses import dataclass
from pathlib import Path

from mcuhome.workbench.api import MCUHomeError, read_manifest
from mcuhome.workbench.signing import KEY_VAR, public_key_pem, signing_key

__all__ = [
    "KEY_FILE",
    "KEY_VAR",
    "SigningError",
    "SigningResult",
    "manifest_is_signed",
    "public_key",
    "sign_build",
]

logger = logging.getLogger(__name__)

#: The name ADR 0008 decision 2 gives it inside the App's private volume.
KEY_FILE = "signing.key"


class SigningError(RuntimeError):
    """The signature could not be applied, in plain language."""


@dataclass(frozen=True)
class SigningResult:
    """What one signing step did."""

    #: The key file used.
    key: Path
    #: True when this call had to create the key. Never quietly true.
    created_key: bool
    #: Paths the signature produced, relative to the build directory.
    outputs: tuple[str, ...]

    def to_dict(self) -> dict[str, object]:
        return {
            "key": str(self.key),
            "created_key": self.created_key,
            "outputs": list(self.outputs),
        }


def key_path(data_dir: Path, *, env: dict[str, str] | None = None) -> Path:
    """Where this dashboard's signing key lives.

    ``MCUHOME_SIGNING_KEY`` wins, because a deployment that mounts a key
    from elsewhere has said what it wants; otherwise ADR 0008 decision
    2's location inside the App's private volume.
    """
    environment = os.environ if env is None else env
    override = environment.get(KEY_VAR)
    return Path(override).expanduser() if override else data_dir / KEY_FILE


def public_key(path: Path, *, create: bool = True) -> tuple[str, bool]:
    """The PEM public half of the key at *path*: ``(pem, created)``.

    This is what crosses the wire with every job (ADR 0007 decision 3).
    The private half stays in this file and is read only here and by
    :func:`sign_build`.
    """
    try:
        # An empty environment, and not this process's: *path* is always
        # an explicit override (:func:`key_path` resolved KEY_VAR already),
        # so the library never consults the environment on this call, and
        # saying so is better than handing it one it must not use.
        key = signing_key(path, env={}, create=create)
        pem = public_key_pem(key.path.read_text(encoding="utf-8"))
    except MCUHomeError as error:
        raise SigningError(str(error)) from error
    except OSError as error:
        raise SigningError(f"The firmware signing key {path} cannot be read: {error}.") from error
    if key.created:
        logger.warning(
            "No firmware signing key existed, so one was generated at %s. Every "
            "device you bootstrap from now on trusts it, and replacing it later "
            "means bootstrapping each of them again. Back it up.",
            key.path,
        )
    return pem, key.created


def manifest_is_signed(build_dir: Path) -> bool:
    """Whether the build in *build_dir* already carries a signature.

    Reads the manifest's ``signing.signed`` — "is there a signature in
    this directory now", which is the question a flasher asks, as
    opposed to ``signed_by_the_build``, which is the historical fact of
    whether a private key was ever near the machine that compiled.
    """
    try:
        manifest = read_manifest(build_dir / "build-manifest.json")
    except MCUHomeError:
        return False
    signing = manifest.get("signing")
    return bool(isinstance(signing, dict) and signing.get("signed"))


async def sign_build(
    build_dir: Path, *, key: Path, command: tuple[str, ...] = ("mcuhome",)
) -> SigningResult:
    """Run ``mcuhome sign`` over a finished build directory.

    Idempotent by omission rather than by cleverness: the caller checks
    :func:`manifest_is_signed` first, because signing twice would produce
    a second, equally valid signature over the same bytes and a manifest
    that had been rewritten for no reason.
    """
    pem, created = await asyncio.to_thread(public_key, key)
    del pem  # only wanted for its side effect: the key exists after this

    argv = [*command, "sign", str(build_dir), "--signing-key", str(key)]
    try:
        process = await asyncio.create_subprocess_exec(
            *argv,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await process.communicate()
    except OSError as error:
        raise SigningError(f"The dashboard could not run `mcuhome sign`: {error}.") from error

    if process.returncode != 0:
        detail = (stderr or stdout).decode("utf-8", errors="replace").strip()
        raise SigningError(
            "The firmware could not be signed. "
            f"`mcuhome sign` exited {process.returncode}: {detail}"
        )

    manifest = await asyncio.to_thread(read_manifest, build_dir / "build-manifest.json")
    signing = manifest.get("signing")
    outputs = signing.get("outputs") if isinstance(signing, dict) else None
    return SigningResult(
        key=key,
        created_key=created,
        outputs=tuple(sorted(outputs.values())) if isinstance(outputs, dict) else (),
    )
