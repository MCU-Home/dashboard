// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The one connection, and the failure mode ADR 0004 says to design for.
 *
 * Correlation, reconnect and event routing are what this class exists
 * for, so they are what is tested. Everything runs on an injected clock
 * and injected sockets — no real timer, no real WebSocket, no waiting.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { WsClient } from '../src/api/client';
import type { WsClientOptions } from '../src/api/client';
import { CommandError } from '../src/api/protocol';
import { flush, ManualClock, socketRecorder } from './helpers';

function makeClient(overrides: Partial<WsClientOptions> = {}) {
  const clock = new ManualClock();
  const recorder = socketRecorder();
  const client = new WsClient({
    url: () => 'ws://test/ws',
    socketFactory: recorder.factory,
    timers: clock,
    random: () => 0.5,
    backoff: [1, 2, 4],
    requestTimeout: 30,
    probe: () => Promise.resolve(false),
    ...overrides,
  });
  return { client, clock, sockets: recorder.sockets };
}

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});

describe('request/response correlation', () => {
  it('resolves each command with the frame that carries its id', async () => {
    const { client, sockets } = makeClient();
    client.connect();
    sockets[0]!.open();

    const first = client.send<{ pong: boolean }>('ping');
    const second = client.send<{ devices: unknown[] }>('device/list');
    const [pingFrame, listFrame] = sockets[0]!.commands;

    // Answered out of order, which is exactly what the ids are for.
    sockets[0]!.receive({ id: listFrame!.id, type: 'result', payload: { devices: [] } });
    sockets[0]!.receive({ id: pingFrame!.id, type: 'result', payload: { pong: true } });

    await expect(first).resolves.toEqual({ pong: true });
    await expect(second).resolves.toEqual({ devices: [] });
  });

  it('gives every command a distinct id', () => {
    const { client, sockets } = makeClient();
    client.connect();
    sockets[0]!.open();

    void client.send('ping');
    void client.send('ping');
    const ids = sockets[0]!.commands.map((command) => command.id);
    expect(new Set(ids).size).toBe(2);
  });

  it('rejects with the server error code, so a caller can branch on it', async () => {
    const { client, sockets } = makeClient();
    client.connect();
    sockets[0]!.open();

    const saved = client.send('device/save', { name: 'a', content: '' });
    const id = sockets[0]!.commands[0]!.id;
    sockets[0]!.receive({
      id,
      type: 'error',
      error: { code: 'conflict', message: 'changed on disk' },
    });

    await expect(saved).rejects.toMatchObject({ code: 'conflict', message: 'changed on disk' });
    await expect(saved).rejects.toBeInstanceOf(CommandError);
  });

  it('queues a command sent before the socket is open, then sends it once', async () => {
    const { client, sockets } = makeClient();
    client.connect();

    const answered = client.send('ping');
    expect(sockets[0]!.sent).toHaveLength(0);

    sockets[0]!.open();
    expect(sockets[0]!.commands).toHaveLength(1);

    sockets[0]!.receive({
      id: sockets[0]!.commands[0]!.id,
      type: 'result',
      payload: { pong: true },
    });
    await expect(answered).resolves.toEqual({ pong: true });
  });

  it('rejects an unanswered command when the connection drops', async () => {
    const { client, sockets } = makeClient();
    client.connect();
    sockets[0]!.open();

    const pending = client.send('device/validate', { name: 'a' });
    sockets[0]!.die();

    await expect(pending).rejects.toMatchObject({ code: 'disconnected' });
  });

  it('never re-sends a command that was already on the wire', async () => {
    // `device/save` is not idempotent: re-sending it after a reconnect
    // could overwrite a write that happened in between.
    const { client, clock, sockets } = makeClient();
    client.connect();
    sockets[0]!.open();

    const pending = client.send('device/save', { name: 'a', content: 'x' });
    sockets[0]!.die();
    await expect(pending).rejects.toMatchObject({ code: 'disconnected' });

    await flush();
    clock.advance(2000);
    sockets[1]!.open();
    expect(sockets[1]!.sent).toHaveLength(0);
  });

  it('re-sends a command that was still queued, because nobody saw it', async () => {
    const { client, clock, sockets } = makeClient();
    client.connect();
    sockets[0]!.open();
    sockets[0]!.die();

    const pending = client.send('ping');
    await flush();
    clock.advance(2000);
    sockets[1]!.open();

    expect(sockets[1]!.commands).toHaveLength(1);
    sockets[1]!.receive({
      id: sockets[1]!.commands[0]!.id,
      type: 'result',
      payload: { pong: true },
    });
    await expect(pending).resolves.toEqual({ pong: true });
  });

  it('times a command out rather than waiting forever', async () => {
    const { client, clock, sockets } = makeClient({ requestTimeout: 5 });
    client.connect();
    sockets[0]!.open();

    const pending = client.send('device/validate', { name: 'slow' });
    clock.advance(5000);

    await expect(pending).rejects.toMatchObject({ code: 'timeout' });
    expect(client.pendingCount).toBe(0);
  });

  it('ignores a late answer to a command that already timed out', async () => {
    const { client, clock, sockets } = makeClient({ requestTimeout: 5 });
    client.connect();
    sockets[0]!.open();

    const pending = client.send('ping');
    const id = sockets[0]!.commands[0]!.id;
    clock.advance(5000);
    await expect(pending).rejects.toMatchObject({ code: 'timeout' });

    expect(() => sockets[0]!.receive({ id, type: 'result', payload: {} })).not.toThrow();
  });
});

describe('events', () => {
  it('routes event frames to the event listeners and to nobody else', () => {
    const { client, sockets } = makeClient();
    const seen: string[] = [];
    client.onEvent((frame) => seen.push(frame.event));
    client.connect();
    sockets[0]!.open();

    sockets[0]!.receive({ type: 'event', event: 'device_changed', payload: { device: {} } });
    sockets[0]!.receive({ type: 'event', event: 'events_dropped', payload: { count: 2 } });

    expect(seen).toEqual(['device_changed', 'events_dropped']);
  });

  it('hands the event payload through unchanged', () => {
    const { client, sockets } = makeClient();
    const payloads: Record<string, unknown>[] = [];
    client.onEvent((frame) => payloads.push(frame.payload));
    client.connect();
    sockets[0]!.open();

    sockets[0]!.receive({
      type: 'event',
      event: 'tree_state',
      payload: { root: '/x', available: true },
    });
    expect(payloads).toEqual([{ root: '/x', available: true }]);
  });

  it('discards an unreadable frame instead of tearing the connection down', () => {
    const { client, sockets } = makeClient();
    const seen: string[] = [];
    client.onEvent((frame) => seen.push(frame.event));
    client.connect();
    sockets[0]!.open();

    sockets[0]!.receiveRaw('}{ not json');
    sockets[0]!.receive({ type: 'event', event: 'device_added', payload: {} });

    expect(seen).toEqual(['device_added']);
    expect(client.state).toBe('open');
  });

  it('unsubscribes a listener when its handle is called', () => {
    const { client, sockets } = makeClient();
    const seen: string[] = [];
    const stop = client.onEvent((frame) => seen.push(frame.event));
    client.connect();
    sockets[0]!.open();

    sockets[0]!.receive({ type: 'event', event: 'one', payload: {} });
    stop();
    sockets[0]!.receive({ type: 'event', event: 'two', payload: {} });

    expect(seen).toEqual(['one']);
  });
});

describe('reconnect', () => {
  it('reports its state as it goes', async () => {
    const { client, clock, sockets } = makeClient();
    const states: string[] = [];
    client.onState((state) => states.push(state));

    client.connect();
    sockets[0]!.open();
    sockets[0]!.die();
    await flush();
    clock.advance(2000);
    sockets[1]!.open();

    expect(states).toEqual(['connecting', 'open', 'reconnecting', 'open']);
  });

  it('backs off further after each failed attempt', async () => {
    const { client, clock, sockets } = makeClient({ backoff: [1, 2, 4] });
    client.connect();
    sockets[0]!.open();

    // 0.5 randomness means the jitter factor is exactly 1.
    sockets[0]!.die();
    await flush();
    clock.advance(999);
    expect(sockets).toHaveLength(1);
    clock.advance(1);
    expect(sockets).toHaveLength(2);

    sockets[1]!.die();
    await flush();
    clock.advance(1999);
    expect(sockets).toHaveLength(2);
    clock.advance(1);
    expect(sockets).toHaveLength(3);
  });

  it('holds at the last backoff step rather than growing without bound', async () => {
    const { client, clock, sockets } = makeClient({ backoff: [1, 2] });
    client.connect();
    sockets[0]!.open();

    for (let attempt = 0; attempt < 4; attempt += 1) {
      sockets[sockets.length - 1]!.die();
      await flush();
      clock.advance(2000);
    }
    expect(sockets).toHaveLength(5);
  });

  it('resets the backoff after a successful open', async () => {
    const { client, clock, sockets } = makeClient({ backoff: [1, 2, 4] });
    client.connect();
    sockets[0]!.open();

    sockets[0]!.die();
    await flush();
    clock.advance(1000);
    sockets[1]!.open();

    sockets[1]!.die();
    await flush();
    clock.advance(1000);
    expect(sockets).toHaveLength(3);
  });

  it('jitters the delay, so restarted tabs do not return in lockstep', async () => {
    const { client, clock, sockets } = makeClient({ backoff: [10], random: () => 0 });
    client.connect();
    sockets[0]!.open();
    sockets[0]!.die();
    await flush();

    // 10 s × (0.75 + 0 × 0.5) = 7.5 s, not 10 s.
    clock.advance(7499);
    expect(sockets).toHaveLength(1);
    clock.advance(1);
    expect(sockets).toHaveLength(2);
  });

  it('announces every successful open, so views re-subscribe', async () => {
    const { client, clock, sockets } = makeClient();
    const connects = vi.fn();
    client.onConnected(connects);

    client.connect();
    sockets[0]!.open();
    sockets[0]!.die();
    await flush();
    clock.advance(2000);
    sockets[1]!.open();

    expect(connects).toHaveBeenCalledTimes(2);
  });

  it('stops reconnecting once it is closed deliberately', async () => {
    const { client, clock, sockets } = makeClient();
    client.connect();
    sockets[0]!.open();

    client.close();
    await flush();
    clock.advance(60_000);

    expect(sockets).toHaveLength(1);
    expect(client.state).toBe('closed');
  });

  it('rejects even queued commands when it is closed deliberately', async () => {
    const { client } = makeClient();
    client.connect();
    const pending = client.send('ping');
    client.close();
    await expect(pending).rejects.toMatchObject({ code: 'disconnected' });
  });
});

describe('a refused connection', () => {
  it('is told apart from an unreachable server by the health probe', async () => {
    const { client, sockets } = makeClient({ probe: () => Promise.resolve(true) });
    client.connect();

    // Closed without ever opening: the browser cannot tell us it was a
    // 401, so the probe does.
    sockets[0]!.die();
    await flush();

    expect(client.state).toBe('refused');
  });

  it('keeps reconnecting when the server is simply down', async () => {
    const { client, clock, sockets } = makeClient({ probe: () => Promise.resolve(false) });
    client.connect();
    sockets[0]!.die();
    await flush();
    clock.advance(2000);

    expect(client.state).not.toBe('refused');
    expect(sockets.length).toBeGreaterThan(1);
  });

  it('stops the reconnect schedule while it is refused', async () => {
    const { client, clock, sockets } = makeClient({ probe: () => Promise.resolve(true) });
    client.connect();
    sockets[0]!.die();
    await flush();
    clock.advance(60_000);

    expect(sockets).toHaveLength(1);
  });

  it('rejects waiting commands, because nothing will answer them', async () => {
    const { client, sockets } = makeClient({ probe: () => Promise.resolve(true) });
    client.connect();
    const pending = client.send('server/info');
    sockets[0]!.die();
    await flush();

    await expect(pending).rejects.toMatchObject({ code: 'disconnected' });
  });

  it('tries again immediately when retry() is called — a login just succeeded', async () => {
    const { client, sockets } = makeClient({ probe: () => Promise.resolve(true) });
    client.connect();
    sockets[0]!.die();
    await flush();
    expect(client.state).toBe('refused');

    client.retry();
    expect(sockets).toHaveLength(2);
    sockets[1]!.open();
    expect(client.state).toBe('open');
  });
});
