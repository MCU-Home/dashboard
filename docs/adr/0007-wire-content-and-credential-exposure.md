# 0007 — What crosses the wire, and what the build server learns

- Status: accepted
- Date: 2026-08-07

## Context

ADR 0006 fixed how the dashboard and the build server talk. This fixes
what they say, and — more importantly — what that costs.

Two payload shapes were available. **The config bundle**: the device
folder, the `shared/` fragments it references, `secrets.yaml` and the
schema, with resolution happening on the build server. **The resolved
model**: stages 1-3 run in the dashboard and only `device-model.json`
crosses. `builder-pipeline.md` §2 already calls the canonical model "the
wire format for remote builds" and left "secrets transport
(send-with-bundle vs. server-side injection)" as the open sub-topic.

The uncomfortable fact underneath the choice: **a Matter device's
commissioning credentials are compile-time Kconfig.** The firmware
repository's own invariant says it plainly —
`mcuhome/pairing.py::kconfig_lines()` emits all seven
`CONFIG_CHIP_DEVICE_*` symbols as one indivisible group, because CHIP
checks none of them against each other on Zephyr. Firmware ADR 0016
decision 6 draws them once, into the user's configuration. They are part
of the build input by construction, so they are part of whatever crosses
the wire — in either payload shape.

The signing side has the same shape and the opposite conclusion.
MCUboot's **public** key is compiled into the bootloader image, so it
must reach the build server. The **private** key must not: firmware ADR
0015 decision 8 fixes that it lives where the user's controlling
instance runs, never on a build server, and that signing is a detached
`imgtool` step over the finished binary.

Finally, `MODEL_VERSION` is 1 and its docstring marks it provisional
"until the first dashboard consumption". This ADR is that consumption.

## Decision

### 1. The resolved `device-model.json` is the wire format

Stages 1-3 — load, validate, resolve — run in the dashboard,
in-process (ADR 0011). Only the resolved canonical model crosses.
`!secret` references are already substituted; the build server needs
neither the schema, nor `secrets.yaml`, nor the config tree, nor any
knowledge that a config tree exists.

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
build server, not in an appendix.

Thread datasets keep the project's standing rule regardless: they live
in the user's configuration tree, are resolved into the model for a
build, and are never written into dashboard state and never into a log.

### 3. The public key crosses; the private key never does

The dashboard sends the signing **public** key with the job; the build
server compiles it into MCUboot. The build server returns the
**unsigned** application image plus the `imgtool` parameters
(header size, alignment, slot size, version) in the build manifest, and
the dashboard signs locally. The signing key never leaves the dashboard's
own state (ADR 0008).

Detached signing is designed but not yet implemented in the builder —
today `imgtool` runs inside the build with the key mounted. The
amendment to firmware ADR 0015 §8 is a Block 0 work item (ADR 0011).

### 4. `model_version` is fixed at 1 now

The provisional period ends here. Compatibility is negotiated, not
assumed: the build server advertises a supported `model_version` range
in `/capabilities` (ADR 0006), the dashboard sends exactly one version,
and a mismatch is a refusal that names both numbers. A `model_version`
bump is a breaking change to a published contract and is treated as one.

### 5. v1.0 direction: move identity out of the compile

A factory-data / settings partition holding per-device identity —
commissioning credentials now, the per-device DAC of firmware ADR 0012
path B later — written at flash time instead of compiled in. It is
listed here as a direction, not a design, because it is the same
conversation as ADR 0012 path B and shares its key-custody question;
the two will want one answer.

What it unlocks, all of it out of reach for v0.1:

- the build server stops seeing credentials at all, which removes
  decision 2 rather than documenting it;
- devices that differ only in identity share one image, which is what
  makes **prebuilt images** possible;
- hosted build servers (ADR 0006's outlook) become defensible, because
  no user would be sending their device secrets to project
  infrastructure.

## Consequences

- The build server is stateless with respect to the user's
  configuration: it never learns file names, never sees the secrets
  store, and knows only the one device it is currently building.
- Every build server an installation uses is inside the trust boundary
  of every device it builds. Written where the build server is chosen.
- Reproducibility is preserved: credentials come from the configuration,
  never from the build, so the same tree and the same MCUHome version
  still produce the same bytes anywhere (`builder-pipeline.md` §1.4).
- `MODEL_VERSION` and `tests_py/test_model_golden.py` change role — from
  an internal regression guard to the test of a published contract. The
  provisional note in `mcuhome/model.py` is removed as part of Block 0.
- The dashboard gains a signing step of its own. Firmware ADR 0015 §8
  already sketches the two options (`imgtool`, which pulls
  `cryptography`, or our own signer over the existing `p256.py`); the
  choice is an implementation detail of Block 0, not of this ADR.
- Related standing decisions: ADR 0006, ADR 0008 (where the key lives),
  ADR 0011 (the manifest and the detached-signing work item); firmware
  ADRs 0012, 0015 and 0016; `builder-pipeline.md` §6 and §7.
