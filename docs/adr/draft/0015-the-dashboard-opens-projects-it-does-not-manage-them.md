# 0015 — The dashboard opens a project; it does not manage one

- Status: draft
- Date: 2026-08-16

## Context

Firmware ADR 0022 gave a project an identity of its own: a marker file
carrying a layout `version` and a `id` drawn once, a `mcuhome.yaml`
configuration layer, `devices/`, `secrets/`. Tools that meet a project
whose version they do not speak refuse it, and the command line grew
`project init`, `project info` and `project upgrade` to create and move
one between versions.

The dashboard predates all of it, and the question the CLI phase left
open was how much of that surface it should grow. The version check is
the sharp end: something has to happen when the project on disk is not
the version this dashboard speaks.

Two facts framed the answer.

**The dashboard is not the only tool a project has.** It is one of
several front ends over the same workbench, and the project on disk is
shared with the command line, an editor, git, and whatever the user
does. Nothing about a project belongs to the dashboard.

**How a project is created differs by deployment.** Standalone, a person
has a shell and can install the command line. In the Home Assistant App,
they may have neither — but the App's container does, and it can create
and upgrade the project when it starts, before the dashboard ever runs.

## Decision

**1. The dashboard is pointed at a project that already exists.** How
that project came to exist is the user's business — the command line,
a clone, a copy, an App container that created it. Creating and
upgrading projects is not the dashboard's job (product owner,
2026-08-16).

**2. Refusing to open a project is a property of the tree, not of each
command.** The project's version is checked where the rest of the tree
is scanned, and a project that cannot be opened is `available: false`
with **no devices listed**.

Only the marker's presence used to be checked. A project written by
older tools therefore listed its devices as though all were well, and
then refused every single thing done to one of them — validating,
building, saving. The interface looked functional and was not. A device
nobody may touch is not a device to offer.

**3. The reason travels as a code, never as a sentence.** `tree_state`
carries `problem: {code, …}` — `no_project`,
`project_upgrade_required`, `project_version_unsupported`,
`project_upgrading`, `project_file_unreadable`, plus the two version
numbers where they apply — and the browser does the wording.

This is not indirection for its own sake. Both sites publish onto one
event bus, and the sentence differs between them: standalone it names
the command line, under ingress it says the App should have handled this
already. A rendered sentence in the payload would be wrong for whichever
site did not produce it. The client knows which site answered it, from
`server/info`.

**4. The dashboard may point at the command line; it may not depend on
it.** Telling a user to install a separate program and run a command is
a sentence. What is forbidden is a package dependency on the CLI
distribution, or invoking it (product owner, 2026-08-16). The
standalone wording therefore names `mcuhome project upgrade`, and links
`t.mcuhome.org/dashboard/docs/project-upgrade/<major.minor>` for the
detail — the dashboard's own page, because the CLI's page about
upgrading a project is not the dashboard's page about a project it
cannot open.

## Consequences

The Home Assistant App has to create the project on first start and
upgrade it when it is old, before the dashboard serves anything. **That
container does not exist yet** — app packaging is a separate repository
that has not been created — so until it does, the ingress wording is
reached in practice and says the honest thing: this should not appear,
restart the App, check its log.

`project init` and `project upgrade` **in** the dashboard are wanted
later (product owner, 2026-08-16) and deliberately deferred: the whole
product has to stand up once before it is refined. When they arrive,
decision 1 is what changes, and decisions 2-4 survive it — a project
being upgraded is already one of the states this reports.

Configuration stays **read-only** here. The five layers of ADR 0022 are
a project's, and editing them is the same kind of act as upgrading it.
What the dashboard configures is its own deployment, through its own
flags.

Nothing about this makes the dashboard need the command line. A project
created by any means, at the version this dashboard speaks, opens
without either program knowing about the other.
