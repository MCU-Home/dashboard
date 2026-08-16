// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Build output, and the property the offsets exist to give it.
 *
 * The store has to be a *function of what it received* like the device
 * store is — but a log is a stream, and the server drops the oldest
 * events for a connection that falls behind. So the interesting
 * assertions are the three shapes a batch can have relative to what is
 * held: exactly next (append), past it (a hole, which must never be
 * papered over), and behind it (a replay, which must never be shown
 * twice).
 */

import { describe, expect, it, vi } from 'vitest';

import { WsClient } from '../src/api/client';
import type { BuildLogResult, BuildRecord } from '../src/api/types';
import { BuildStore, coalesced, fillLogGaps, MAX_LOG_LINES } from '../src/state/build-store';
import { flush, socketRecorder } from './helpers';

function build(overrides: Partial<BuildRecord> = {}): BuildRecord {
  return {
    id: 'b1',
    device: 'bench-node',
    method: 'local',
    state: 'running',
    started: 1000,
    finished: null,
    context_id: 'ctx',
    image: 'ghcr.io/mcu-home/builder:r6',
    status: '',
    steps: [],
    errors: [],
    artifacts: [],
    signing: null,
    ota: null,
    log_first_offset: 0,
    log_next_offset: 0,
    ...overrides,
  };
}

function logResult(overrides: Partial<BuildLogResult> = {}): BuildLogResult {
  return {
    build_id: 'b1',
    offset: 0,
    lines: [],
    next_offset: 0,
    first_offset: 0,
    truncated: false,
    state: 'running',
    ...overrides,
  };
}

describe('the snapshot', () => {
  it('is unknown before the first one arrives', () => {
    const store = new BuildStore();
    expect(store.loaded).toBe(false);
    expect(store.builds).toEqual([]);
    expect(store.running).toBeNull();
  });

  it('replaces the records and names the build holding the slot', () => {
    const store = new BuildStore();
    store.setSnapshot([build({ id: 'old', state: 'succeeded' }), build({ id: 'b2' })], 'b2');

    expect(store.builds.map((record) => record.id)).toEqual(['old', 'b2']);
    expect(store.running).toBe('b2');
    expect(store.loaded).toBe(true);
  });

  it('forgets the log of a build the server no longer lists', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one'] });
    store.setSnapshot([build({ id: 'b2' })], 'b2');

    expect(store.linesFor('b1')).toEqual([]);
    expect(store.nextOffsetFor('b1')).toBe(0);
  });

  it('keeps the offset it holds, so a reconnect refetches a suffix', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one', 'two'] });

    // The build ran on while this connection was away.
    store.setSnapshot([build({ log_next_offset: 9 })], 'b1');

    expect(store.linesFor('b1')).toEqual(['one', 'two']);
    expect(store.needsLog('b1')).toBe(true);
    expect(store.gapFor('b1')).toBe(2);
  });

  it('needs no log when the server got no further than this connection', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one', 'two'] });
    store.setSnapshot([build({ log_next_offset: 2 })], 'b1');

    expect(store.needsLog('b1')).toBe(false);
  });
});

describe('records', () => {
  it('adds a started build and follows it to its end', () => {
    const store = new BuildStore();
    store.setSnapshot([], null);

    expect(store.applyEvent('build_started', { build: build() })).toBe(true);
    expect(store.running).toBe('b1');
    expect(store.get('b1')?.state).toBe('running');

    store.applyEvent('build_changed', { build: build({ state: 'succeeded', finished: 1100 }) });
    expect(store.get('b1')?.state).toBe('succeeded');
    expect(store.running).toBeNull();
  });

  it('ignores a malformed payload rather than storing a build without an id', () => {
    const store = new BuildStore();
    store.setSnapshot([], null);
    expect(store.applyEvent('build_started', { build: { device: 'x' } })).toBe(false);
    expect(store.builds).toHaveLength(0);
  });

  it('shows a device its most recent build', () => {
    const store = new BuildStore();
    store.setSnapshot(
      [
        build({ id: 'old', started: 10, state: 'failed' }),
        build({ id: 'other', device: 'lamp', started: 50 }),
        build({ id: 'new', started: 20, state: 'succeeded' }),
      ],
      null,
    );

    expect(store.forDevice('bench-node')?.id).toBe('new');
    expect(store.forDevice('lamp')?.id).toBe('other');
    expect(store.forDevice('nobody')).toBeUndefined();
  });
});

describe('output arriving in order', () => {
  it('appends batch after batch and tracks the next offset', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');

    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one', 'two'] });
    store.applyEvent('build_output', { build_id: 'b1', offset: 2, lines: ['three'] });

    expect(store.linesFor('b1')).toEqual(['one', 'two', 'three']);
    expect(store.nextOffsetFor('b1')).toBe(3);
    expect(store.needsLog('b1')).toBe(false);
  });

  it('replaces the array, so a view sees the change by identity', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one'] });
    const first = store.linesFor('b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 1, lines: ['two'] });

    expect(store.linesFor('b1')).not.toBe(first);
  });

  it('refuses a batch that is not all text, which would renumber the rest', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    expect(store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['a', 7] })).toBe(
      false,
    );
    expect(store.linesFor('b1')).toEqual([]);
  });
});

describe('a hole', () => {
  it('is recorded rather than silently appended', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one', 'two'] });

    // The bus dropped whatever covered offsets 2..6 for this connection.
    expect(store.applyEvent('build_output', { build_id: 'b1', offset: 7, lines: ['eight'] })).toBe(
      true,
    );

    // Nothing was appended out of order, and the store says what to ask for.
    expect(store.linesFor('b1')).toEqual(['one', 'two']);
    expect(store.needsLog('b1')).toBe(true);
    expect(store.gapFor('b1')).toBe(2);
  });

  it('grows with a second batch that arrives while the hole is open', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one'] });
    store.applyEvent('build_output', { build_id: 'b1', offset: 5, lines: ['six'] });

    // An answer that only reaches offset 6 must not close the hole: the
    // batch at 6 was seen and is still missing.
    store.applyEvent('build_output', { build_id: 'b1', offset: 6, lines: ['seven'] });
    store.applyLog(
      logResult({ offset: 1, lines: ['two', 'three', 'four', 'five', 'six'], next_offset: 6 }),
    );

    expect(store.linesFor('b1')).toEqual(['one', 'two', 'three', 'four', 'five', 'six']);
    expect(store.needsLog('b1')).toBe(true);
    expect(store.gapFor('b1')).toBe(6);
  });

  it('is closed by the answer that reaches past it', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one'] });
    store.applyEvent('build_output', { build_id: 'b1', offset: 3, lines: ['four'] });

    expect(store.applyLog(logResult({ offset: 1, lines: ['two', 'three', 'four'] }))).toBe(true);

    expect(store.linesFor('b1')).toEqual(['one', 'two', 'three', 'four']);
    expect(store.needsLog('b1')).toBe(false);
    expect(store.nextOffsetFor('b1')).toBe(4);
  });
});

describe('an overlapping batch', () => {
  it('contributes only its tail, so no line is shown twice', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one', 'two'] });

    store.applyEvent('build_output', { build_id: 'b1', offset: 1, lines: ['two', 'three'] });

    expect(store.linesFor('b1')).toEqual(['one', 'two', 'three']);
    expect(store.nextOffsetFor('b1')).toBe(3);
  });

  it('changes nothing at all when it is entirely old', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one', 'two'] });

    expect(
      store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one', 'two'] }),
    ).toBe(false);
    expect(store.linesFor('b1')).toEqual(['one', 'two']);
  });

  it('is deduplicated by a `build/log` answer as well', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one', 'two'] });

    store.applyLog(logResult({ offset: 0, lines: ['one', 'two', 'three'], next_offset: 3 }));

    expect(store.linesFor('b1')).toEqual(['one', 'two', 'three']);
  });
});

describe('truncation', () => {
  it('says so when the server could not reach back far enough', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one'] });
    store.applyEvent('build_output', { build_id: 'b1', offset: 900, lines: ['nine hundred'] });

    // The bridging lines are gone from the server too: the answer starts
    // past what is held, so the two pieces cannot be joined.
    store.applyLog(
      logResult({ offset: 800, lines: ['eight hundred'], next_offset: 801, truncated: true }),
    );

    expect(store.truncatedFor('b1')).toBe(true);
    expect(store.linesFor('b1')).toEqual(['eight hundred']);
    expect(store.nextOffsetFor('b1')).toBe(801);
  });

  it('caps a resumed log too, which is the case the cap was written for', () => {
    // The branch that replaces what is held runs exactly when the server
    // already dropped lines — i.e. on a log long enough that this cap is
    // the only thing between a tab and the backend's whole 20 000.
    const store = new BuildStore();
    store.setSnapshot([build({ log_next_offset: 30_000 })], 'b1');
    const lines = Array.from({ length: MAX_LOG_LINES + 40 }, (_unused, index) => `line ${index}`);

    store.applyLog(
      logResult({
        offset: 10_000,
        lines,
        next_offset: 10_000 + lines.length,
        truncated: true,
      }),
    );

    expect(store.linesFor('b1')).toHaveLength(MAX_LOG_LINES);
    // Dropped from the front, and the offsets still describe what is held.
    expect(store.linesFor('b1')[0]).toBe('line 40');
    expect(store.nextOffsetFor('b1')).toBe(10_000 + lines.length);
    expect(store.truncatedFor('b1')).toBe(true);
  });

  it('caps what one tab retains, dropping from the front', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    const lines = Array.from({ length: MAX_LOG_LINES + 10 }, (_unused, index) => `line ${index}`);
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines });

    expect(store.linesFor('b1')).toHaveLength(MAX_LOG_LINES);
    expect(store.linesFor('b1')[0]).toBe('line 10');
    expect(store.nextOffsetFor('b1')).toBe(MAX_LOG_LINES + 10);
    expect(store.truncatedFor('b1')).toBe(true);
  });
});

describe('events', () => {
  it('marks itself stale when the server discarded events for this connection', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    expect(store.stale).toBe(false);

    expect(store.applyEvent('events_dropped', { count: 4, total: 4 })).toBe(true);
    expect(store.stale).toBe(true);

    store.setSnapshot([build()], 'b1');
    expect(store.stale).toBe(false);
  });

  it('ignores an event this build has never heard of', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    expect(store.applyEvent('flash_progress', { percent: 12 })).toBe(false);
  });

  it('notifies its listeners and stops when unsubscribed', () => {
    const store = new BuildStore();
    const listener = vi.fn();
    const stop = store.subscribe(listener);

    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one'] });
    expect(listener).toHaveBeenCalledTimes(2);

    stop();
    store.applyEvent('build_output', { build_id: 'b1', offset: 1, lines: ['two'] });
    expect(listener).toHaveBeenCalledTimes(2);
  });
});

describe('reset', () => {
  it('goes back to knowing nothing, which is what a disconnect leaves', () => {
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one'] });
    store.reset();

    expect(store.loaded).toBe(false);
    expect(store.builds).toEqual([]);
    expect(store.running).toBeNull();
    expect(store.linesFor('b1')).toEqual([]);
  });
});

describe('the resume path', () => {
  /** A client whose socket is a test double, already open. */
  function connected() {
    const recorder = socketRecorder();
    const client = new WsClient({
      url: () => 'ws://test/ws',
      socketFactory: recorder.factory,
      probe: () => Promise.resolve(false),
    });
    client.connect();
    recorder.sockets[0]!.open();
    return { client, socket: recorder.sockets[0]! };
  }

  it('asks `build/log` from the offset held and comes back whole', async () => {
    const { client, socket } = connected();
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');
    store.applyEvent('build_output', { build_id: 'b1', offset: 0, lines: ['one', 'two'] });
    // Offsets 2..4 never arrived on this connection.
    store.applyEvent('build_output', { build_id: 'b1', offset: 5, lines: ['six'] });

    const filling = fillLogGaps(client, store, ['b1']);
    await flush();

    const command = socket.commands.at(-1);
    expect(command?.type).toBe('build/log');
    expect(command?.payload).toEqual({ build_id: 'b1', from_offset: 2 });

    socket.receive({
      id: command!.id,
      type: 'result',
      payload: logResult({
        offset: 2,
        lines: ['three', 'four', 'five', 'six'],
        next_offset: 6,
      }),
    });
    await filling;

    expect(store.linesFor('b1')).toEqual(['one', 'two', 'three', 'four', 'five', 'six']);
    expect(store.needsLog('b1')).toBe(false);
  });

  it('stops asking when the answer brings it no further', async () => {
    const { client, socket } = connected();
    const store = new BuildStore();
    // A snapshot claiming output this backend cannot actually serve.
    store.setSnapshot([build({ log_next_offset: 4 })], 'b1');

    const filling = fillLogGaps(client, store, ['b1']);
    await flush();
    const command = socket.commands.at(-1);
    socket.receive({
      id: command!.id,
      type: 'result',
      payload: logResult({ offset: 0, lines: [], next_offset: 0 }),
    });
    await filling;

    // Still short, and still not asking again.
    expect(store.needsLog('b1')).toBe(true);
    expect(socket.commands.filter((frame) => frame.type === 'build/log')).toHaveLength(1);
  });

  it('sends nothing for a build whose log is complete', async () => {
    const { client, socket } = connected();
    const store = new BuildStore();
    store.setSnapshot([build()], 'b1');

    await fillLogGaps(client, store, ['b1']);
    expect(socket.commands).toHaveLength(0);
  });
});

describe('coalesced', () => {
  it('runs once more for a call that arrived while it was running', async () => {
    let running = 0;
    let release = (): void => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const refill = coalesced(async () => {
      running += 1;
      if (running === 1) await gate;
    });

    refill();
    // The second reason — a device page opened while the first fetch is
    // in flight, whose build is not in that fetch's targets.
    refill();
    refill();
    expect(running).toBe(1);

    release();
    await flush();

    // Owed once, not once per suppressed call, and not never.
    expect(running).toBe(2);
  });

  it('does not run again when nothing asked while it ran', async () => {
    let running = 0;
    const refill = coalesced(() => {
      running += 1;
      return Promise.resolve();
    });

    refill();
    await flush();
    expect(running).toBe(1);
  });
});
