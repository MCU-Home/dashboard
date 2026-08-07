// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The validity badges, and the queue that keeps them from being a
 * client-side denial of service against a Raspberry Pi.
 */

import { describe, expect, it, vi } from 'vitest';

import type { WsClient } from '../src/api/client';
import { CommandError } from '../src/api/protocol';
import { ValidityTracker } from '../src/state/validity';

interface Deferred {
  resolve: (value: unknown) => void;
  reject: (reason: unknown) => void;
}

/** A client whose `device/validate` answers only when a test says so. */
function scriptedClient() {
  const inFlight: { name: string; deferred: Deferred }[] = [];
  const client = {
    send: (_type: string, payload: Record<string, unknown>) =>
      new Promise((resolve, reject) => {
        inFlight.push({ name: payload.name as string, deferred: { resolve, reject } });
      }),
  } as unknown as WsClient;
  return { client, inFlight };
}

async function settle(): Promise<void> {
  for (let index = 0; index < 4; index += 1) await Promise.resolve();
}

describe('ValidityTracker', () => {
  it('knows nothing about a device it has not been told about', () => {
    const { client } = scriptedClient();
    expect(new ValidityTracker(client).get('nope').status).toBe('unknown');
  });

  it('marks a queued device as checking straight away', () => {
    const { client } = scriptedClient();
    const tracker = new ValidityTracker(client);
    tracker.sync(['a']);
    expect(tracker.get('a').status).toBe('checking');
  });

  it('validates one device at a time', async () => {
    const { client, inFlight } = scriptedClient();
    const tracker = new ValidityTracker(client);
    tracker.sync(['a', 'b', 'c']);
    await settle();

    // Three devices queued, one command on the wire.
    expect(inFlight).toHaveLength(1);
    expect(inFlight[0]!.name).toBe('a');

    inFlight[0]!.deferred.resolve({ name: 'a', ok: true, errors: [], device: null });
    await settle();
    expect(inFlight).toHaveLength(2);
    expect(inFlight[1]!.name).toBe('b');
  });

  it('records the verdict and the number of problems', async () => {
    const { client, inFlight } = scriptedClient();
    const tracker = new ValidityTracker(client);
    tracker.sync(['a']);
    await settle();

    inFlight[0]!.deferred.resolve({
      name: 'a',
      ok: false,
      errors: [{ message: 'x' }, { message: 'y' }],
      device: null,
    });
    await settle();

    expect(tracker.get('a')).toMatchObject({ status: 'invalid', errorCount: 2 });
  });

  it('shows a command that never came back as "not checked", not as a problem', async () => {
    const { client, inFlight } = scriptedClient();
    const tracker = new ValidityTracker(client);
    tracker.sync(['a']);
    await settle();

    inFlight[0]!.deferred.reject(new CommandError('disconnected', 'gone'));
    await settle();

    // The connection failed; the configuration was never judged.
    expect(tracker.get('a').status).toBe('failed');
    expect(tracker.get('a').errorCount).toBe(0);
  });

  it('does not re-check a device it already has a verdict for', async () => {
    const { client, inFlight } = scriptedClient();
    const tracker = new ValidityTracker(client);
    tracker.sync(['a']);
    await settle();
    inFlight[0]!.deferred.resolve({ name: 'a', ok: true, errors: [], device: null });
    await settle();

    tracker.sync(['a']);
    await settle();
    expect(inFlight).toHaveLength(1);
  });

  it('re-checks a device that changed', async () => {
    const { client, inFlight } = scriptedClient();
    const tracker = new ValidityTracker(client);
    tracker.sync(['a']);
    await settle();
    inFlight[0]!.deferred.resolve({ name: 'a', ok: true, errors: [], device: null });
    await settle();

    tracker.invalidate('a');
    expect(tracker.get('a').status).toBe('checking');
    await settle();
    expect(inFlight).toHaveLength(2);
  });

  it('forgets a device that left the tree', async () => {
    const { client, inFlight } = scriptedClient();
    const tracker = new ValidityTracker(client);
    tracker.sync(['a']);
    await settle();
    inFlight[0]!.deferred.resolve({
      name: 'a',
      ok: false,
      errors: [{ message: 'x' }],
      device: null,
    });
    await settle();

    tracker.sync([]);
    expect(tracker.get('a').status).toBe('unknown');
  });

  it('notifies its listeners as verdicts arrive', async () => {
    const { client, inFlight } = scriptedClient();
    const tracker = new ValidityTracker(client);
    const listener = vi.fn();
    tracker.subscribe(listener);

    tracker.sync(['a']);
    await settle();
    inFlight[0]!.deferred.resolve({ name: 'a', ok: true, errors: [], device: null });
    await settle();

    expect(listener.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('starts over after a reconnect', async () => {
    const { client, inFlight } = scriptedClient();
    const tracker = new ValidityTracker(client);
    tracker.sync(['a']);
    await settle();
    inFlight[0]!.deferred.resolve({ name: 'a', ok: true, errors: [], device: null });
    await settle();

    tracker.reset();
    expect(tracker.get('a').status).toBe('unknown');
  });
});
