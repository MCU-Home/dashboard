# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
#
# Two images out of one build, because they are the same program in two
# deployments and only the way they are started differs:
#
#   --target standalone      `docker run` on a machine you own
#   --target homeassistant   the Home Assistant App
#
# The Home Assistant image is NOT built on Home Assistant's own base
# images. Those exist for the s6-overlay service tree an app with several
# processes needs; this app is one Python process, so it takes Docker's
# default init (`init` is left at its default in the app's config.yaml)
# and reads its options with the Python it already has instead of with
# bashio. What it costs is the convention; what it buys is that both
# images run the same interpreter on the same libc, so a wheel that
# resolves for one resolves for the other.
#
# Build from the repository root:
#   docker build -f docker/Dockerfile --target standalone -t mcuhome-dashboard .

# ---------------------------------------------------------------- frontend
# Pinned to the *build* platform: the output is JavaScript, which is the
# same bytes for every target, and running a pnpm install plus a Vite
# build under emulation costs minutes per architecture for nothing.
FROM --platform=$BUILDPLATFORM node:22-slim AS frontend

# pnpm asks before it purges a modules directory and refuses to guess
# with no terminal attached. There is no terminal here, and the answer is
# always yes.
ENV CI=true

WORKDIR /src
# The lockfile first, so a source-only change does not reinstall the world.
# pnpm-workspace.yaml comes along because it carries this package's
# install-time settings, not a workspace: without it pnpm refuses the
# post-install script Vite's platform binary needs.
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
# The pnpm version comes from `packageManager` in package.json, so it is
# never stated twice. Installed with npm rather than through corepack:
# the corepack bundled with Node carries npm registry signing keys that
# have since been rotated, and dies on "Cannot find matching keyid"
# before it installs anything — the same reason CI does not use it.
RUN npm install --global "$(node -p 'require("./package.json").packageManager')"
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
# `tsc -b && vite build`: the type check is part of the build on purpose.
RUN pnpm build

# ------------------------------------------------------------------ python
FROM python:3.13-slim AS python-env

# git, because the workbench and the model are installed from git until
# they are published; it is not in the runtime images.
RUN apt-get update \
    && apt-get install --no-install-recommends --yes git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src

# One virtual environment, copied whole into the runtime images. It keeps
# the runtime free of pip, git and build tooling without a wheel-juggling
# stage in between.
RUN python -m venv /opt/mcuhome
ENV PATH="/opt/mcuhome/bin:${PATH}"

COPY docker/requirements.txt ./
RUN pip install --no-cache-dir --disable-pip-version-check -r requirements.txt

COPY backend/ ./backend/
RUN pip install --no-cache-dir --disable-pip-version-check ./backend

# The compiler must not be here, and this is the check rather than the
# claim: `mcuhome.compiler` present would mean the image can compile,
# which every other part of this system is built on it not being able to.
RUN python -c "import importlib.util, sys; sys.exit(0 if importlib.util.find_spec('mcuhome.compiler') is None else 'mcuhome-compiler is installed in an image that must never compile')"

# The built frontend is served from beside the environment rather than
# from inside the Python package: what the server serves is then a path
# this file chose, not a packaging accident.
COPY --from=frontend /src/dist /opt/mcuhome/share/dashboard/static

# ----------------------------------------------------------------- runtime
FROM python:3.13-slim AS runtime

COPY --from=python-env /opt/mcuhome /opt/mcuhome

# The build method is `remote` here rather than the builder's own default
# of `local`. `local` drives a build container through mcuhome-compiler,
# which is not installed and must not be, so it could only ever refuse —
# naming an install this image is built to refuse. `remote` refuses with
# the address that is actually missing.
ENV PATH="/opt/mcuhome/bin:${PATH}" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    MCUHOME_DASHBOARD_STATIC_ROOT=/opt/mcuhome/share/dashboard/static \
    MCUHOME_DASHBOARD_BUILD_METHOD=remote

LABEL org.opencontainers.image.source="https://github.com/mcu-home/dashboard" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.title="MCUHome Dashboard"

# -------------------------------------------------------------- standalone
FROM runtime AS standalone

# `docker run` reaches the container from outside it, so the public site
# binds to every interface — and that site wants a password (there is no
# Home Assistant here to say who is asking). Set
# MCUHOME_DASHBOARD_PASSWORD, or reach it over loopback only.
ENV MCUHOME_DASHBOARD_CONFIG_ROOT=/config \
    MCUHOME_DASHBOARD_DATA_DIR=/data \
    MCUHOME_DASHBOARD_HOST=0.0.0.0

VOLUME ["/config", "/data"]
EXPOSE 8099

LABEL org.opencontainers.image.description="MCUHome dashboard, standalone."

ENTRYPOINT ["mcuhome-dashboard"]

# ----------------------------------------------------------- home assistant
FROM runtime AS homeassistant

COPY docker/homeassistant-entrypoint.py /opt/mcuhome/share/dashboard/entrypoint.py

LABEL org.opencontainers.image.description="MCUHome dashboard, as a Home Assistant App."

ENTRYPOINT ["/opt/mcuhome/bin/python", "/opt/mcuhome/share/dashboard/entrypoint.py"]
