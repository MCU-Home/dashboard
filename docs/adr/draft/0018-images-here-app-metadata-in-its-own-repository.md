# 0018 — Images are built here; app metadata lives in its own repository

- Status: draft
- Date: 2026-08-16

## Context

Since the first commit this repository has carried a rule saying that
packaging does not belong in it: no `config.yaml`, no Dockerfile, both go
to a future packaging repository. It was written by copying the layout of
the nearest comparable project (ESPHome keeps `esphome/esphome` and
`esphome/home-assistant-addon` apart) and it was never argued.

ADR 0003 decision 4 then gave it an argument: there were to be **two**
Home Assistant Apps, a thin dashboard and a fat build server, and one
packaging place for the pair. On 2026-08-09 that pairing was superseded —
a build server is an orchestrator whose primary target is standalone, and
the App is one additional deployment among several. The rule was carried
forward in the supersession list anyway, without re-checking whether its
reason had survived. It had not.

Meanwhile the dashboard has been shippable for a while and is shipped
nowhere. Nothing in this repository produces an artefact a user can
install, which is the reason the product still starts with a git clone.

## Decision

**1. The images are built by the repository that holds the source.**
`docker/Dockerfile` builds two images from one description, and this
repository's CI publishes them to GHCR on a `v*` tag:

| target | image | for |
|---|---|---|
| `standalone` | `ghcr.io/mcu-home/ui` | `docker run` on a machine you own |
| `homeassistant` | `ghcr.io/mcu-home/ui-homeassistant-app` | the Home Assistant App |

The thing that knows how to build the program is the thing that builds
it, from the commit that produced the code inside it. A Dockerfile in a
second repository would be a second opinion about this one's dependencies.

**2. App metadata lives in `mcu-home/homeassistant-apps`.** That
repository holds `repository.yaml` and one directory per App —
`config.yaml`, documentation, translations — and nothing else. It builds
nothing; each App's `config.yaml` names the image published under
decision 1 and pins the tag.

This is what ADR 0003 decision 4 got right for a reason that no longer
holds and is right anyway for a different one: a Home Assistant *App
repository* is a source a user adds by URL, and one URL that offers every
MCUHome App beats one per product. What changes against that decision is
the split — packaging *metadata* there, the Dockerfile **here**.

**3. The App image is not built on Home Assistant's base images.** Those
exist for the s6-overlay service tree an App with several processes
needs. This App is one Python process: it takes Docker's default init and
reads `/data/options.json` with the interpreter it already has instead of
with bashio. Both images then run the same Python on the same libc, so a
wheel that resolves for one resolves for the other — which is worth more
here than the convention, because the runtime is a stack of unpublished
packages installed from git.

**4. The container prepares the project; the dashboard still does not.**
The dashboard opens a project and does not manage one (ADR 0015), and in
a Home Assistant App there is nobody at a terminal to run the command
line. So the App's entry point creates the project on first start and
migrates an outdated one before the server comes up — unattended, because
a user who updated the App has already agreed to the part of it they can
see, and Home Assistant's backup is the way back.

It asks what state the directory is in with `builder.project_problem`,
the same function that answers the browser. One judgement, not two that
can disagree.

It does not force. A directory holding files that are not a project — a
half-restored backup, a clone that lost its marker — is reported and left
alone, and the dashboard then shows the user what it found.

**5. Both images default to the `remote` build method.** The builder's
own default is `local`, which drives a build container through
`mcuhome-compiler` — not installed in either image, and required not to
be. `local` could therefore only ever refuse, naming an install these
images exist to refuse. `remote` refuses with the thing that is actually
missing, which is a build server's address.

The Dockerfile asserts the absence rather than promising it: a build step
fails if `mcuhome.compiler` is importable in the finished environment.
This is also why the images do not ship the command line, which would
pull the compiler in through its `local` extra.

**6. The App asks for the Supervisor's admin role.** ADR 0014's
consequence named `auth_api: true` for this. That key gates Home
Assistant's *password* endpoint; the user list the admin check actually
reads — `GET /auth/list` — is gated by the Supervisor's role table, where
nothing below `admin` matches it. So the App declares `hassio_api: true`
and `hassio_role: admin`, and its documentation says why in the place a
user will wonder about it.

Fail-closed is unchanged: without the answer, every ingress user is
read-only.

**7. Releasing is two steps, in one order.** Tag here, then set `version:`
in the App's `config.yaml` there. The Supervisor pulls the tag that key
names, so the reverse order offers users an update that cannot be pulled.
By hand for now; a workflow if it becomes a recurring nuisance rather
than an occasional one.

**8. amd64 only, for now.** The evidence gate of ADR 0003 decision 5 has
not moved. The workflow builds with a `platforms` list and the App
declares an `arch` list, so adding aarch64 is two words once somebody has
measured it.

## Consequences

- The rule changes rather than disappearing: no App metadata
  here, and the Dockerfile is here.
- CI gains a gate that builds both images without publishing them. It is
  the only thing that exercises installing the workbench from git,
  building the frontend and the compiler assertion — the other three
  gates run in a checkout, not in an image.
- `.dockerignore` is load-bearing, not hygiene: without it a host-built
  `node_modules` or `.venv` is copied into the image, which works
  perfectly until it is built on a different machine.
- The runtime pins in `docker/requirements.txt` are commit hashes,
  because the workbench and the model are not published. They are bumped
  when a release is cut, in the commit that moves the version.
- What a user gets today is a dashboard that edits, validates and creates
  devices, and refuses to build until a build server exists to point it
  at. That is the honest shape of the product right now, and it is
  visible in the App's log on first start rather than at the moment
  somebody presses build.
