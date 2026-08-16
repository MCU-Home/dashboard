# 0008 — State layout: config tree, signing key, retention, backups

- Status: draft
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

**Where artifacts come from changed underneath this ADR.** ADR 0012
extracted the build server into its own repository and product, and ADR
0013 made building a call into `mcuhome.workbench.api.run_build`: every
build method — a container here, a build server elsewhere, a west
workspace — delivers its artifacts *back to the dashboard*, unsigned,
inside the build session, and one host-side step signs them here. The
build server keeps a session for the duration of a build and nothing
afterwards. So the artifact state this ADR lays out is dashboard state,
in the App's private volume, and the build server appears below only to
say that it holds none of it.

## Decision

### 1. The configuration tree lives in the app's own config directory

`addon_config` → `/addon_configs/<id>_mcuhome-ui`, mounted at
`/config` inside the app. The builder tree layout (`devices/`,
`shared/`, `components/`, `secrets.yaml` — owned by the firmware
repository, `builder-pipeline.md` §2) sits directly at its root. The
mapping itself is declared by the App packaging, which lives in the
future packaging repo; the dashboard binary just takes the tree's
location (`--config-root` / `MCUHOME_DASHBOARD_CONFIG_ROOT`), which is
how a Docker or plain-Python deployment points it anywhere.

This is the least-privilege option, and the difference is not
theoretical: the dashboard cannot read or write the user's Home
Assistant configuration, so it can never be the tool that corrupts it.
The documented access path for users who want a text editor is Studio
Code Server, which reaches `/addon_configs/<slug>` — that instruction
belongs in the user documentation, because the directory is otherwise
not where people look.

A consequence the backend is built around: the dashboard is **not the
tree's only writer**. Studio Code Server, a git checkout and an editor
on a mounted share all change it behind the dashboard's back, so the
tree is a watched resource — and watched by *polling*, because the tree
lives on whatever the deployment mounts (a bind mount, an SD card, a
network share) and a poll works the same on all of them, which inotify
does not.

### 2. The signing key is app-private, not in the tree

`<data-dir>/signing.key`, mode `0600`. Never in the configuration tree.
In a Home Assistant App the data directory is `/data` — the private
volume — so the key sits at `/data/signing.key`; a standalone install
gets a state directory under the user's home (`XDG_STATE_HOME`) rather
than something in the current working directory, because a signing key
is not a build artefact. A deployment that mounts a key from elsewhere
says so with `MCUHOME_SIGNING_KEY`, which overrides the path.

The reason is behavioural, not cryptographic: the configuration tree is
the thing users sync to git, copy between machines, paste into forum
posts and hand to someone helping them. A private key must not be one
`git add .` away from a public repository. This refines firmware ADR
0015 §8's `/config/mcuhome/signing.key` sketch — the custody rule (the
key lives where the controlling instance runs, never on a build server)
is unchanged; the location is stricter. Since firmware E56 that custody
rule is structural on every build method: whatever compiles receives
only the PEM public half, delivers the image unsigned, and the
dashboard signs afterwards — `BuildRequest` has no field a private key
fits in.

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

The UI is still to be built. What already exists, and what the UI will
sit on top of, is the first-need behaviour: the key is **generated on
first need**, by the call that produces a build's public-key input —
refusing to build until the user has visited a key-management screen
would block the first success and teach nothing. A first generation is
never silent: it is logged at warning level and reported to the user in
the build record (`signing.created_key`), because a key that was just
generated is a key nobody has backed up yet. And the signing step
itself runs with `create=False`, so the *second* generation — the one
that orphans devices — cannot happen as a quiet side effect of anything;
it only happens through the regenerate operation, behind the warning
above. A key that vanishes between a build's start and its signature is
a loud, recoverable failed build, never a fresh key.

### 4. Artifact retention: one current image per device, here

Artifacts live in the App's private volume, at
`<data-dir>/builds/<device>/<build id>/` — one directory per **build**,
under the device's own, holding exactly the files the build method
declared and verified. Retention is the rule of ADR 0013 decision 5: a
build that succeeds *is* that device's current image, so completing it
removes the device's older build directories; a build that did not
succeed declared no artifacts and can serve nothing, so it removes its
own. What is on disk is one current, signed image per device — not a
configurable cap, not a dated pile. Artifacts are reproducible; keeping
them beyond the current image buys nothing and writes an SD card to
death.

The build server keeps **no artifacts at all**. A build session is
bounded by a hard TTL and an idle timeout (firmware ADR 0019), the
artifacts travel back to the dashboard inside it, and reaping the
session removes its working state. There is nothing on that side for a
retention policy to manage.

### 5. Backups

The configuration tree and `<data-dir>/signing.key` are **in** the Home
Assistant backup set. The build directories under `<data-dir>/builds/`
are `backup_exclude`d — large, reproducible, and worthless in a restore.
The exclusion is declared by the App packaging (the future packaging
repo), because backup composition is a packaging concern; this ADR fixes
*what* is excluded.

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
  wrong file. As built, a reclaimed build directory answers 404 like
  anything else that is not there, and no cancelled or failed build can
  leave a flashable lookalike behind. Retention and addressing are
  designed together.
- The App's private volume holds exactly two things — the signing key
  and the build directories — and everything a user would ever edit,
  sync or share lives in the configuration tree, apart from them.
- The build server holds no long-lived user state at all: its sessions
  are ephemeral by construction, which is what makes it disposable and
  re-creatable — and what keeps the hosted-server outlook (first
  sketched in ADR 0006, carried through ADR 0012's extraction)
  thinkable at all.
- Related standing decisions: ADR 0003, ADR 0007, ADR 0009,
  ADR 0010, ADR 0012, ADR 0013 (decisions 5 and 6); firmware ADR 0015
  (§8), ADR 0016 and ADR 0019; `builder-pipeline.md` §2.
