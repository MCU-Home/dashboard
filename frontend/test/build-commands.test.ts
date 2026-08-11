// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The build half of the command vocabulary, on the wire.
 *
 * `commands.ts` is where a command name and its payload keys are spelled
 * once, so what is worth asserting is exactly that: the name the backend
 * dispatches on and the keys it reads. An optional field that is not
 * given must be *absent* rather than `null` — `build/start` treats a
 * missing `method` as "this deployment's own", and a null one as a
 * method called null.
 */

import { describe, expect, it } from 'vitest';

import { WsClient } from '../src/api/client';
import {
  buildCancel,
  buildLog,
  buildStart,
  buildStatus,
  buildStatusOf,
  buildSubscribe,
  TOPIC_BUILDS,
} from '../src/api/commands';
import { socketRecorder } from './helpers';

function connected() {
  const recorder = socketRecorder();
  const client = new WsClient({
    url: () => 'ws://test/ws',
    socketFactory: recorder.factory,
    probe: () => Promise.resolve(false),
  });
  client.connect();
  const socket = recorder.sockets[0]!;
  socket.open();
  return { client, socket };
}

describe('the builds topic', () => {
  it('is the name the backend publishes on', () => {
    expect(TOPIC_BUILDS).toBe('builds');
  });
});

describe('build commands', () => {
  it('starts a build of one device', () => {
    const { client, socket } = connected();
    void buildStart(client, 'bench-node');

    expect(socket.commands[0]).toMatchObject({
      type: 'build/start',
      payload: { name: 'bench-node' },
    });
    // Not `{name, method: undefined}` — the key has to be gone.
    expect(Object.keys(socket.commands[0]!.payload)).toEqual(['name']);
  });

  it('carries a method override when one was chosen', () => {
    const { client, socket } = connected();
    void buildStart(client, 'bench-node', 'remote');

    expect(socket.commands[0]?.payload).toEqual({ name: 'bench-node', method: 'remote' });
  });

  it('asks for every build, or for one by id', () => {
    const { client, socket } = connected();
    void buildStatus(client);
    void buildStatusOf(client, 'b1');

    expect(socket.commands[0]).toMatchObject({ type: 'build/status', payload: {} });
    expect(socket.commands[1]).toMatchObject({
      type: 'build/status',
      payload: { build_id: 'b1' },
    });
  });

  it('asks for a log from an offset, and from the start without one', () => {
    const { client, socket } = connected();
    void buildLog(client, 'b1', 42);
    void buildLog(client, 'b1');

    expect(socket.commands[0]?.payload).toEqual({ build_id: 'b1', from_offset: 42 });
    expect(socket.commands[1]?.payload).toEqual({ build_id: 'b1' });
  });

  it('sends offset zero rather than dropping it, which means something else', () => {
    const { client, socket } = connected();
    void buildLog(client, 'b1', 0);

    expect(socket.commands[0]?.payload).toEqual({ build_id: 'b1', from_offset: 0 });
  });

  it('cancels one build by id', () => {
    const { client, socket } = connected();
    void buildCancel(client, 'b1');

    expect(socket.commands[0]).toMatchObject({
      type: 'build/cancel',
      payload: { build_id: 'b1' },
    });
  });

  it('subscribes without a payload, because the snapshot is the result', () => {
    const { client, socket } = connected();
    void buildSubscribe(client);

    expect(socket.commands[0]).toMatchObject({ type: 'build/subscribe', payload: {} });
  });
});
