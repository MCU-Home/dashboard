<!--
SPDX-FileCopyrightText: 2026 The MCUHome Contributors
SPDX-License-Identifier: Apache-2.0
-->

# The API

The front end and the back end share exactly one contract: this API. The
front end holds no knowledge of how a project is stored, what the builder
calls things, or where a file lives; it asks for what a screen needs and
renders the answer. The back end holds no knowledge of screens.

The contract is written down in one place in the front end —
`frontend/app/src/commonMain/kotlin/org/mcuhome/ui/api/McuHomeApi.kt` —
as a Kotlin interface with the data model beside it. Two implementations
exist:

- **`MockApi`** (`…/api/mock/`), an in-memory project with sample devices
  and simulated builds. Every screen is developed and tested against it,
  and it needs no server.
- the **client**, which turns the same calls into messages on a WebSocket.
  It does not exist yet; the back end it talks to is being written.

This document describes the areas, the wire vocabulary each call maps to,
and what the mock does. It is the reference the back end is built against.

## Shape of the contract

- A request with an answer is a suspending function that returns the
  answer. Screens read as ordinary sequential code, and cancelling the
  screen cancels the request.
- Anything that arrives unasked — a build's progress, another window
  saving a file — is a stream (`Flow`).
- A failure is an error with a code, a message and, where one helps, a fix
  hint. The codes are `not_found`, `invalid`, `refused`, `not_available`
  and `internal`.
- Two answers are deliberately *not* failures and have types of their own:
  - a **write conflict**: the file moved on since it was read, so the
    write answers with the current revision and the current text instead
    of an error;
  - **not available**: the command exists, but nothing behind it can do
    the work yet (see "Capabilities that do not exist yet").

Every object crossing the boundary is a serializable data class, so the
mock and the client share the model unchanged.

## Naming

Commands are `<area>/<verb>`, lowercase, hyphens inside a part. The area
is a singular noun naming the object the command acts on; the verb names
the action. Events are `<noun>_<past participle>` in snake case and arrive
on a topic named after the plural noun. Routes exist only for what a
message cannot carry sensibly — file downloads — and are `/api/<area>/…`.

## Areas and commands

| Kotlin | Command | Answers |
|---|---|---|
| `ServerApi.info` | `server/info` | versions, the open project, and which capabilities exist |
| `DeviceApi.list` | `device/list` | every device with its config, build and signing state |
| `DeviceApi.get` | `device/get` | one device: its file, its diagnostics, its artifacts, its pairing state |
| `DeviceApi.save` | `device/save` | saved with a new revision, or a conflict |
| `DeviceApi.validate` | `device/validate` | every problem at once, as diagnostics |
| `DeviceApi.new` | `device/new` | a device folder with a starter configuration |
| `DeviceApi.rename` | `device/rename` | |
| `DeviceApi.delete` | `device/delete` | |
| `DeviceApi.clean` | `device/clean` | removes build output, of one device or of all |
| `DeviceApi.boards` | `device/boards` | the boards MCUHome builds for, planned ones included |
| `DeviceApi.model` | `device/model` | the canonical model the configuration resolves to |
| `BuildApi.start` | `build/start` | the first snapshot of a new build |
| `BuildApi.cancel` | `build/cancel` | |
| `BuildApi.status` | `build/status` | one build's snapshot |
| `BuildApi.stream` | `build/subscribe` → `build_changed`, `build_output_appended` | the build as it happens |
| `BuildApi.artifacts` | `build/artifacts` | the files a build produced |
| `BuildApi.download` | `build/download` → `GET /api/build/{build}/artifact/{path}` | where to fetch one file's bytes |
| `BuildApi.sign` | `build/sign` | signs the image of a finished build |
| `JobApi.list` | `job/list` | the jobs chip and its popover |
| `JobApi.cancel` | `build/cancel` | only a build can be stopped today |
| `JobApi.clearFinished` | `job/clear-finished` | |
| `SecretApi.scopes` | `secret/scopes` | which devices and build servers have a secrets file |
| `SecretApi.list` | `secret/list` | keys, masked values, used-by — never a value |
| `SecretApi.reveal` | `secret/reveal` | one value, on explicit request |
| `SecretApi.set` | `secret/set` | |
| `SecretApi.delete` | `secret/delete` | |
| `ConfigApi.list` | `config/list` | the files in `configs/` and who uses them |
| `ConfigApi.read` | `config/read` | one file with the secrets it refers to |
| `ConfigApi.write` | `config/write` | saved, or a conflict |
| `ConfigApi.new` | `config/new` | |
| `ConfigApi.validateUsers` | `config/validate-users` | one validation report per device that includes the file |
| `ProjectApi.options` | `project/options` | every option with its value and the layer that set it |
| `ProjectApi.setOption` | `project/set-option` | writes the project layer |
| `ProjectApi.unsetOption` | `project/unset-option` | drops back to the layer below |
| `ProjectApi.read` | `project/read` | the project file as text |
| `ProjectApi.write` | `project/write` | saved, or a conflict |
| `ProjectApi.doctor` | `project/doctor` | the environment report |
| `ProjectApi.publicKey` | `project/public-key` | the public half of the signing key |
| `PairingApi.get` | `device/matter-pairing` | a device's commissioning credentials |
| `PairingApi.draw` | `device/matter-pairing-new` | draws fresh ones; replacing needs a confirmation |
| `FlashApi.options` | `flash/options` | images and ports for the Flash dialog |
| `FlashApi.start` | `flash/start` | |
| `SetupApi.start` | `device/first-time-setup` | |
| `DeviceLogApi.open` | `log/subscribe` → `log_appended` | a running device's log |

Pairing has no area of its own: the credentials belong to a device, and
the command line spells the same operation `mcuhome device matter-pairing`.

## Events

| Event | Topic | Carries |
|---|---|---|
| `device_added`, `device_changed` | `devices` | the whole device row |
| `device_removed` | `devices` | the name |
| `build_changed` | `builds` | the whole build snapshot |
| `build_output_appended` | `builds` | new output lines, in order |
| `job_added`, `job_changed` | `jobs` | the whole job |
| `jobs_cleared` | `jobs` | the identifiers that were dropped |
| `config_changed` | `configs` | the file name |
| `events_dropped` | — | how many were lost |

Events carry the whole changed object rather than a patch, so a screen
that connects late renders the same thing as one that watched from the
start, and no client keeps a merge algorithm in step with the server's. A
client that sees `events_dropped` refetches what it is showing.

## Diagnostics

A validation report is a list of diagnostics plus an `ok` flag, which is
false exactly when at least one diagnostic is an error. Warnings alone
leave a configuration valid — that is the "1 warning" pill in the device
table.

Each diagnostic carries a severity, a message, the file it is about, a
one-based line and column, the configuration key, a fix hint and the
error's own kind. Line and column are absent for a problem with no place
to point at, such as a missing secrets file. The fields are the ones the
builder itself reports, with one addition: the severity, which the builder
does not have — it raises errors and reports warnings separately, and the
interface needs both in one list to draw them in the same gutter.

## Secrets

A secret list carries keys, a masked value and the used-by column, and
never a value. `secret/reveal` is the only call that returns secret
material, it returns exactly one key, and it exists so that showing a
password is a deliberate act with a request behind it.

The four scopes match the four places a project keeps secrets:
`secrets/main.yaml`, `secrets/devices/<device>.yaml`,
`secrets/build-server/<server>.yaml` and `secrets/firmware/`.

Matter pairing is the deliberate exception: the passcode travels in the
clear, because the manual pairing code and the QR payload beside it are
derived from exactly that number, so masking it while printing them would
protect nothing. The dialog masks it on screen behind a toggle — a
rendering decision, not a transport one.

## Capabilities that do not exist yet

Flashing, first-time setup and the device log are part of the vocabulary
from the start and answer "not available" with a reason until the builder
can perform them. `server/info` lists them, so a screen can say so before
the user presses the button rather than after.

## The mock

`MockApi` is a complete implementation over an in-memory project.

**What is in it.** Six devices with real configurations — one valid and
signed, one with two errors, one being built, one built but unsigned, one
with a warning, one never built; five shared configurations with their
users; secrets in all four scopes; the option registry with values from
the project, user, system and default layers; a board list including a
planned board with its reason; a doctor report; commissioning credentials
for the devices that have them.

**What it computes rather than stores.** Validation runs over the file's
text, so typing a `pin:` into the editor makes a warning disappear and the
pill in the device table changes with it. The checks are: a tab in the
indentation, a section the schema does not have, a missing `device` block,
a board that is unknown or only planned, a `!secret` nobody set, an
`!include` that points nowhere, and a GPIO peripheral without a pin. The
used-by columns of the Secrets and Configs screens are derived the same
way, from the `!secret` and `!include` references in the files.

**Builds.** A build walks five stages — generate, configure, compile with
a step count, link, sign — over about four seconds, streaming its stage
changes and its output, and can be cancelled between steps; a cancelled
build puts the device back as it was, a finished one produces artifacts
and marks the device built and signed. One build runs at a time.

**Determinism.** The mock never reads the system clock: every timestamp
comes from an injected clock that stands still unless a test moves it,
identifiers come from a counter, and the build is a fixed list of steps. A
speed factor divides every simulated wait, so the same build that takes
four seconds on a screen takes none at all in a test. Its tests live in
`frontend/app/src/commonTest/kotlin/org/mcuhome/ui/api/mock/`.

**Sample content.** Everything in the sample project is invented. There is
no real network name, no real address and no real key in it.
