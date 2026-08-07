// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The frame vocabulary, from the untrusted side.
 *
 * `decodeFrame` is the one place where text off a socket becomes a typed
 * frame, so the assertions here are mostly about refusal: everything it
 * lets through, the rest of the application is allowed to assume.
 */

import { describe, expect, it } from 'vitest';

import { decodeFrame, encodeCommand, ERROR_CODES, ProtocolError } from '../src/api/protocol';

describe('encodeCommand', () => {
  it('writes exactly the three fields the server reads', () => {
    const text = encodeCommand({ id: '7', type: 'device/get', payload: { name: 'bench-node' } });
    expect(JSON.parse(text)).toEqual({
      id: '7',
      type: 'device/get',
      payload: { name: 'bench-node' },
    });
  });

  it('sends an empty payload as an object, not as null', () => {
    const text = encodeCommand({ id: '1', type: 'ping', payload: {} });
    expect(JSON.parse(text)).toEqual({ id: '1', type: 'ping', payload: {} });
  });
});

describe('decodeFrame', () => {
  it('reads a result frame', () => {
    const frame = decodeFrame('{"id":"7","type":"result","payload":{"devices":[]}}');
    expect(frame).toEqual({ kind: 'result', id: '7', payload: { devices: [] } });
  });

  it('normalises a numeric id, because protocol.py accepts one', () => {
    const frame = decodeFrame('{"id":7,"type":"result","payload":{}}');
    expect(frame).toMatchObject({ kind: 'result', id: '7' });
  });

  it('reads an error frame and keeps whatever else it carried', () => {
    const frame = decodeFrame(
      '{"id":"3","type":"error","error":{"code":"unknown_command","message":"nope","known":["ping"]}}',
    );
    expect(frame).toEqual({
      kind: 'error',
      id: '3',
      code: 'unknown_command',
      message: 'nope',
      detail: { known: ['ping'] },
    });
  });

  it('accepts an error frame without an id — the server could not read one', () => {
    const frame = decodeFrame('{"type":"error","error":{"code":"bad_request","message":"x"}}');
    expect(frame).toMatchObject({ kind: 'error', id: null });
  });

  it('reads an event frame, which answers nothing and carries no id', () => {
    const frame = decodeFrame(
      '{"type":"event","event":"device_changed","payload":{"device":{"name":"a"}}}',
    );
    expect(frame).toEqual({
      kind: 'event',
      event: 'device_changed',
      payload: { device: { name: 'a' } },
    });
  });

  it('substitutes an empty payload rather than handing on a non-object', () => {
    expect(decodeFrame('{"type":"event","event":"tick","payload":42}')).toMatchObject({
      payload: {},
    });
  });

  it.each([
    ['not JSON at all', 'not json'],
    ['a JSON array', '[1,2,3]'],
    ['a result without an id', '{"type":"result","payload":{}}'],
    ['an event without a name', '{"type":"event","payload":{}}'],
    ['an unknown frame type', '{"type":"whatever"}'],
    ['a binary frame', 42],
  ])('refuses %s', (_label, raw) => {
    expect(() => decodeFrame(raw)).toThrow(ProtocolError);
  });

  it('falls back to a renderable error when the server sends a shapeless one', () => {
    const frame = decodeFrame('{"id":"1","type":"error","error":{}}');
    expect(frame).toMatchObject({ kind: 'error', code: 'internal_error' });
    expect((frame as { message: string }).message).not.toBe('');
  });
});

describe('the error vocabulary', () => {
  it('is the one protocol.py documents', () => {
    // Kept literal on purpose: a code added on one side and not the
    // other is exactly the drift this list exists to make visible.
    expect([...ERROR_CODES]).toEqual([
      'bad_request',
      'unknown_command',
      'not_found',
      'unauthorized',
      'unavailable',
      'conflict',
      'internal_error',
    ]);
  });
});
