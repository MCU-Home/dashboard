# 0016 — Build progress travels in the record, as facts

- Status: draft
- Date: 2026-08-16

## Context

Until now a running build offered a browser two things: a badge reading
"Building" and a growing wall of compiler output. Neither answers the
two questions a person actually has while one runs — *how far along is
it* and *where is this happening*. Fifteen silent minutes look the same
whether the build is linking or hung, and nothing said which SDK the
firmware was coming from.

The material was already there and unused. `BuildRequest.on_step` is the
workbench's honest-progress seam: the build says which step it entered,
and says it a second time once that step establishes something worth
stating — which SDK the context pinned, which image answered the Zephyr
requirement. The command line has rendered it since its output round
(cli ADR 0004). The dashboard passed `on_line` and left `on_step` at
`None`.

The rule the seam carries is worth restating, because everything below
follows from it: **a caller renders steps it was told about, never ones
it guessed.**

## Decision

**1. The steps live in the build record, not in an event of their own.**
`BuildRecord` gains `steps`, a list of `{key, state, facts}`, and every
change to it is published as the `build_changed` that already exists.

An event would have needed its own resume path. The record is what
`build/subscribe` and `build/status` answer with, so a browser that
opens halfway through a build — or reconnects after the bus dropped
events for it — finds the progress in the snapshot, with nothing
further to ask for. The log needed offsets because it is a stream of
thousands of lines; five steps are not a stream.

**2. The step list is a prediction, and the announcements are the
truth.** The list is stated when the build is accepted, all of it
`pending`, because "how far along" needs the steps still to come. It is
derived from the method: `local-dev` compiles in a west workspace and
builds no build context, so it claims no `context` step. A step that is
then announced without being on the list is inserted where it happened
rather than dropped — a workbench that grows a step shows it late
instead of not at all.

Two of the five keys are the workbench's vocabulary (`context`,
`compile`) and three are this dashboard's (`validate`, `artifacts`,
`sign`), for the work it does around the one `run_build` call.

**3. One verb, plus one for the step this side owns.** The seam only
ever says "this is where I am now" and everything before it is
finished, which is the rule the command line's step line derives from
too — two front ends over one seam must not disagree about what a build
did. `validate` needs a second verb (`finish`): the step after it is
the *builder's* to announce, and the builder's most ordinary refusal
here — no `mcuhome-compiler` installed, which is this dashboard's
deliberate default (ADR 0003) — happens before it announces anything.
Without it, a configuration that plainly resolved would be the step
marked failed, and the bar would point at the one thing that was not
wrong.

**4. Facts reach the browser through an allowlist, and as data.**
`builder.PUBLIC_FACTS` names the keys that may travel, per step, and a
value that is not plain JSON data is dropped.

An allowlist and not a filter of known-bad keys, because the vocabulary
belongs to another repository and is append-only by construction: a fact
a later workbench release adds would otherwise reach every open browser
tab without anyone deciding it may. One key is excluded today for
exactly that reason — the `compile` step carries `server` on the remote
method, and `server/info` publishes only *whether* a build server is
configured, never its address (ADR 0007). A progress update is not a way
around that. The value check is the other half: these dicts are
serialized into a WebSocket frame, and a fact that arrived as an object
would turn a progress update into a failed publish in the middle of a
build.

Facts are **data, never a sentence** — the same rule ADR 0015 decision 3
sets for the tree's problems, for the same reason: two sites, one event
bus, and the browser is where wording happens.

**5. The place a step runs comes from the method.** The browser labels
`compile` with "build container", "build server" or "local workspace"
from `record.method`, which is known before the build starts, and
`sign` with "dashboard", which is structural (ADR 0008). Never from a
fact that may or may not arrive.

**6. A build is not finished until its steps are, and after that the
seam is shut.** The record's terminal state is written in one place,
`BuildRegistry._finish`, and that place settles the steps first — it
takes the progress object, so a terminal path cannot be written without
one. Afterwards the seam refuses further announcements, the way the
output one already does.

Both halves answer the same question: `finished_state` is what a client
reads to stop watching, so the moment it turns true is the moment
everything else about the record has to be true as well. Without the
first half the state was written where the result was learned and the
steps were settled several statements and one `await` later, which put a
window on every build in which `build/status` answered `succeeded` next
to a step reading `running` — brief, but exactly the pair a step bar
exists to exclude. Without the second, the build a cancel abandons — and
a cancel deliberately abandons rather than stops it — kept announcing
onto a record that had ended, walking its bar forward through steps that
cancel had guaranteed did not happen; with the pump gone nothing
republished it, so a reconnecting browser was served a shape a connected
one was never told about, permanently.

The first half is why `close` takes the state rather than reading it: it
needs the *decided* state, to know whether the steps still open were
reached or missed, and the decided state is the argument `_finish` was
given.

## Consequences

The two front ends now render the same seam and stay comparable without
sharing code: the dashboard takes no dependency on the command line
(ADR 0015 decision 4), and the workbench never learns either exists.

`on_step` is called from whichever thread the method runs on — a worker
for the two synchronous methods, the event loop for `remote` — exactly
like `on_line`. So it mutates under a lock and the existing pump
publishes, output first, so that a step line cannot get ahead of the log
it is supposed to explain.

What this deliberately does **not** do is show the build report's memory
and flash figures, which the command line prints as tables after a
build. They come from a different source — the build report, not this
seam — and are left for later, to be picked up if they turn out to be
wanted (product owner, 2026-08-16).

The step vocabulary is append-only on both sides. A key the browser has
never heard of is rendered by its bare name, and a fact it does not
recognize is skipped — a line that describes a build must never be the
thing that stops it being rendered.
