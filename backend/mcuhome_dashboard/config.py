# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Runtime configuration: command line, environment, and the auth rules.

Every option can be given on the command line or through an environment
variable prefixed ``MCUHOME_DASHBOARD_`` — the command line wins. The
environment form is what an app's ``run`` script and a ``docker run``
use; the command line is what a developer uses.

The password rules of ADR 0009 decision 2 live in :func:`resolve_password`
and are the security-relevant part of this module:

* a configured password is always honoured;
* a **loopback-only** bind runs without authentication (the process is
  reachable only from the machine it runs on);
* **any other bind** requires a password, and generates one when none
  was configured — the code-server pattern that keeps a fresh container
  usable in one step while keeping it closed.

There is no branch that binds a non-loopback address without
authentication, not even behind a warning.

**The build settings are live again** (ADR 0013). They outlived two
protocols: ADR 0012 decision 3 carried ADR 0006's transport and threat
model forward — WebSocket plus bearer token, TLS at the deployment, the
auto-pairing file — while the client that read them was dismantled, and
ADR 0013 gives them a reader again. They are not resolved *for* the
session protocol any more: they are resolved into
:class:`mcuhome.workbench.api.BuildRequest`, whose ``server`` and
``token`` the builder's ``remote`` method uses, and the protocol on the
wire is the builder's business rather than this file's.

**This is the dashboard's own configuration surface, deliberately**
(ADR 0013 decision 2). The command line reads no
``build-servers.toml``: the XDG ladder of firmware E53/E63 is the
``mcuhome`` command line's, where a human types ``--server`` and expects
their shell's configuration to be found. A dashboard is configured by
whoever deploys it — App options, ``docker run`` environment, flags —
and a second, invisible ladder underneath those would mean an App whose
build server depends on a file in the home directory of whichever user
the container happens to run as.

**Which build method runs is configuration, not code** (ADR 0013
decision 1). :attr:`Config.build_method` is passed to
:func:`mcuhome.workbench.api.run_build` and nothing here interprets it;
``None`` means "no preference" and takes the builder's own default. A
method this installation cannot run refuses in the builder's own words,
which is a better answer than a shorter list of choices here.
"""

from __future__ import annotations

import argparse
import ipaddress
import os
import secrets
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field, replace
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

__all__ = [
    "DEFAULT_BUILD_JOBS",
    "DEFAULT_HOST",
    "DEFAULT_PAIR_FILE",
    "DEFAULT_POLL_INTERVAL",
    "DEFAULT_PORT",
    "ENV_PREFIX",
    "Config",
    "build_parser",
    "default_data_dir",
    "http_url",
    "is_loopback_host",
    "load_config",
    "resolve_build_server_token",
    "resolve_password",
    "split_paths",
    "ws_url",
]

ENV_PREFIX = "MCUHOME_DASHBOARD_"

#: Where a same-host build server publishes its token (ADR 0006 decision
#: 8). Both Apps share ``/share`` on one Home Assistant instance, so the
#: common installation has no protocol configuration at all.
DEFAULT_PAIR_FILE = Path("/share/mcuhome/build-server.token")

#: The build server's port, so that auto-pairing needs no URL either.
DEFAULT_BUILD_SERVER_URL = "http://127.0.0.1:8100"

#: Home Assistant apps conventionally serve their ingress site here, and
#: a standalone dashboard has no reason to pick a different number.
DEFAULT_PORT = 8099
DEFAULT_HOST = "127.0.0.1"
#: Seconds between config-tree polls. No inotify: a poll works the same
#: on a bind mount, an SD card and a network share (ADR 0008), and the
#: tree is tens of files, not thousands.
DEFAULT_POLL_INTERVAL = 2.0

#: Shipped with the wheel. The Vite build of the frontend (ADR 0005)
#: writes its output here; until Block 3 it holds a placeholder shell.
DEFAULT_STATIC_ROOT = Path(__file__).resolve().parent / "static"

#: Parallel compile jobs a build is given. Every core, because a build is
#: the only heavy thing this process ever starts and it starts one at a
#: time (ADR 0013 decision 3). A machine that cannot afford that — a
#: Home Assistant box with 2 GB of RAM meeting a Matter build — turns it
#: down with ``--build-jobs``; nothing here can measure memory pressure
#: honestly, so nothing here pretends to.
DEFAULT_BUILD_JOBS = os.cpu_count() or 1


def split_paths(raw: str) -> tuple[Path, ...]:
    """A ``PATH``-style list of directories, order kept, duplicates dropped."""
    seen: dict[str, None] = {}
    for part in raw.split(os.pathsep):
        cleaned = part.strip()
        if cleaned:
            seen.setdefault(cleaned, None)
    return tuple(Path(item).expanduser() for item in seen)


def default_data_dir(env: Mapping[str, str] | None = None) -> Path:
    """The App's private volume, or its equivalent outside one.

    ADR 0008 puts two things here and only here: the firmware signing
    key and the artifacts downloaded from a build server. ``/data`` is
    what a Home Assistant App gets; a developer install gets a state
    directory under their home rather than something in the current
    working directory, because a signing key is not a build artefact.
    """
    env = os.environ if env is None else env
    if Path("/data").is_dir():
        return Path("/data")
    base = env.get("XDG_STATE_HOME") or str(Path.home() / ".local" / "state")
    return Path(base) / "mcuhome-dashboard"


def is_loopback_host(host: str) -> bool:
    """True when *host* can only be reached from this machine."""
    name = host.strip().strip("[]")
    if name.lower() in {"localhost", "localhost.localdomain"}:
        return True
    try:
        return ipaddress.ip_address(name).is_loopback
    except ValueError:
        return False


def _normalized(url: str, *, scheme_map: dict[str, str], path: str) -> str:
    parts = urlsplit(url if "://" in url else f"http://{url}")
    scheme = scheme_map.get(parts.scheme, parts.scheme)
    base = parts.path.rstrip("/")
    return urlunsplit((scheme, parts.netloc, f"{base}{path}", "", ""))


def http_url(url: str, path: str = "") -> str:
    """The REST form of a configured build-server URL.

    A user configures one address and may write it in whichever of the
    four schemes they have in front of them, or none at all; both forms
    are derived from it rather than demanded of them. ADR 0012 decision
    3 keeps the transport, so this survives the job protocol that first
    needed it.
    """
    return _normalized(url, scheme_map={"ws": "http", "wss": "https"}, path=path)


def ws_url(url: str) -> str:
    """The WebSocket form of a configured build-server URL."""
    return _normalized(url, scheme_map={"http": "ws", "https": "wss"}, path="/ws")


@dataclass(frozen=True)
class Config:
    """Everything the server needs to know before it binds a socket."""

    #: Root of the MCUHome configuration tree (ADR 0008: the app's own
    #: config directory). ``None`` means "no tree configured yet" — the
    #: server starts and reports an empty device list rather than
    #: refusing, so a fresh install can be pointed at a tree from the UI.
    config_root: Path | None = None

    # --- public site (ADR 0009 decision 2) ---
    host: str = DEFAULT_HOST
    port: int = DEFAULT_PORT
    public_site: bool = True
    password: str | None = None

    # --- ingress site (ADR 0009 decision 1) ---
    #: ``None`` disables the ingress site. The Home Assistant app sets
    #: this; a standalone deployment never does.
    ingress_port: int | None = None

    # --- ingress admin resolution (ADR 0014) ---
    #: The add-on's ``SUPERVISOR_TOKEN``, used to ask the Supervisor's
    #: authenticated ``/auth/list`` which ingress users are administrators
    #: (:mod:`mcuhome_dashboard.admin`). ``None`` everywhere but a Home
    #: Assistant app; without it no ingress user resolves to an admin, so
    #: the admin-only verbs are refused — the fail-closed default of ADR
    #: 0014. It never reaches the wire (like the build-server token).
    supervisor_token: str | None = None
    #: Where the Supervisor answers. The in-cluster name a Home Assistant
    #: app resolves; overridable for a test or an unusual deployment.
    supervisor_url: str = "http://supervisor"

    # --- building (ADR 0003, ADR 0006 transport, ADR 0012, ADR 0013) ---
    #: Where a build server is and how to authenticate to it. ``None``
    #: means none is configured, which is not an error: only the
    #: ``remote`` method needs them, and it says so itself when they are
    #: missing. They become
    #: :attr:`mcuhome.workbench.api.BuildRequest.server` and ``.token``
    #: (ADR 0013 decision 2) — this is the dashboard's own surface, and
    #: the ``mcuhome`` command line's ``build-servers.toml`` is not read.
    build_server_url: str | None = None
    build_server_token: str | None = None

    #: Which build method runs (ADR 0013 decision 1). ``None`` expresses
    #: no preference and takes the builder's own default. Not validated
    #: here: :func:`mcuhome.workbench.api.resolve_method` owns the list of
    #: real names and its refusal names all of them, so a typo is
    #: answered by the package that knows rather than by a copy of the
    #: list that can go stale.
    build_method: str | None = None

    #: Parallel compile jobs handed to a build.
    build_jobs: int = DEFAULT_BUILD_JOBS

    #: Directories holding the hash-pinned MCUHome SDK package (firmware
    #: ADR 0018). Both container-shaped methods read them and for the same
    #: reason (firmware E65): the package's hash is an input of the build
    #: context's identity, so the pin is resolved on *this* machine
    #: whether a container here or a build server elsewhere fetches the
    #: bytes afterwards.
    sdk_sources: tuple[Path, ...] = ()

    #: ADR 0008: the App's private volume. Holds the signing key and the
    #: artifacts of every build this dashboard ran.
    data_dir: Path = field(default_factory=default_data_dir)

    # --- HTTP surface ---
    static_root: Path = DEFAULT_STATIC_ROOT
    allowed_origins: tuple[str, ...] = ()
    poll_interval: float = DEFAULT_POLL_INTERVAL
    log_level: str = "INFO"

    #: True when :func:`resolve_password` had to invent the password, so
    #: that startup can print it exactly once.
    password_generated: bool = field(default=False, compare=False)

    @property
    def auth_required(self) -> bool:
        """Whether the public site demands a password."""
        return self.password is not None

    @property
    def artifact_root(self) -> Path:
        """Where the artifacts of a build are kept: ``<device>/<build id>``.

        Under ``/data`` and therefore inside the App's backup volume —
        but ADR 0008 decision 5 excludes it from the backup set, because
        artifacts are large, reproducible and worthless in a restore.

        One directory per **build**, under the device's own, because a
        build record is addressed by build id and what it says about its
        artifacts has to be true of the files behind that id — sharing
        one directory per device made a cancelled build's URLs serve its
        predecessor's signed firmware, and made two builds of one device
        write into the same build context. Retention is what keeps the
        promise the per-device layout was after: a successful build
        removes the older directories of that device, so what is on disk
        is still the current image and not a dated pile (ADR 0013
        decision 5).
        """
        return self.data_dir / "builds"

    @property
    def build_server_configured(self) -> bool:
        """Whether a build server address is set. The token may be absent.

        A token is not part of being configured: firmware ADR 0019 lets a
        build server want no ``Authorization`` header at all, and
        :class:`…api.BuildRequest` permits ``None`` for exactly that. An
        address, on the other hand, is never invented — there is no
        discovery and no default (E53).
        """
        return bool(self.build_server_url)

    def site_summary(self) -> str:
        parts = []
        if self.public_site:
            auth = "password" if self.auth_required else "no auth (loopback only)"
            parts.append(f"public http://{self.host}:{self.port} ({auth})")
        if self.ingress_port is not None:
            parts.append(f"ingress :{self.ingress_port} (Home Assistant authenticates)")
        return ", ".join(parts) if parts else "no sites configured"


def resolve_password(
    configured: str | None,
    host: str,
    *,
    public_site: bool = True,
) -> tuple[str | None, bool]:
    """Apply ADR 0009 decision 2. Returns ``(password, was_generated)``.

    A generated password is returned, not logged — logging is the
    caller's job, because it must happen exactly once and at a level the
    operator actually sees.
    """
    if not public_site:
        return None, False
    if configured:
        return configured, False
    if is_loopback_host(host):
        return None, False
    return secrets.token_urlsafe(18), True


def resolve_build_server_token(
    configured: str | None,
    token_file: Path | None,
    *,
    env: Mapping[str, str] | None = None,
    pair_file: Path | None = None,
) -> tuple[str | None, bool]:
    """Find the build server's token. Returns ``(token, auto_paired)``.

    ADR 0006 decision 8, carried forward by ADR 0012 decision 3: when
    both Apps run on one Home Assistant instance they share ``/share``,
    the build server writes its token there, and the dashboard finds the
    pair without the user configuring anything. That is what makes ADR
    0003's always-remote decision invisible to the people it would
    otherwise annoy — so the pairing file is consulted *last*, after
    everything explicit, and never overrides a token somebody typed.

    ``pair_file`` defaults to :data:`DEFAULT_PAIR_FILE` at call time
    rather than in the signature, so that a test can point it somewhere
    that is not a real Home Assistant share.
    """
    environment = os.environ if env is None else env
    pair_file = DEFAULT_PAIR_FILE if pair_file is None else pair_file
    if configured:
        return configured, False
    from_env = environment.get(ENV_PREFIX + "BUILD_SERVER_TOKEN")
    if from_env and from_env.strip():
        return from_env.strip(), False

    candidates: list[Path] = []
    if token_file is not None:
        candidates.append(token_file)
    env_file = environment.get(ENV_PREFIX + "BUILD_SERVER_TOKEN_FILE")
    if env_file:
        candidates.append(Path(env_file))
    for path in candidates:
        try:
            token = path.read_text(encoding="utf-8").strip()
        except OSError:
            continue
        if token:
            return token, False

    try:
        paired = pair_file.read_text(encoding="utf-8").strip()
    except OSError:
        return None, False
    return (paired, True) if paired else (None, False)


def _env_flag(raw: str | None) -> bool | None:
    if raw is None:
        return None
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_int(env: Mapping[str, str], name: str) -> int | None:
    raw = env.get(ENV_PREFIX + name)
    if raw is None or not raw.strip():
        return None
    try:
        return int(raw)
    except ValueError:
        raise SystemExit(f"{ENV_PREFIX + name} must be a port number, not {raw!r}.") from None


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="mcuhome-dashboard",
        description="Web interface for MCUHome. Serves the UI and drives the builder; "
        "firmware is always compiled by a separate build server.",
    )
    parser.add_argument(
        "--config-root",
        type=Path,
        metavar="PATH",
        help="root of the MCUHome configuration tree (the directory holding devices/)",
    )
    parser.add_argument(
        "--host", metavar="ADDRESS", help=f"public bind address (default {DEFAULT_HOST})"
    )
    parser.add_argument(
        "--port", type=int, metavar="PORT", help=f"public port (default {DEFAULT_PORT})"
    )
    parser.add_argument(
        "--no-public-site",
        dest="public_site",
        action="store_false",
        default=None,
        help="serve the ingress site only (Home Assistant app deployment)",
    )
    parser.add_argument(
        "--ingress-port",
        type=int,
        metavar="PORT",
        help="serve a Home Assistant ingress site on this port (trusts the Supervisor gateway)",
    )
    parser.add_argument(
        "--password",
        metavar="PASSWORD",
        help="password for the public site; prefer the environment variable, since a "
        "command line is visible to every process on the machine",
    )
    parser.add_argument(
        "--build-method",
        metavar="METHOD",
        help=(
            "where firmware is compiled: local (a build container on this machine) "
            "or remote (a build server, needs --build-server-url). Default: whatever "
            "the installed mcuhome package defaults to. A method this installation "
            "cannot run — no container runtime — refuses with the exact install it "
            "is missing"
        ),
    )
    parser.add_argument(
        "--build-jobs",
        type=int,
        metavar="N",
        help=f"parallel compile jobs per build (default {DEFAULT_BUILD_JOBS}: this machine's)",
    )
    parser.add_argument(
        "--sdk-source",
        action="append",
        type=Path,
        metavar="PATH",
        dest="sdk_sources",
        help=(
            "directory holding the MCUHome SDK package (repeatable). The build "
            "context pins the package by version and hash, and that pin is resolved "
            "here — needed by the local and remote methods alike"
        ),
    )
    parser.add_argument(
        "--build-server-url",
        metavar="URL",
        help=(
            "address of the build server that compiles firmware for this dashboard "
            f"(for example {DEFAULT_BUILD_SERVER_URL}), used by --build-method remote. "
            "A build server learns the Matter commissioning passcode of every device "
            "it builds, because those credentials are compiled into the firmware — "
            "operate it as a trusted machine. The firmware signing key is never sent "
            "to it: the dashboard signs afterwards, where the key is"
        ),
    )
    parser.add_argument(
        "--build-server-token",
        metavar="TOKEN",
        help=(
            "bearer token for the build server; prefer the environment variable or "
            "--build-server-token-file, since a command line is visible to every "
            "process on the machine"
        ),
    )
    parser.add_argument(
        "--build-server-token-file",
        type=Path,
        metavar="PATH",
        help=(
            "read the build server's token from this file (two Apps on one Home "
            f"Assistant instance pair automatically through {DEFAULT_PAIR_FILE})"
        ),
    )
    parser.add_argument(
        "--data-dir",
        type=Path,
        metavar="PATH",
        help=(
            "private state directory: the firmware signing key and the artifacts of "
            "every build (default /data inside a Home Assistant App)"
        ),
    )
    parser.add_argument(
        "--static-root", type=Path, metavar="PATH", help="directory of built frontend assets"
    )
    parser.add_argument(
        "--allowed-origin",
        action="append",
        metavar="ORIGIN",
        dest="allowed_origins",
        help="additional accepted browser origin (repeatable)",
    )
    parser.add_argument(
        "--poll-interval",
        type=float,
        metavar="SECONDS",
        help=f"how often the configuration tree is re-checked (default {DEFAULT_POLL_INTERVAL})",
    )
    parser.add_argument(
        "--log-level",
        metavar="LEVEL",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="logging verbosity (default INFO)",
    )
    return parser


def load_config(
    argv: Sequence[str] | None = None,
    *,
    env: Mapping[str, str] | None = None,
) -> Config:
    """Build a :class:`Config` from the command line and the environment."""
    env = os.environ if env is None else env
    args = build_parser().parse_args(list(argv) if argv is not None else None)

    root = args.config_root
    if root is None and env.get(ENV_PREFIX + "CONFIG_ROOT"):
        root = Path(env[ENV_PREFIX + "CONFIG_ROOT"])

    static_root = args.static_root
    if static_root is None and env.get(ENV_PREFIX + "STATIC_ROOT"):
        static_root = Path(env[ENV_PREFIX + "STATIC_ROOT"])

    origins = list(args.allowed_origins or ())
    if env.get(ENV_PREFIX + "ALLOWED_ORIGINS"):
        origins += [
            item.strip() for item in env[ENV_PREFIX + "ALLOWED_ORIGINS"].split(",") if item.strip()
        ]

    public_site = args.public_site
    if public_site is None:
        public_site = _env_flag(env.get(ENV_PREFIX + "PUBLIC_SITE"))
    if public_site is None:
        public_site = True

    poll = args.poll_interval
    if poll is None and env.get(ENV_PREFIX + "POLL_INTERVAL"):
        poll = float(env[ENV_PREFIX + "POLL_INTERVAL"])

    data_dir = args.data_dir
    if data_dir is None and env.get(ENV_PREFIX + "DATA_DIR"):
        data_dir = Path(env[ENV_PREFIX + "DATA_DIR"])

    build_token, auto_paired = resolve_build_server_token(
        args.build_server_token, args.build_server_token_file, env=env
    )
    build_url = args.build_server_url or env.get(ENV_PREFIX + "BUILD_SERVER_URL")
    if build_url is None and auto_paired:
        # A token found in the shared pairing file means the build
        # server App is on this host; its port is not a thing the user
        # should have to know (ADR 0006 decision 8).
        build_url = DEFAULT_BUILD_SERVER_URL

    sdk_sources: list[Path] = [path.expanduser() for path in (args.sdk_sources or ())]
    if env.get(ENV_PREFIX + "SDK_SOURCE"):
        sdk_sources += split_paths(env[ENV_PREFIX + "SDK_SOURCE"])

    build_jobs = args.build_jobs or _env_int(env, "BUILD_JOBS") or DEFAULT_BUILD_JOBS
    if build_jobs < 1:
        raise SystemExit(f"--build-jobs must be at least 1, not {build_jobs}.")

    # `SUPERVISOR_TOKEN` is Home Assistant's own environment variable, not
    # a dashboard setting, so it is read unprefixed (ADR 0014). The URL is
    # a dashboard setting and keeps the prefix.
    supervisor_token = env.get("SUPERVISOR_TOKEN") or None
    supervisor_url = env.get(ENV_PREFIX + "SUPERVISOR_URL") or "http://supervisor"

    config = Config(
        config_root=root.expanduser().resolve() if root is not None else None,
        host=args.host or env.get(ENV_PREFIX + "HOST") or DEFAULT_HOST,
        port=args.port or _env_int(env, "PORT") or DEFAULT_PORT,
        public_site=public_site,
        ingress_port=args.ingress_port or _env_int(env, "INGRESS_PORT"),
        supervisor_token=supervisor_token,
        supervisor_url=supervisor_url,
        build_server_url=build_url or None,
        build_server_token=build_token,
        build_method=(args.build_method or env.get(ENV_PREFIX + "BUILD_METHOD") or None),
        build_jobs=build_jobs,
        sdk_sources=tuple(dict.fromkeys(sdk_sources)),
        data_dir=(data_dir.expanduser() if data_dir is not None else default_data_dir(env)),
        static_root=static_root or DEFAULT_STATIC_ROOT,
        allowed_origins=tuple(dict.fromkeys(origins)),
        poll_interval=poll if poll is not None else DEFAULT_POLL_INTERVAL,
        log_level=args.log_level or env.get(ENV_PREFIX + "LOG_LEVEL") or "INFO",
    )

    password, generated = resolve_password(
        args.password or env.get(ENV_PREFIX + "PASSWORD"),
        config.host,
        public_site=config.public_site,
    )
    config = replace(config, password=password, password_generated=generated)

    if not config.public_site and config.ingress_port is None:
        raise SystemExit(
            "--no-public-site leaves nothing to serve: add --ingress-port for a "
            "Home Assistant app, or drop --no-public-site."
        )
    return config
