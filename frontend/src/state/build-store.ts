// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Builds, and the offsets that make a lost batch of output recoverable.
 *
 * The records themselves are snapshot-then-events like the device list
 * (ADR 0004 decision 3): `build/subscribe` answers with the current
 * state and every later change arrives as an event on the same socket.
 *
 * **The log is the part that needs a design.** Build output is the
 * traffic that makes a connection fall behind, and the server's event
 * bus answers that by dropping the oldest events for whoever fell — so a
 * client that appended every `build_output` it received in the order it
 * received them would eventually render a log that silently skipped a
 * few thousand lines. Every batch therefore names the line offset it
 * starts at, and this store compares that offset against the one it
 * holds:
 *
 * - **equal** — the ordinary case, append.
 * - **greater** — a HOLE. The batch is *not* appended (appending it
 *   would put a silent discontinuity in the middle of a log nobody can
 *   see), the end of the missing range is remembered, and
 *   {@link BuildStore.needsLog} says so until it is filled. The offset
 *   to ask `build/log` from is {@link BuildStore.gapFor}, which is
 *   simply what this store already holds.
 * - **smaller** — a replay or an overlapping batch. Only the tail past
 *   what is held is appended, so a line is never shown twice.
 *
 * {@link BuildStore.applyLog} merges the answer that closes a hole, and
 * {@link fillLogGaps} is that round trip as one function, because the
 * whole point of the offsets is that a client can get whole again on its
 * own.
 *
 * Retention is capped here as well as on the server: a Matter build
 * prints tens of thousands of lines and a browser tab left open across
 * several of them is not somewhere to keep all of them. What is dropped
 * is dropped from the front and {@link BuildStore.truncatedFor} says so,
 * which is what lets a view show "…earlier output was discarded" instead
 * of a log that quietly begins in the middle.
 */

import type { WsClient } from '../api/client';
import { buildLog } from '../api/commands';
import type { BuildLogResult, BuildRecord } from '../api/types';

export type BuildStoreListener = () => void;

/**
 * Lines kept per build, in this tab.
 *
 * Deliberately smaller than the backend's own cap: the server keeps a
 * build's log so that any client can resume from any offset, while a tab
 * only has to render what a person will scroll through. Asking for more
 * than this is one `build/log` away.
 */
export const MAX_LOG_LINES = 5000;

/** One shared empty array, so "no output" keeps a stable identity. */
const NO_LINES: readonly string[] = Object.freeze([]);

const FINISHED_STATES = new Set(['succeeded', 'failed', 'cancelled']);

/**
 * A batch of output, taken whole or not at all.
 *
 * One line that is not text would renumber every offset after it, and an
 * offset that is wrong is worse than a batch that is missing: the
 * missing one is recoverable and the wrong one is not.
 */
function isLineBatch(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((line) => typeof line === 'string');
}

interface LogState {
  /** Replaced rather than mutated, so a change is visible by identity. */
  lines: readonly string[];
  /** Offset of the oldest line still held here. */
  first: number;
  /** Offset the next line appended would get; also "how far we are". */
  next: number;
  /**
   * End offset of the newest batch that could not be applied in order.
   *
   * `gapEnd > next` *is* the hole: it says the stream has been seen to
   * reach that far while this store only reaches `next`. Taking the
   * newest end rather than the oldest matters — a second batch arriving
   * while the first `build/log` is in flight extends the range that has
   * to be filled, and an answer computed before it must not be allowed
   * to declare the hole closed.
   */
  gapEnd: number;
  /** True once lines were dropped at either end — here or on the server. */
  truncated: boolean;
}

export class BuildStore {
  #builds = new Map<string, BuildRecord>();
  #logs = new Map<string, LogState>();
  #running: string | null = null;
  #stale = false;
  #loaded = false;
  readonly #listeners = new Set<BuildStoreListener>();

  /** Every build, oldest first — the order the server lists them in. */
  get builds(): BuildRecord[] {
    return [...this.#builds.values()];
  }

  /** The build holding the one slot, if any (ADR 0013 decision 3). */
  get running(): string | null {
    return this.#running;
  }

  /** False until the first snapshot, so a view can tell empty from unknown. */
  get loaded(): boolean {
    return this.#loaded;
  }

  /** True when the server discarded events for this connection. */
  get stale(): boolean {
    return this.#stale;
  }

  get(id: string): BuildRecord | undefined {
    return this.#builds.get(id);
  }

  /**
   * The most recent build of one device.
   *
   * What a device page shows: there is exactly one build worth putting
   * in front of a user on a page about one device, and it is the last
   * one. Ties go to the record that arrived later, which after a
   * snapshot is the one the server listed last.
   */
  forDevice(name: string): BuildRecord | undefined {
    let latest: BuildRecord | undefined;
    for (const record of this.#builds.values()) {
      if (record.device !== name) continue;
      if (latest === undefined || record.started >= latest.started) latest = record;
    }
    return latest;
  }

  /** The output held for one build, in order. */
  linesFor(id: string): readonly string[] {
    return this.#logs.get(id)?.lines ?? NO_LINES;
  }

  /** The offset to ask `build/log` from — what this store already holds. */
  nextOffsetFor(id: string): number {
    return this.#logs.get(id)?.next ?? 0;
  }

  /** True once output was dropped, here or on the server. */
  truncatedFor(id: string): boolean {
    return this.#logs.get(id)?.truncated ?? false;
  }

  /**
   * Where to resume one build's log, or `null` when nothing is missing.
   *
   * Non-null means output was seen to go past what is held — either
   * because a batch skipped ahead or because a snapshot said the server
   * has more than this store received.
   */
  gapFor(id: string): number | null {
    const log = this.#logs.get(id);
    if (log === undefined || log.gapEnd <= log.next) return null;
    return log.next;
  }

  /** True while {@link gapFor} has an offset to resume from. */
  needsLog(id: string): boolean {
    return this.gapFor(id) !== null;
  }

  subscribe(listener: BuildStoreListener): () => void {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }

  /**
   * Replace the records with a fresh snapshot.
   *
   * Logs are **kept** for the builds that are still there, because they
   * are exactly what a reconnect must not throw away: the offsets held
   * are what makes the refetch a suffix instead of the whole log. What
   * the snapshot does say is how far the server has got, so a build
   * whose output ran on without this connection comes out of here
   * needing a log.
   */
  setSnapshot(builds: BuildRecord[], running: string | null): void {
    this.#builds = new Map(builds.map((record) => [record.id, record]));
    this.#running = running;
    this.#stale = false;
    this.#loaded = true;
    for (const id of [...this.#logs.keys()]) {
      if (!this.#builds.has(id)) this.#logs.delete(id);
    }
    for (const record of builds) {
      const log = this.#log(record.id);
      if (record.log_next_offset > log.gapEnd) log.gapEnd = record.log_next_offset;
    }
    this.#notify();
  }

  /** Forget everything — what a disconnect leaves behind. */
  reset(): void {
    this.#builds.clear();
    this.#logs.clear();
    this.#running = null;
    this.#stale = false;
    this.#loaded = false;
    this.#notify();
  }

  /**
   * Apply one event frame. Returns true when it changed anything.
   *
   * Unknown event names are ignored rather than refused, for the same
   * reason the device store ignores them: a newer backend publishing an
   * event this build has never heard of is not a reason to stop
   * rendering.
   */
  applyEvent(event: string, payload: Record<string, unknown>): boolean {
    switch (event) {
      case 'build_started':
      case 'build_changed': {
        const record = payload.build as BuildRecord | undefined;
        if (record === undefined || typeof record.id !== 'string') return false;
        this.#builds.set(record.id, record);
        this.#log(record.id);
        if (FINISHED_STATES.has(record.state)) {
          if (this.#running === record.id) this.#running = null;
        } else {
          this.#running = record.id;
        }
        this.#notify();
        return true;
      }
      case 'build_output': {
        const id = payload.build_id;
        const offset = payload.offset;
        const lines = payload.lines;
        if (typeof id !== 'string' || typeof offset !== 'number' || !isLineBatch(lines)) {
          return false;
        }
        if (!this.#output(id, offset, lines)) return false;
        this.#notify();
        return true;
      }
      case 'events_dropped': {
        this.#stale = true;
        this.#notify();
        return true;
      }
      default:
        return false;
    }
  }

  /**
   * Merge a `build/log` answer, which is how a hole is closed.
   *
   * An answer that starts *past* what is held cannot be joined onto it —
   * the server has dropped the lines that would bridge the two — so the
   * held piece goes and the log is marked truncated. That is the honest
   * shape: a view can say the beginning is missing, which it could not
   * do if the two pieces had been spliced together.
   */
  applyLog(result: BuildLogResult): boolean {
    const log = this.#log(result.build_id);
    let changed: boolean;

    if (result.offset > log.next) {
      // Through the same cap as every other path: this is the branch for
      // a log so long that the *server* already dropped lines from it,
      // so it is the last one that may hand a tab the backend's whole
      // retained log to render in one text node.
      log.next = result.offset + result.lines.length;
      this.#keep(log, result.lines);
      log.truncated = true;
      changed = true;
    } else {
      changed = this.#append(log, result.lines.slice(log.next - result.offset));
      if (result.truncated && !log.truncated) {
        log.truncated = true;
        changed = true;
      }
    }

    if (changed) this.#notify();
    return changed;
  }

  // -- internals ---------------------------------------------------

  #log(id: string): LogState {
    const existing = this.#logs.get(id);
    if (existing !== undefined) return existing;
    const created: LogState = { lines: NO_LINES, first: 0, next: 0, gapEnd: 0, truncated: false };
    this.#logs.set(id, created);
    return created;
  }

  /** One `build_output` batch against what is held. */
  #output(id: string, offset: number, lines: string[]): boolean {
    const log = this.#log(id);
    if (offset > log.next) {
      // A hole. Nothing is appended: the missing range is recorded so
      // that `build/log` can be asked for it from the offset held.
      const end = offset + lines.length;
      if (end <= log.gapEnd) return false;
      log.gapEnd = end;
      return true;
    }
    // Equal offsets append the whole batch; a smaller one is a replay
    // whose head this connection already has.
    return this.#append(log, lines.slice(log.next - offset));
  }

  #append(log: LogState, added: readonly string[]): boolean {
    if (added.length === 0) return false;
    log.next += added.length;
    this.#keep(log, [...log.lines, ...added]);
    return true;
  }

  /**
   * Hold at most {@link MAX_LOG_LINES} of *lines*, dropping from the front.
   *
   * The one site the cap lives at, so that a second way of filling a log
   * cannot quietly be a second retention policy. `first` is derived from
   * `next` rather than tracked separately — the two are the same fact
   * seen from opposite ends, and deriving it is what keeps them agreeing
   * whichever branch got here.
   */
  #keep(log: LogState, lines: readonly string[]): void {
    const dropped = lines.length - MAX_LOG_LINES;
    log.lines = dropped > 0 ? lines.slice(dropped) : [...lines];
    log.first = log.next - log.lines.length;
    if (dropped > 0) log.truncated = true;
  }

  #notify(): void {
    for (const listener of this.#listeners) listener();
  }
}

/**
 * Run *work*, and if it is asked for again while it runs, run it once more.
 *
 * A plain "already in flight, skip" guard is what a caller reaches for
 * here, and it is wrong in one case that matters: the second caller had
 * a *different* reason, and dropping it strands whatever that reason
 * was until something else happens to ask. Trailing instead of dropping
 * costs one extra round trip in the rare case and cannot loop, because
 * the work itself is a no-op when there is nothing to do.
 */
export function coalesced(work: () => Promise<unknown>): () => void {
  let running = false;
  let owed = false;

  const start = (): void => {
    if (running) {
      // Deferred, never dropped. This is the whole reason the wrapper
      // exists: what {@link fillLogGaps} works on is decided when it
      // starts, so a gap that appears while it runs is not in its
      // targets — and with no build running, no further event would ever
      // ask again. The gap would simply stay, invisible.
      owed = true;
      return;
    }
    running = true;
    owed = false;
    const finish = (): void => {
      running = false;
      if (owed) start();
    };
    try {
      void Promise.resolve(work()).then(finish, finish);
    } catch {
      finish();
    }
  };

  return start;
}

/**
 * Fetch whatever the store is missing of these builds' logs.
 *
 * The resume path, as one function: for each build that reports a gap,
 * ask `build/log` from the offset the store holds and merge the answer,
 * until nothing is missing any more. A fetch that brings the store no
 * further ends that build's turn rather than being repeated — an
 * unfillable gap is a reason to stop asking, not to ask in a loop.
 *
 * A command that failed simply ends the round: a `build/log` that did
 * not come back is a dropped connection nine times out of ten, and the
 * reconnect path asks again from the very same offset.
 */
export async function fillLogGaps(
  client: WsClient,
  store: BuildStore,
  ids: readonly string[],
): Promise<void> {
  const stalled = new Set<string>();
  for (;;) {
    const id = ids.find((candidate) => !stalled.has(candidate) && store.needsLog(candidate));
    if (id === undefined) return;
    const from = store.nextOffsetFor(id);
    let answer: BuildLogResult;
    try {
      answer = await buildLog(client, id, from);
    } catch {
      return;
    }
    store.applyLog(answer);
    if (store.nextOffsetFor(id) <= from) stalled.add(id);
  }
}
