# 0008 — State layout: config tree, signing key, retention, backups

- Status: accepted
- Date: 2026-08-07

## Context

A Home Assistant app gets its persistent state from two kinds of mount,
and they are mutually exclusive at the top level:

| Mapping | Path in the app | What it is |
|---|---|---|
| `addon_config` | `/addon_configs/<slug>`, mounted `/config` | the app's own configuration directory |
| `homeassistant_config` | `/homeassistant` | the user's entire Home Assistant configuration |
| (always present) | `/data` | the app's private volume, included in backups |

`builder-pipeline.md` §2 sketched the configuration tree at
`/config/mcuhome/`, and firmware ADR 0015 §8 sketched the signing key
next to it at `/config/mcuhome/signing.key`. Both sketches predate
knowing which mapping the app would take.

**The signing key is not an ordinary secret.** It is the trust anchor of
every device the user has bootstrapped: its public half is compiled into
each device's MCUboot, and rotating it is a bootloader replacement
requiring physical access to every device (firmware ADR 0015 §8, ADR
0016). Losing it is the one unrecoverable loss in this system.

**Artifact sets are not small and not precious.** Since firmware ADR
0015 a build produces a set per image — bootloader, signed application,
merged hex, UF2, OTA image, manifest, memory report — and the reference
host writes them to an SD card. They are also fully reproducible.

## Decision

### 1. The configuration tree lives in the app's own config directory

`addon_config` → `/addon_configs/mcuhome-dashboard`, mounted at
`/config` inside the app. The builder tree layout (`devices/`,
`shared/`, `components/`, `secrets.yaml`) sits directly at its root.

This is the least-privilege option, and the difference is not
theoretical: the dashboard cannot read or write the user's Home
Assistant configuration, so it can never be the tool that corrupts it.
The documented access path for users who want a text editor is Studio
Code Server, which reaches `/addon_configs/<slug>` — that instruction
belongs in the user documentation, because the directory is otherwise
not where people look.

### 2. The signing key is app-private, not in the tree

`/data/signing.key`, mode `0600`. Never in the configuration tree.

The reason is behavioural, not cryptographic: the configuration tree is
the thing users sync to git, copy between machines, paste into forum
posts and hand to someone helping them. A private key must not be one
`git add .` away from a public repository. This refines firmware ADR
0015 §8's `/config/mcuhome/signing.key` sketch — the custody rule (the
key lives where the controlling instance runs, never on a build server)
is unchanged; the location is stricter.

### 3. Key management is an explicit UI, with three operations

Product-owner requirement. A key this consequential must be visible and
manageable, not hidden in a volume.

- **Download** — the user's own backup, outside Home Assistant's.
- **Upload** — bring an existing key, or restore a downloaded one.
- **Regenerate** — create a new key pair.

Upload and regenerate carry a prominent warning, phrased in what it does
to the user rather than in cryptography: **a changed key orphans every
device already bootstrapped with the old one.** Those devices accept
only firmware signed by the key in their bootloader, so each of them
must be bootstrapped again (firmware ADR 0016), with physical access,
one at a time. Nothing in the dashboard can undo it.

### 4. Artifact retention on the build server: cap plus TTL

A per-device cap (keep the N most recent artifact sets) and a
time-to-live, both configurable, both on by default. Artifacts are
reproducible; keeping them forever buys nothing and writes an SD card to
death.

### 5. Backups

The configuration tree and `/data/signing.key` are **in** the Home
Assistant backup set. Build artifacts are `backup_exclude`d — large,
reproducible, and worthless in a restore.

## Consequences

- A restored backup restores a working installation *including the
  ability to sign updates for existing devices*. That is the property
  the backup set is chosen for.
- A user who moves to a fresh installation without a backup must
  re-bootstrap every device. The download operation exists so that this
  is a choice someone made, not an accident that happened to them.
- The dashboard never touches `/homeassistant`.
- Artifacts have a lifetime, so the stable artifact URLs ADR 0010
  requires must resolve to an honest "expired" — never to a stale or
  wrong file. Retention and addressing are designed together.
- The build server holds no long-lived user state beyond artifacts and
  job logs, which is what makes it disposable and re-creatable — and
  what makes ADR 0006's hosted-server outlook thinkable at all.
- Related standing decisions: ADR 0003, ADR 0006, ADR 0007, ADR 0009,
  ADR 0010; firmware ADR 0015 (§8) and ADR 0016;
  `builder-pipeline.md` §2.
