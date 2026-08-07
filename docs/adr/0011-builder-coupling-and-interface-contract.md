# 0011 — Builder coupling and the firmware-side interface contract

- Status: accepted
- Date: 2026-08-07

## Context

ADR 0002 fixed that the backend drives the builder natively, and
AGENTS.md carries two invariants that pull in opposite directions: the
dashboard is a standalone product with its own release cycle, and using
the builder CLI must never require the dashboard or any dashboard
version. "In-process import" and "independent release cycles" have to be
made compatible rather than chosen between.

**The builder has no machine-readable surface today.** Everything the
dashboard needs to be more than a text editor with a build button is
missing:

- `build-manifest.json` is designed in `builder-pipeline.md` §7 and not
  implemented;
- there is no `--json` mode and no `ConfigError.to_dict()`, although the
  error objects already carry file, line, column, key and hint
  (`mcuhome/errors.py`);
- there is no `mcuhome new <device>` scaffold command;
- the registry (boards, drivers, components, device types) and the YAML
  schema exist only as Python, not as exportable data;
- detached signing is designed (firmware ADR 0015 §8) but not built —
  `imgtool` runs inside the build today with the private key mounted,
  which ADR 0007 forbids for a remote build.

None of these is a dashboard task. All of them are firmware-repository
tasks that the dashboard cannot start without, which is why they are
recorded here, in the repository that is their consumer.

## Decision

### 1. In-process import

The dashboard imports the `mcuhome` package and calls it: load,
validate, resolve, error objects, registry. No subprocess, no CLI output
parsing, no exit-code interpretation. This is what makes a `ConfigError`
arrive in the editor's gutter as a marker with a fix hint instead of in
a log pane as a line of text (ADR 0004 decision 5).

### 2. Coupling is a declared version range, not a pin

The dashboard declares the `mcuhome` versions it supports
(`mcuhome>=X,<Y`) and refuses to start against anything outside the
range, naming both versions. The builder never depends on the dashboard
— **the direction of the dependency is the invariant**, and it is what
keeps AGENTS.md's "standalone release cycle" true while importing
in-process: the dashboard follows the builder's releases, the CLI never
learns that the dashboard exists.

### 3. Model compatibility is the handshake of ADR 0007

The dashboard sends `model_version` 1; the build server advertises its
supported range in `/capabilities`; a mismatch is a refusal, never a
fallback.

### 4. The interface contract — dashboard Block 0

Firmware-repository work items. They gate dashboard implementation
Blocks 1-3.

| Item | What it is | Consumer |
|---|---|---|
| `build-manifest.json` | designed in `builder-pipeline.md` §7, unimplemented: device model, versions, per-image sha256, `imgtool` parameters | ADR 0006 artifacts, ADR 0007 signing, ADR 0010 integrity |
| Structured errors | `ConfigError.to_dict()` plus a `--json` mode on the CLI | ADR 0004 editor diagnostics |
| `mcuhome new <device>` | scaffold a device folder from board + device type | the new-device wizard |
| Registry + JSON-Schema export | boards, drivers, components, device types as data; the YAML schema as JSON Schema | board/driver pickers; editor autocomplete and lint (ADR 0005) |
| Detached signing | firmware ADR 0015 §8 amendment: the build emits the unsigned image, the manifest carries the `imgtool` parameters, the bootloader build takes the public key as an input | ADR 0007, ADR 0008 |
| Per-board onboarding instructions | firmware ADR 0016's generated instructions, retrievable as registry data | ADR 0010 rung 1 |

### 5. Two further firmware/CI items feed this phase

Named here so they are scheduled rather than discovered.

**Pre-generated ZAP output.** CHIP supports it officially
(`scripts/codepregen.py`, `CHIP_CODEGEN_PREGEN_DIR`), and our `.zap` is
framework-owned and per-release (firmware ADR 0014), so pre-generating
is the intended use of the mechanism rather than a workaround. It
removes Node and Electron from the build path entirely: **−798 MB of
build-server image**, and the flakiest aarch64 dependency gone — which
is what makes ADR 0003's gated aarch64 build server winnable at all.

**CI-produced ccache packs**, per Zephyr line and board, published as
release assets. The first build on a fresh build server is its worst
moment: measured 13:38 cold against 2:02 warm on a 4-core developer
machine, and 1:12 against 0:21 on a 16-core one. A downloadable warm
cache turns a user's first experience of MCUHome from a coffee break
into a download. Product-owner-approved direction for the
horizontal-scaling phase.

## Consequences

- **Block 0 is a firmware-repository work block that gates dashboard
  Blocks 1-3.** It is recorded in this repository because this
  repository defines the requirement; it is executed in the other one.
- Every Block 0 item improves the plain CLI as well. `--json`,
  `mcuhome new`, the manifest and the schema export are all things a
  CLI-only user benefits from — the dashboard is the reason they get
  built, not the only beneficiary. That is what keeps AGENTS.md's
  "the CLI must never require the dashboard" honest in spirit and not
  only in packaging.
- The dashboard container carries the builder package (pure Python,
  small) and none of the toolchain (ADR 0003).
- The registry and schema export become a maintained contract: adding a
  board or a driver in the firmware repository changes what the
  dashboard offers, with no dashboard release. That is the payoff for
  the version-range rule in decision 2.
- Detached signing changes the builder's own artifact set, so it is
  worth doing together with the sysbuild migration rather than after it.
- Related standing decisions: ADR 0003, ADR 0004, ADR 0005, ADR 0006,
  ADR 0007; firmware ADR 0014 (framework-owned ZAP), ADR 0015 (§8),
  ADR 0016; `builder-pipeline.md` §6 and §7.
