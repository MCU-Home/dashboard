# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Whether an ingress user is a Home Assistant administrator (ADR 0014).

The ingress site trusts its peer, not its headers: :mod:`security` proves
a request came from the Supervisor gateway before anything reads
``X-Remote-User-*``. Once that is proven, ``X-Remote-User-Name`` is the
**authenticated** username of the logged-in Home Assistant user — the
Supervisor strips any client-supplied value and re-injects it from the
ingress session, so a non-admin cannot forge one. See ADR 0014.

But a username is *identity*, not *authorization*. The Supervisor
forwards no "is this user an admin" flag, so the answer is fetched from
the one party that knows and that this add-on can authenticate to: the
Supervisor's own ``GET /auth/list``, called with the add-on's
``SUPERVISOR_TOKEN``. That endpoint returns, per user, ``is_owner`` and
``group_ids``; an administrator is an owner or a member of Home
Assistant's built-in admin group. The trusted header says *which* user;
the authenticated API says *whether that user is an admin* — a client
never gets to assert the second, which is the whole point.

**Fail closed.** An oracle that cannot answer — no token configured, the
Supervisor unreachable, the user not in the roster — returns ``None``,
which every caller treats as "not an administrator" (ADR 0014). A
deployment with no Supervisor token therefore grants no ingress user the
admin-only verbs, which is the safe default for a signal it cannot
verify.
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Protocol

import aiohttp

__all__ = [
    "HA_ADMIN_GROUP",
    "AdminOracle",
    "NoAdminOracle",
    "SupervisorAdminOracle",
    "build_admin_oracle",
    "user_is_admin",
]

logger = logging.getLogger(__name__)

#: Home Assistant's built-in administrators group id
#: (``homeassistant/auth/const.py``: ``GROUP_ID_ADMIN``). A user is an
#: administrator when they own the instance or belong to this group.
HA_ADMIN_GROUP = "system-admin"

#: How long a fetched roster is trusted before the Supervisor is asked
#: again. Long enough that a click storm is one request, short enough
#: that revoking a user's admin rights takes effect within the minute.
ROSTER_TTL = 30.0

#: The Supervisor is a loopback-speed peer, so a request that has not
#: answered by now is a request that has hung.
SUPERVISOR_TIMEOUT = 5.0


def user_is_admin(record: dict[str, Any]) -> bool:
    """Whether one ``/auth/list`` user record is an administrator.

    Owners always are; everyone else is an admin exactly when they are in
    :data:`HA_ADMIN_GROUP`. Any other shape — a missing field, a
    ``group_ids`` that is not a list — is read as "not an admin", because
    an unreadable record is not a licence.
    """
    if record.get("is_owner") is True:
        return True
    group_ids = record.get("group_ids")
    return isinstance(group_ids, list) and HA_ADMIN_GROUP in group_ids


class AdminOracle(Protocol):
    """Resolves a Home Assistant user to their administrator status."""

    async def is_admin(self, *, user_id: str | None, username: str | None) -> bool | None:
        """``True``/``False`` when known, ``None`` when it cannot be determined."""
        ...

    async def aclose(self) -> None:
        """Release anything held open (an HTTP session). Idempotent."""
        ...


class NoAdminOracle:
    """The fallback when nothing can answer: nobody is known to be an admin.

    Used whenever no Supervisor token is configured — a standalone
    deployment, or a test. Its ``None`` makes every ingress user
    non-admin, which is the fail-closed default of ADR 0014.
    """

    async def is_admin(self, *, user_id: str | None, username: str | None) -> bool | None:
        return None

    async def aclose(self) -> None:  # pragma: no cover - nothing to release
        return None


class SupervisorAdminOracle:
    """Resolves admin status via the Supervisor's ``GET /auth/list``.

    The roster is cached for :data:`ROSTER_TTL` behind a lock, so a burst
    of ingress requests is one Supervisor round-trip and two requests
    never fetch at once. A fetch that fails is **not** cached: it returns
    ``None`` for this request only, so a transient Supervisor hiccup
    refuses one mutation rather than locking admins out for the whole TTL.
    """

    def __init__(
        self,
        base_url: str,
        token: str,
        *,
        ttl: float = ROSTER_TTL,
        session: aiohttp.ClientSession | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._token = token
        self._ttl = ttl
        self._session = session
        self._owns_session = session is None
        self._admins: frozenset[str] | None = None
        self._fetched_at = 0.0
        self._lock = asyncio.Lock()

    async def is_admin(self, *, user_id: str | None, username: str | None) -> bool | None:
        # `/auth/list` keys users by username, and `X-Remote-User-Name`
        # carries exactly that (the Supervisor sets it from
        # `session_data.user.username`). Without one there is nothing to
        # match, which is not an admin.
        if not username:
            return None
        roster = await self._roster()
        if roster is None:
            return None
        return username in roster

    async def _roster(self) -> frozenset[str] | None:
        async with self._lock:
            now = time.monotonic()
            if self._admins is not None and now - self._fetched_at < self._ttl:
                return self._admins
            try:
                users = await self._fetch_users()
            except (aiohttp.ClientError, TimeoutError, ValueError) as exc:
                logger.warning("could not read the Home Assistant admin roster: %s", exc)
                return None
            admins = frozenset(
                user["username"]
                for user in users
                if isinstance(user.get("username"), str) and user_is_admin(user)
            )
            self._admins = admins
            self._fetched_at = now
            return admins

    async def _fetch_users(self) -> list[dict[str, Any]]:
        session = await self._ensure_session()
        headers = {"Authorization": f"Bearer {self._token}"}
        timeout = aiohttp.ClientTimeout(total=SUPERVISOR_TIMEOUT)
        async with session.get(
            f"{self._base_url}/auth/list", headers=headers, timeout=timeout
        ) as response:
            response.raise_for_status()
            payload = await response.json()
        return _users_of(payload)

    async def _ensure_session(self) -> aiohttp.ClientSession:
        if self._session is None:
            self._session = aiohttp.ClientSession()
            self._owns_session = True
        return self._session

    async def aclose(self) -> None:
        if self._session is not None and self._owns_session:
            await self._session.close()
        self._session = None


def _users_of(payload: Any) -> list[dict[str, Any]]:
    """The user list out of a Supervisor ``/auth/list`` body, defensively.

    The Supervisor wraps results as ``{"result": "ok", "data": {...}}``
    and the list lives under ``data.users``. Both the envelope and the
    key are unwrapped tolerantly so a shape change downgrades to "no
    admins found" (fail closed) rather than a crash.
    """
    data = payload.get("data", payload) if isinstance(payload, dict) else payload
    if isinstance(data, dict):
        users = data.get("users")
    elif isinstance(data, list):
        users = data
    else:
        users = None
    if not isinstance(users, list):
        raise ValueError("the /auth/list response carried no user list")
    return [user for user in users if isinstance(user, dict)]


def build_admin_oracle(*, supervisor_url: str, supervisor_token: str | None) -> AdminOracle:
    """The oracle a deployment gets: Supervisor-backed, or the fail-closed one."""
    if supervisor_token:
        return SupervisorAdminOracle(supervisor_url, supervisor_token)
    return NoAdminOracle()
