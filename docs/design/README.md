# Design reference

This directory holds the design that the frontend implements: rendered
mockups of every screen and state, plus the notes below that describe the
structure, the states, the responsive rules and the token mapping the
mockups are built from. The screenshots use sample data; they are static
renders, not a running application.

## Application structure

- A light topbar (48 px, surface background, bottom border): the brand mark
  (light/dark variant per color scheme) and the wordmark "MCUHome" (Sora
  700), followed by the project name in a muted color.
- Navigation: Devices, Configs, Secrets, Project. The active item is ink
  colored, weight 600, with a 2 px accent underline.
- On the right: a jobs chip (accent tint background, a pulsing dot, a job
  count, opens a dropdown) and the connection state (a dot plus the word
  "connected").

## Screens and states

### Devices (entry screen)

Title and device count, a filter field, a segmented filter (All, Errors,
Not built, Unsigned), and a primary "New device" action. The table has a
sortable header:

- Name, with the friendly name shown below it
- Board (monospace)
- Config: a pill showing valid, or a count of errors/warnings
- Build: state, time and method; a running build shows a "building" pill
  with progress `n/m`
- Signed: a pill
- Network
- a row menu

### Jobs popover

Opened from the jobs chip. Lists the running job with its progress and a
cancel action, and finished or failed jobs with their time and a
jump-to-device action. A "Clear finished" action removes completed entries.
Builds keep running when the page changes; only one build runs at a time.

### New device

A dialog to create a device: name, friendly name, a board search over the
board registry (including boards marked "planned"), and a starter
configuration choice.

### Device detail

Breadcrumb "Devices / <name>" plus the board. Actions: Validate, Build (a
dropdown for the build method), Sign, Flash (a dropdown for recovery/USB or
Matter OTA), Pairing, and a "more" menu (first-time setup, clean, rename,
delete, resolved model).

Three columns:

1. **YAML editor** — line numbers, YAML syntax highlighting, inline
   diagnostics.
2. **Status rail** (260 px, directly right of the editor) with sections:
   Config (validation, includes, secrets resolved), Build (state, method,
   last good build, signed), Artifacts of the last good build (download),
   Matter pairing (credentials, masked discriminator, "Show QR code"), and
   a Diagnostics list. The rail collapses to a 44 px icon strip with status
   dots; collapsing is a manual toggle, but the rail also collapses
   automatically when the available width is short. Re-opening after an
   automatic collapse is manual.
3. **Output panel** — see below.

### Output panel

A dark surface in both color schemes. Tabs: Build, Diagnostics (with a
count), Device log, Model, Artifacts. The panel docks at the bottom (below
the editor column) or at the right (right of the status rail); the user
switches the dock side, and a draggable divider resizes it. The panel
minimizes to a 36 px status bar when docked at the bottom, or a 40 px
vertical strip when docked at the right; both still show progress and
counts while minimized.

- **Build tab**: a stage row (generate, configure, compile `n/m`, link,
  sign) with a progress bar, streamed build output, and a footer with the
  start time, the build method/image, the job, and a Cancel action.
- **Device log tab**: a live log with timestamp, level and module per line,
  and a footer showing the transport (serial port, baud rate), an
  autoscroll toggle, and Pause / Clear / Save actions.

### Shared configs

A file list (`configs/*.yaml`) annotated with "used by n devices", an
editor with an unsaved-changes pill, actions Validate users and Save, and a
rail showing the users of the file, its referenced secrets, and its path
and change state.

### Secrets

Four scopes, matching the CLI's secret files: Project (`secrets/main.yaml`),
Devices (`secrets/devices/<name>.yaml`), Build server
(`secrets/build-server/<name>.yaml`), and Firmware key (`secrets/firmware/`).
Each scope shows a table of key, masked value (revealed per row on request),
and used-by, with add/edit/delete actions. Secret values are not sent to the
browser unless a row is explicitly revealed.

### Project

An options table: option, value, and set-by (project, user or default), with
reset/override actions. Four tabs: Options, Edit as YAML, Boards, Doctor.

### Dialogs

- **Flash**: choose the image (signed or unsigned), the mode (Recovery over
  USB or Matter OTA), the port, and a first-time-setup warning where it
  applies.
- **Matter pairing**: QR code, manual pairing code, the full payload, the
  discriminator, a masked passcode, a "Draw new credentials…" action, and
  Print.

## Responsive behavior

The design is one UI with responsive rules, not a separate mobile UI; the
primary target is desktop at 1920×1080 or larger.

- **Tablet portrait**: the devices table drops the Signed and Network
  columns; the status rail is collapsed by default; the output panel opens
  at the bottom.
- **Tablet landscape**: the status rail is open; the output panel is
  minimized.
- **Phone**: navigation moves behind a menu button; the jobs chip shows
  only the count; the device list becomes rows with a status dot and one
  pill. The device page gets a back-header, a status pill strip instead of
  the rail, and a 44 px bottom action bar (Validate, Build, Flash); the
  output panel becomes a bottom sheet.
  - **Keyboard-open editing mode**: when the editor has focus, the header
    shrinks, the status pill strip, output panel and action bar hide, and a
    YAML toolbar (outdent, indent, `:`, `-`, `!secret`, undo, Done) sits
    directly above the on-screen keyboard.
  - **Landscape phone**: a 40 px header, the editor, and the toolbar.

## Dark mode

Dark mode follows the operating system's color scheme; there is no separate
toggle and no layout change between the two schemes. Every color is one of
the light/dark pairs listed below. The output panel is a dark surface in
both schemes.

## Tokens

Colors and typography come from the
[mcuhome-brand](https://github.com/mcu-home/mcuhome-brand) `style.css`; the
frontend theme uses these tokens directly rather than hardcoded colors.
Pairs below are given as light → dark.

| Token | Light | Dark |
|---|---|---|
| accent | `#d95513` | `#ef7b33` |
| accent-strong | `#a8420f` | `#f49a5e` |
| ink | `#221c18` | `#f4efe9` |
| muted | `#6f6659` | `#a89d90` |
| border | `#e8e1d6` | `#3d342c` |
| bg | `#fdfbf8` | `#1d1815` |
| surface | `#ffffff` | `#292219` |
| bg-alt | `#f4efe9` | `#231d17` |
| pin-gray | `#b0a89e` | `#8a8078` |
| success | `#3a7d44` | `#5cab6b` |
| success tint | `#e9f2ea` | — |
| success tint-border | `#c5dcc9` | — |
| success on-tint | `#2d6336` | `#5cab6b` |
| info | `#2e6ca4` | `#6aa5d8` |
| info tint | `#e7eff6` | — |
| info tint-border | `#c2d6e6` | — |
| info on-tint | `#26597f` | `#6aa5d8` |
| warning | `#b07d0a` | `#d99a1e` |
| warning tint | `#f7f0da` | — |
| warning tint-border | `#e2d3a8` | — |
| warning on-tint | `#8a6106` | `#d99a1e` |
| error | `#b3332b` | `#dd6a5f` |
| error tint | `#f7e9e7` | — |
| error tint-border | `#e3c2bd` | — |
| error on-tint | `#8f2822` | `#dd6a5f` |

Fonts: Sora 400/600/700 for the wordmark and headings, the system sans-serif
for body text, the system monospace for YAML and log content.

Editor syntax colors: keys use ink, values use info, `!secret`/`!include`
tags use warning, numbers and booleans use success, comments use muted.

Two values shown in the mockups have no brand token yet and are pending as
new tokens: the accent tint used for the jobs chip and the "building" pill,
and the editor's current-line highlight (`#fdf5ee` light / `#33291f` dark).

## Screenshots

| File | Shows |
|---|---|
| `01-devices.png` | Devices table, default state |
| `02-devices-jobs-open.png` | Devices screen with the jobs popover open |
| `03-new-device.png` | New device dialog |
| `04-device-panel-bottom.png` | Device detail, output panel docked at the bottom |
| `05-device-panel-right.png` | Device detail, output panel docked at the right |
| `06-device-rail-collapsed-panel-minimized.png` | Device detail, status rail collapsed, output panel minimized (bottom dock) |
| `07-device-rail-collapsed-panel-right-minimized.png` | Device detail, status rail collapsed, output panel minimized (right dock) |
| `08-flash-dialog.png` | Flash dialog |
| `09-pairing-dialog.png` | Matter pairing dialog |
| `10-shared-configs.png` | Shared configs screen |
| `11-secrets.png` | Secrets screen |
| `12-project.png` | Project screen |
| `20-tablet-portrait-devices.png` | Devices table, tablet portrait |
| `21-tablet-portrait-device.png` | Device detail, tablet portrait |
| `22-tablet-landscape-device.png` | Device detail, tablet landscape |
| `30-phone-devices.png` | Devices list, phone |
| `31-phone-device.png` | Device detail, phone |
| `32-phone-build-sheet.png` | Device detail, phone, output panel as a bottom sheet |
| `33-phone-editing-keyboard-open.png` | Device detail, phone, editor focused with keyboard open and YAML toolbar |
| `34-phone-landscape-device.png` | Device detail, phone landscape |
| `35-phone-landscape-editing-keyboard-open.png` | Device detail, phone landscape, editor focused with keyboard open and YAML toolbar |
| `40-dark-devices.png` | Devices table, dark mode |
| `41-dark-device-panel-bottom.png` | Device detail, dark mode, output panel docked at the bottom |
| `42-dark-flash-dialog.png` | Flash dialog, dark mode |
