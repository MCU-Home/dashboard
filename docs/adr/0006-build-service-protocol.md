# 0006 — The build-service protocol

- Status: accepted
- Date: 2026-08-07

## Context

ADR 0003 makes the remote build the only build. The protocol between the
dashboard and the build server is therefore on the critical path, not a
later nicety, and `builder-pipeline.md` §6 left exactly its two hardest
questions open: transport and authentication.

**There is a shipped precedent to learn from.** ESPHome released remote
builds in 2026.5/2026.6 with an offloader/receiver split — the headless
`--remote-build-only` receiver is structurally our build server. Their
choices, and the reasons they do not all transfer:

| Their choice | Ours | Why |
|---|---|---|
| Noise XX over plain-TCP WebSocket, port 6055 | WebSocket + bearer token | their receiver is a LAN peer found by mDNS; ours is routinely off-LAN |
| mDNS discovery with `pin_sha256` in TXT, OOB pin pairing | explicit URL + token; auto-pairing on the same host | same reason |
| frames: `submit_job`, `cancel_job`, `download_artifacts`, `queue_status`, `job_state_changed`, `job_output` | the same | this part is simply right |
| chunked gzip tarball artifacts | chunked transfer, per-artifact sha256 | ADR 0010 needs the hash |
| version provisioning per job | capability negotiation before the job | ADR 0011's version range |

Notable: ESPHome pivoted **away** from HTTPS + bearer to Noise during
their rewrite. That is the correct direction for a receiver that lives
on the same LAN as the offloader and is discovered by mDNS. It is the
wrong direction for a build server behind someone else's NAT, on a
university network, or reached through a corporate proxy — which is our
expected case, and already the real case in this project's own
development setup.

**One measurement decides the queue shape.** On a 16-core/32 GiB machine
a cold Zephyr+Matter build takes 1:23 at `-j12` and 1:12 at `-j24`
(measured 2026-08-07). Eleven seconds. The serial CMake/configure/link
phases dominate, so a build already owns the machine; running two at
once trades every job's latency for no throughput.

## Decision

### 1. Transport for v0.1: WebSocket with a bearer token

Over TLS wherever the deployment provides it. The product-owner
rationale, recorded because it is the argument that decides against the
precedent: **WebSocket over HTTPS traverses firewalls, NAT and reverse
proxies naturally**, and that is precisely the situation of a build
server operated fully outside the home network. A protocol that needs a
flat LAN and mDNS would work in the demo and fail in the deployment we
expect.

### 2. Transport is a swappable layer under a stable frame vocabulary

The frame vocabulary is the contract; the transport underneath it is
replaceable without touching either side's job logic. **Noise XX** (the
ESPHome shape, if we ever want mutual authentication without TLS
infrastructure) and **WebTransport** are the named candidates. Named —
not scheduled, not promised.

### 3. Frame vocabulary

`submit_job` · `cancel_job` · `job_state_changed` · `job_output` ·
`download_artifacts` · `queue_status`

Modeled on the shipped ESPHome vocabulary deliberately: it covers the
job lifecycle, it survives a client that disconnects mid-build, and
copying a vocabulary that already works in production is cheaper than
inventing a worse one.

### 4. `GET /capabilities` for negotiation before a job exists

A token-gated REST endpoint answering: MCUHome/builder versions, builder
image tag, supported `model_version` range (ADR 0007), architecture, and
number of job slots. The dashboard negotiates before it submits, so a
mismatch is a clear refusal naming both sides' versions — never a
silent fallback, never a failure ten minutes into a compile
(`builder-pipeline.md` §6).

### 5. Compile lane = 1, as a hard default

One compile at a time. Configurable upward for a machine whose operator
knows better, never raised silently.

### 6. Logs: per-job sidecar files, resumable follow

Build output goes to a per-job sidecar file on the build server. A
`follow` is **history-then-live with byte offsets**: a client states the
offset it has, receives everything after it, and is switched to the live
stream. A reconnecting browser resumes where it was instead of replaying
a 20-minute log or losing the gap.

### 7. Artifacts: chunked, hashed from day one

Artifacts transfer in chunks over the same connection. Every artifact
carries a **sha256 in the build manifest** from the first release. This
is not future-proofing for its own sake: ADR 0010's rung-3 flash tunnel
uses the hash as the anti-substitution anchor, and ADR 0007's signing
step verifies what it signs.

### 8. Same-host app pair: auto-pairing

When both apps run on one Home Assistant instance they share the
Supervisor network and a `/share/` mount. The build server writes its
token to a file under `/share/mcuhome/`; the dashboard finds the pair
and connects without the user configuring anything. A self-hosted build
server is the same protocol with a URL and a token entered by hand.

## Outlook — MCUHome-hosted public build servers (post-v1.0, not scope)

Recorded because it changes what "correct" means for decisions taken
now, and because the alternative is re-deriving it later.

ADR 0003's honest consequence is that a user with only a Raspberry Pi
needs a second machine. "Buy another computer" is a hostile first step
for a project whose competition is `pip install esphome`. Project-hosted
build servers are the obvious answer, and they are plausible for a
reason specific to our architecture: **a job is not generic CI**. It is
a fixed pipeline over a validated `device-model.json`, running
sandboxed, with no network access during the build and a bounded time
budget. There is no user-supplied code to execute — which is the thing
that makes free CI a magnet for abuse.

Candidate design, to be re-derived properly when it is built: anonymous
per-installation quota tokens, proof-of-work at token issuance, and
CDN-level DDoS shielding in front.

Two constraints on it, decided now:

- It **should** follow the factory-data direction of ADR 0007 first, so
  that users are not sending Matter commissioning passcodes to project
  infrastructure. Hosting builds while credentials are still compile-time
  Kconfig would make MCUHome the custodian of every hosted user's device
  secrets.
- Optional paid build quotas are named as a possible future funding
  source. Product-owner idea; no commitment, no design, no promise.

## Consequences

- One protocol document to maintain, and two products that version
  independently behind it. The negotiation in decision 4 is what makes
  that safe.
- Bearer tokens push transport security onto the deployment: a build
  server reachable from the internet **must** sit behind TLS, and the
  documentation says exactly that rather than assuming it.
- A leaked token is shell-equivalent on the build server. ESPHome states
  the same threat model verbatim for their offloader; ADR 0009 carries
  our version of the sentence into SECURITY.md.
- Auto-pairing means the common installation has no protocol
  configuration at all, which is also what makes the always-remote
  decision of ADR 0003 invisible to the people it would otherwise annoy.
- Related standing decisions: ADR 0003, ADR 0007 (what the frames
  carry), ADR 0009 (the threat model), ADR 0010 (hashes and flasher
  targets), ADR 0011 (version range, and the manifest that must exist);
  firmware ADR 0007; `builder-pipeline.md` §6 and §7.
