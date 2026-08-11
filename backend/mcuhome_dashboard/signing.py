# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The dashboard's firmware signing key, and the detached signature.

ADR 0007 decision 3 splits one operation in two: whatever compiles gets
the **public** key, builds it into MCUboot and delivers the application
image *unsigned*, and the side that has the private key applies the
signature. Firmware E56 generalised the same split across all three
build methods — a container here, a build server, a west workspace — so
the split is now a property of every build rather than of the remote
one, and this module is the second half of it wherever the first half
ran. ADR 0008 decision 2 puts the key at ``/data/signing.key`` — the
App's private volume, mode ``0600``, never in the configuration tree,
because the tree is the thing users sync to git and paste into forum
posts.

**Everything here is in-process**, through ``mcuhome.workbench``. That
package is the builder's answer to "where the key lives, how one is
generated, what is refused, and which exact ``imgtool`` arguments the
build was linked for", and a dashboard-side re-implementation of any of
it would be a second opinion about the most consequential secret in the
system. Key custody is ``mcuhome.workbench.signing``; the signature
itself is ``mcuhome.workbench.imgtool``, which is the same code the
``mcuhome sign`` command runs.

**It used to shell out to** ``mcuhome sign <build dir>``, and ADR 0013
stopped: that command belongs to the ``mcuhome`` CLI distribution, which
a dashboard install does not carry and must not start carrying — the
dashboard depends on ``mcuhome-workbench`` and on nothing that ships a
console script (ADR 0011, firmware ADR 0020 decision 2). The subprocess
was a call to a program that is not there, so the step is taken through
the library both sides already share. The bytes are the same bytes: the
argument order lives in :func:`mcuhome.workbench.imgtool.sign_command`
and neither side spells it out.

**Two report shapes, because there are two** (firmware E55). A build
that ran in a build container or on a build server delivers the
contract's ``build-report.json``; a host build writes
``build-manifest.json`` and has a manifest to fold the signature back
into. :attr:`mcuhome.workbench.api.BuildOutcome.report` says which one
this build wrote, so the caller passes it through rather than this
module guessing by looking around the directory.

**A key is generated on first need, and the dashboard says so.** That is
the builder's own behaviour and the alternative is worse: refusing to
build until the user has visited a key-management screen teaches nothing
and blocks the first success. What must never happen quietly is the
*second* generation — a changed key orphans every device already
bootstrapped with the old one (ADR 0008 decision 3) — so creation is
logged at warning level and reported in the result.

The generation happens in :func:`public_key`, which a build calls
**before** it starts, because the public half is a build input
(:attr:`…api.BuildRequest.signing_pub`). By the time
:func:`sign_build` runs, the key therefore exists, and the signer is
called with ``create=False`` underneath — a delivered build has to be
signed with the key its device's bootloader already carries, and
inventing one at that point produces firmware nothing accepts.

**The private half never leaves this process.** It is read here and
nowhere else: what travels into a build request is the PEM public half
(ADR 0007 decision 3, ADR 0015 decision 8), and
:class:`…api.BuildRequest` has no field a private key fits in, on any
build method. That is the structural half of the invariant; this module
is the other half.
"""

from __future__ import annotations

import asyncio
import logging
import os
from dataclasses import dataclass
from pathlib import Path

from mcuhome.model.manifest import MANIFEST_FILE
from mcuhome.workbench import imgtool
from mcuhome.workbench.api import MCUHomeError, read_manifest
from mcuhome.workbench.imgtool import BUILD_REPORT_FILE
from mcuhome.workbench.signing import KEY_VAR, public_key_pem, signing_key

__all__ = [
    "BUILD_REPORT_FILE",
    "KEY_FILE",
    "KEY_VAR",
    "MANIFEST_FILE",
    "KeyCustodyError",
    "SigningError",
    "SigningResult",
    "key_path",
    "manifest_is_signed",
    "public_key",
    "sign_build",
]

logger = logging.getLogger(__name__)

#: The name ADR 0008 decision 2 gives it inside the App's private volume.
KEY_FILE = "signing.key"


class SigningError(RuntimeError):
    """The signature could not be applied, in plain language."""


class KeyCustodyError(SigningError):
    """The key itself could not be read or created — said without the path.

    Every failure in this module ends up in a build record's ``errors``,
    and that record is published to **every subscribed browser tab**
    (:meth:`…builds.BuildRecord.to_dict` promises no paths outside the
    build directory). The libraries underneath name the key file in their
    message, and with ``MCUHOME_SIGNING_KEY`` set that is a path an
    operator chose — so the split is made here, where the path is known:
    :attr:`message` names the key by its role, :attr:`detail` carries
    what actually happened and belongs in the log.
    """

    #: Where to look, without saying where it is — not even the default
    #: file name, so that "no key path crosses the wire" is a property a
    #: test can assert rather than a promise. The operator has the server
    #: log for the rest.
    hint = (
        f"the signing key is the file {KEY_VAR} names, or the default one in this "
        "dashboard's own private data directory. The server log says which file "
        "it is and why it could not be used."
    )

    def __init__(self, detail: str) -> None:
        super().__init__("The firmware signing key could not be read or created.")
        #: The full, path-carrying account. Never crosses the wire.
        self.detail = detail


@dataclass(frozen=True)
class SigningResult:
    """What one signing step did."""

    #: The key file used.
    key: Path
    #: True when this call had to create the key — which it never is,
    #: because :func:`sign_build` signs with ``create=False``.
    #: :func:`public_key` is the one call that may create a key, and the
    #: build record reports either of them having done so.
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
        raise KeyCustodyError(str(error)) from error
    except OSError as error:
        raise KeyCustodyError(
            f"The firmware signing key {path} cannot be read: {error}."
        ) from error
    if key.created:
        logger.warning(
            "No firmware signing key existed, so one was generated at %s. Every "
            "device you bootstrap from now on trusts it, and replacing it later "
            "means bootstrapping each of them again. Back it up.",
            key.path,
        )
    return pem, key.created


def manifest_is_signed(build_dir: Path, *, report: str = MANIFEST_FILE) -> bool:
    """Whether the build in *build_dir* already carries a signature.

    Reads the manifest's ``signing.signed`` — "is there a signature in
    this directory now", which is the question a flasher asks, as
    opposed to ``signed_by_the_build``, which is the historical fact of
    whether a private key was ever near the machine that compiled.

    A §7.2.1 ``build-report.json`` answers ``False`` always and
    correctly: a delivered build is unsigned by construction (firmware
    E56), and the report is written by the build environment, which has
    no signature to record and never gains one — the signed files land
    beside it without it being rewritten.
    """
    if report != MANIFEST_FILE:
        return False
    try:
        manifest = read_manifest(build_dir / MANIFEST_FILE)
    except MCUHomeError:
        return False
    signing = manifest.get("signing")
    return bool(isinstance(signing, dict) and signing.get("signed"))


def _sign_blocking(build_dir: Path, *, key: Path, report: str, env: dict[str, str]) -> list[Path]:
    """The signature itself, off the event loop. Returns what it wrote.

    ``topdir=None`` on both calls: a west workspace is where ``imgtool``
    can be borrowed from when there is none installed, and the dashboard
    never compiles (ADR 0003), so there is no workspace here to borrow
    one from. ``imgtool`` is a dependency of the machine that signs, and
    its absence is a refusal that names the ``pip install`` — which is
    the honest answer rather than a search for a workspace that this
    deployment does not have.
    """
    signer = imgtool.sign_build if report == MANIFEST_FILE else imgtool.sign_report
    plan = signer(build_dir, key=key, env=env, topdir=None)
    return list(plan.outputs)


async def sign_build(
    build_dir: Path,
    *,
    key: Path,
    report: str = BUILD_REPORT_FILE,
    env: dict[str, str] | None = None,
) -> SigningResult:
    """Apply the detached signature to a finished build directory.

    *report* is :attr:`mcuhome.workbench.api.BuildOutcome.report`: which
    of the two documents this build wrote, and therefore which of the two
    signers reads it. Passing it through rather than sniffing the
    directory is what keeps "which build shape is this" a fact the build
    stated instead of a guess made afterwards.

    *env* is stated, never read from the process inside the library —
    which ``imgtool`` runs is part of what a signature is. ``None`` means
    this process's environment, which is what a server that was started
    with one wants.

    Idempotent by omission rather than by cleverness: the caller checks
    :func:`manifest_is_signed` first, because signing twice would produce
    a second, equally valid signature over the same bytes and a manifest
    that had been rewritten for no reason.
    """
    environment = dict(os.environ) if env is None else dict(env)
    # ``create=False``, and it is the documented half of the split: the
    # key was created — if it had to be — by the :func:`public_key` call
    # that produced this build's input, minutes ago. Inventing one *here*
    # would sign an image with a key its own MCUboot does not carry and
    # would silently make that key the dashboard's, orphaning every
    # device bootstrapped against the old one (ADR 0008 decision 3). A
    # key that vanished in between is a loud, recoverable failed build.
    _pem, created = await asyncio.to_thread(public_key, key, create=False)

    try:
        outputs = await asyncio.to_thread(
            _sign_blocking, build_dir, key=key, report=report, env=environment
        )
    except MCUHomeError as error:
        # The builder's refusals carry the fix in their hint, and losing
        # it here would turn "imgtool is not installed, here is the pip
        # line" back into "signing failed".
        hint = getattr(error, "hint", None)
        message = f"{error}{f' {hint}' if hint else ''}"
        if str(key) in message:
            # A refusal that names the key file is a key-custody refusal
            # whatever raised it — the signers resolve the key themselves
            # (``create=False`` there too), so this catches the narrow
            # race where it disappeared after the check above.
            raise KeyCustodyError(message) from error
        raise SigningError(message) from error
    except OSError as error:
        raise SigningError(f"The firmware in {build_dir} could not be signed: {error}.") from error

    return SigningResult(
        key=key,
        created_key=created,
        outputs=tuple(sorted(path.name for path in outputs)),
    )
