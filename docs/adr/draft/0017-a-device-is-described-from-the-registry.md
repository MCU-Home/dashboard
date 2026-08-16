# 0017 — A device is described from the registry, not from memory

- Status: draft
- Date: 2026-08-16

## Context

Until now the browser could edit a device and not start one. The list
said so — "new devices are created with `mcuhome new`" — which was the
honest surface while `mcuhome.workbench.api` had no entry point for it,
and a poor product: the dashboard is what a Home Assistant user is given,
and it could not do the first thing anybody wants to do with it.

Two more verbs were missing for the same reason. A device's commissioning
identity is drawn once and never again, by `mcuhome device
matter-pairing --new`; a freshly created device has none, so without that
verb the browser could create a device it could not then build. And the
list of boards MCUHome supports had no wire form at all.

The first question was what a "new device" form should *ask*. Writing a
`main.yaml` by hand means knowing the Zephyr board target verbatim, which
devicetree node the board's I2C is called, which compatible string names
the part, and which Matter cluster a temperature reading belongs in. All
four are already written down in the builder's registry, and none of them
is something a person should have to remember (product owner,
2026-08-16).

That exposed a gap: the registry knew boards, drivers, clusters and
device types — but not which **buses** a board breaks out.
`hardware.buses.<id>.controller` takes a devicetree node label, and the
only place `arduino_i2c` existed was an error hint and an example
comment. Enough for someone reading a message; not enough for a picker
offering the choice.

## Decision

**1. The gap is closed in the registry, not in this repository.**
`BoardDef.buses` (firmware repository) lists the buses a board breaks
out — kind, devicetree node label, and a description for a human choosing
between two of them — and `registry_data` exports it. A list of node
labels maintained here would be a second opinion about board bring-up,
which is exactly what AGENTS.md forbids and what would go stale the first
time a board lands.

It is a **catalogue and not a validation whitelist**: a board's
devicetree may carry labels MCUHome has never listed, and refusing one
would turn "what is known" into "what is allowed".

**2. `device/boards` is the catalogue, and it is not admin-gated.**
It answers with the registry's boards, drivers, clusters and device types,
each with the `planned_*` list beside it — "not yet, because …" is a
better answer than an absence, and it is the answer `mcuhome device
boards` already gives. It carries nothing about *this* deployment and
reads no project, so it is open, and it answers before a project is
resolved — which is what a form needs to populate itself.

It is a slice of `registry_data` rather than the whole of it: the full
export carries attribute sizes and per-board flash layouts, which an
editor's autocomplete will want and a board picker will not.

**3. The form is made of that answer, and knows no hardware itself.**
`mh-new-device` constrains its choices against the same data it renders:
a part is only offered a bus of the kind its driver speaks, and an
endpoint is only offered a reading whose quantity matches the cluster —
the constraint the builder states as "a cluster only accepts a peripheral
channel of the same quantity". That is what makes the form correct
rather than merely convenient, and it is why it will grow when the
registry does without anybody editing it. It is also why it looks thin
today: MCUHome supports one board and one part.

Buses are **derived, not asked for**. Somebody wiring a sensor picks the
connector, not the YAML key naming it, so each distinct controller
becomes one bus entry and the peripherals point at it.

**4. Nothing is checked twice.** A name that cannot become a hostname, a
board nobody brought up, a driver that is planned rather than supported,
a device that already exists — every one of those is the builder's
refusal, arriving as the diagnostics it produces with the fix hint the
command line prints. The backend judges the *shape* of the frame and
stops there. `conflict` is reserved for "there is already a device called
that", because that is the one refusal a client acts on differently: it
has somewhere to send the user.

A refused creation **writes nothing**. The builder checks the outline
before it touches the folder.

**5. The scaffold learned to write hardware, because guessing stopped.**
`render_starter` has always left hardware and endpoints as a commented
example, on the grounds that scaffolding a peripheral the user does not
own is worse than scaffolding none. That reasoning is about *guessing*,
and it stops applying the moment a form has walked somebody through the
registry. So `mcuhome.workbench.api` takes a `DeviceOutline`, and the
sections are written for real when one is given. Everything semantic —
a device type missing a mandatory cluster, an address out of range —
stays with stages 1-3, which can say it with a line and a column.

**6. Drawing the identity is a separate verb, and it returns no codes.**
`device/matter-pairing` writes `!secret` references into `main.yaml` and
the values into the device's own secrets file, and answers with
`replaced` and the path. The codes come from `device/commissioning` and
from nothing else.

The alternative — returning them from the draw, the way the command line
prints them — is one round trip cheaper on an already-open socket, and
turns "exactly one command carries passcodes" from a place into a
judgement ("…unless the user clearly asked"). A place is checkable and
`backend/tests/test_commissioning.py` checks it; a judgement erodes.

`force` is required to replace an existing identity, because every
controller that knows the device would have to commission it again. In
the browser that is a typed-out second step, not a checkbox.

**7. The draw button is offered whatever state the section is in.** A
device that was just created cannot resolve *because* it has no
credentials, so the case where the button matters most is the case where
the codes above it are an error message. Where it does not apply —
Matter switched off — the builder refuses in a sentence that says what to
write, which beats a button that is absent and explains nothing.

## Consequences

The dashboard can now take a user from an empty project to a device that
builds, without a terminal. What it still cannot do is manage the
*project* itself, which is deliberate (ADR 0015).

`mcuhome.workbench.api` grew: `new_device`, `render_starter`,
`init_pairing` and their types are part of the builder's SemVer promise
now, where before only the command line used them — by reaching into
implementation modules, which it may, being version-locked. The pairing
result is `PairingResult` there rather than `InitResult`, because
starting a project and drawing credentials are not the same operation to
read about in a traceback.

Two literals disappeared on the way: the validator's bus hint and the
scaffold's commented example both look their controller up in the board's
own registry row now, so a renamed node label follows them.

What is deliberately **not** here: a device cannot be deleted or renamed
from the browser, and the form does not offer optional configuration —
sampling periods, report deltas, measurement ranges, power source. Those
are edits, and the editor is one click away with the file already open.
