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
"""

from __future__ import annotations

import argparse
import ipaddress
import os
import secrets
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field, replace
from pathlib import Path

__all__ = [
    "DEFAULT_HOST",
    "DEFAULT_POLL_INTERVAL",
    "DEFAULT_PORT",
    "ENV_PREFIX",
    "Config",
    "build_parser",
    "is_loopback_host",
    "load_config",
    "resolve_password",
]

ENV_PREFIX = "MCUHOME_DASHBOARD_"

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


def is_loopback_host(host: str) -> bool:
    """True when *host* can only be reached from this machine."""
    name = host.strip().strip("[]")
    if name.lower() in {"localhost", "localhost.localdomain"}:
        return True
    try:
        return ipaddress.ip_address(name).is_loopback
    except ValueError:
        return False


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

    config = Config(
        config_root=root.expanduser().resolve() if root is not None else None,
        host=args.host or env.get(ENV_PREFIX + "HOST") or DEFAULT_HOST,
        port=args.port or _env_int(env, "PORT") or DEFAULT_PORT,
        public_site=public_site,
        ingress_port=args.ingress_port or _env_int(env, "INGRESS_PORT"),
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
