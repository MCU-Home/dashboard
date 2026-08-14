# 0007 — What crosses the wire, and what the build server learns

- Status: accepted
- Date: 2026-08-07
- Finalized: 2026-08-14

## Context

This ADR fixes what the dashboard and a build server say to each other,
and — more importantly — what that costs. It was decided against
ADR 0006's job protocol; that protocol is history (superseded by the
session protocol of firmware ADR 0019, which the dashboard reaches
through `mcuhome.workbench.api.run_build` since ADR 0013), but the
payload decision survived the protocol change by design: firmware
ADR 0018 wraps the model into the build context and states that this
ADR's wire format is contained, not replaced.

Two payload shapes were available. **The config bundle**: the device
folder, the `shared/` fragments it references, `secrets.yaml` and the
schema, with resolution happening on the build server. **The resolved
model**: stages 1-3 run on the dashboard's side and only
`device-model.json` crosses. `builder-pipeline.md` §6 records the
outcome as a rule: the canonical model is the wire format for remote
builds.

The uncomfortable fact underneath the choice: **a Matter device's
commissioning credentials are compile-time Kconfig.** The firmware
repository's own invariant says it plainly —
`mcuhome/model/pairing.py::kconfig_lines()` emits all seven
`CONFIG_CHIP_DEVICE_*` symbols as one indivisible group, because CHIP
checks none of them against each other on Zephyr. They are drawn once,
into the user's configuration (`mcuhome init-pairing`; firmware
ADR 0016 decision 6, `yaml-schema.md` §4.1). They are part of the build input by
construction, so they are part of whatever crosses the wire — in either
payload shape.

The signing side has the same shape and the opposite conclusion.
MCUboot's **public** key is compiled into the bootloader image, so it
must reach the build. The **private** key must not: firmware ADR 0015
decision 8 fixes that it lives where the user's controlling instance
runs, never on a build server, and that signing is a detached `imgtool`
step over the finished binary.

Finally, `MODEL_VERSION` was 1 and marked provisional "until the first
dashboard consumption". This ADR was that consumption.

## Decision

### 1. The resolved `device-model.json` is the wire format

Stages 1-3 — load, validate, resolve — run on the dashboard's side of
the wire, in-process (ADR 0011; as built, `mcuhome.workbench`, whose
`BuildRequest.model` takes the canonical model with stages 1-3 already
run). Only the resolved canonical model crosses. `!secret` references
are already substituted; the build server needs neither the schema, nor
`secrets.yaml`, nor the config tree, nor any knowledge that a config
tree exists.

The envelope around the model changed with the protocol; the rule did
not. On a remote build the model travels inside the self-contained build
context of firmware ADR 0018, next to the board, the Zephyr line and the
SDK pin — still no schema, no secrets store, no tree.

### 2. Stated plainly: the build server sees the commissioning secrets

The model carries `PairingModel`, so **the build server learns each
device's Matter passcode, discriminator and SPAKE2 verifier**, and
learns any Wi-Fi or Thread material that is compiled in. Sending the
bundle instead of the model would not change this; nothing changes it
while credentials are compile-time Kconfig.

The consequence is an operating instruction, and it is documented at the
same volume as the feature it qualifies: **operate build servers as
trusted machines.** A build server is inside the trust boundary of every
device it builds — exactly like the machine that holds the signing key,
and for the same reason. This sentence belongs where a user configures a
build server, not in an appendix — the build-server repository's README
carries it up front.

Thread datasets keep the project's standing rule regardless: they live
in the user's configuration tree, are resolved into the model for a
build, and are never written into dashboard state and never into a log.

### 3. The public key crosses; the private key never does

The dashboard sends the signing **public** key with the build —
`BuildRequest.signing_pub`, the PEM that rides in the build context as
`keys/signing.pub`, which is all of the key pair a build ever sees — and
the build compiles it into MCUboot. Every build method returns the
**unsigned** application image (firmware draft ADR 0015 decision 8)
plus the `imgtool`
parameters (header size, alignment, slot size, version) in the build
report, and the dashboard signs locally, in-process over
`mcuhome.workbench.imgtool` (ADR 0013 decision 6). The signing key never
leaves the dashboard's own state (ADR 0008), and the invariant is
structural as well as procedural: `BuildRequest` has no field a private
key fits in, on any method.

The same detached step exists on the command line: `mcuhome build
--no-sign --public-key <file>` compiles the public half into MCUboot and
leaves the application unsigned, the build report carries the `imgtool`
parameters (`build-report.json` for a container build,
`build-manifest.json` for a west build), and `mcuhome sign <build dir>`
applies the signature wherever the private key is.

### 4. `model_version` is fixed at 1 now

The provisional period ends here; `mcuhome/model/model.py` states it —
"Version 1, and no longer provisional". Compatibility is negotiated,
never guessed: the dashboard sends exactly one version, and a mismatch
is a refusal that names both numbers, never a fallback
(`mcuhome.model.modelfile.read_model` is that refusal, on whatever
machine reads the model back). A `model_version` bump is a breaking
change to a published contract and is treated as one.

The negotiation channel moved with the protocol. Originally a build
server advertised its supported range in ADR 0006's `GET /capabilities`;
that endpoint retired with the job protocol, and no advertisement
replaced it because none is needed any more: the client resolves and
pins the SDK by sha256 (firmware E65), so the implementation that reads
the model on the far side is the one the sender chose, and a mismatch
means a stale file rather than two products disagreeing — still refused,
with both numbers named. The dashboard continues to declare what it
sends and what it can read (`versions.py`: `MODEL_VERSION`,
`MODEL_VERSION_MIN`/`MODEL_VERSION_MAX`, reported in `server/info`).

### 5. v1.0 direction: move identity out of the compile

A factory-data / settings partition holding per-device identity —
commissioning credentials now, the per-device DAC of firmware ADR 0012
path B later — written at flash time instead of compiled in. It is
listed here as a direction, not a design, because it is the same
conversation as firmware ADR 0012 path B and shares its key-custody
question; the two will want one answer.

What it unlocks, all of it out of reach for v0.1:

- the build server stops seeing credentials at all, which removes
  decision 2 rather than documenting it;
- devices that differ only in identity share one image, which is what
  makes **prebuilt images** possible;
- hosted build servers (the outlook ADR 0006 recorded) become
  defensible, because no user would be sending their device secrets to
  project infrastructure.

## Consequences

- The build server is stateless with respect to the user's
  configuration: it never learns the user's file names, never sees the
  secrets store, and knows only the one device it is currently building.
- Every build server an installation uses is inside the trust boundary
  of every device it builds. Written where the build server is chosen.
- Reproducibility is preserved: credentials come from the configuration,
  never from the build, so the same tree and the same MCUHome version
  still produce the same bytes anywhere (`builder-pipeline.md` §1.4).
- `MODEL_VERSION` and `tests_py/test_model_golden.py` changed role —
  from an internal regression guard to the test of a published contract.
- The dashboard's own signing step settled on MCUboot's `imgtool`,
  driven through `mcuhome.workbench.imgtool` — the same library
  `mcuhome sign` runs, so the bytes are the same bytes — rather than the
  own-signer-over-`p256.py` option firmware ADR 0015 §8 also sketched.
  The dashboard calls it in-process (`signing.py`, ADR 0013 decision 6);
  no subprocess, no console script.
- Related standing decisions: ADR 0008 (where the key lives), ADR 0011
  (in-process import), ADR 0012 (which carries ADR 0006's transport and
  threat-model decisions forward), ADR 0013 (the caller of all of this);
  firmware ADRs 0012, 0015, 0016, 0018 and 0019; `builder-pipeline.md`
  §6 and §7.
