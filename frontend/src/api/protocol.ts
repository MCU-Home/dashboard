// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The `/ws` frame vocabulary, from the client's side.
 *
 * The mirror of the backend's `protocol.py`, and deliberately the same
 * shape: one place where untrusted text becomes a typed frame, so that
 * everything downstream may assume a well-formed one. ADR 0004 records
 * that no OpenAPI document falls out of a WebSocket API and that the
 * vocabulary is documented by hand — `backend/README.md` is that
 * document and this file is its type-level restatement.
 *
 * The distinction the backend makes and this file keeps: an **error
 * frame** means the command could not be carried out. A configuration
 * that fails to validate is not an error — it is a successful
 * `device/validate` whose result says `ok: false` and carries the
 * diagnostics.
 */

/**
 * "One of these, or something a newer backend added."
 *
 * The wire vocabulary is deliberately open: this client is one of two
 * planned consumers (ADR 0005 names a future VS Code extension as the
 * second) and follows the backend's releases rather than pinning to
 * them, so a code it has never heard of must arrive as data, not as a
 * type error. `string & {}` is the idiom that keeps the named members
 * visible to an editor instead of collapsing the whole union to
 * `string`.
 */
export type Open<T extends string> = T | (string & {});

/** Every code an error frame can carry. Kept in sync with `protocol.py`. */
export const ERROR_CODES = [
  'bad_request',
  'unknown_command',
  'not_found',
  'unauthorized',
  'unavailable',
  'conflict',
  'internal_error',
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

/**
 * Codes this client invents for failures that never reached the server.
 *
 * They are not part of the wire vocabulary; they exist so that a caller
 * can handle "the command failed" in one place regardless of where it
 * failed.
 */
export const CLIENT_ERROR_CODES = ['disconnected', 'timeout'] as const;

export type ClientErrorCode = (typeof CLIENT_ERROR_CODES)[number];

export interface CommandFrame {
  id: string;
  type: string;
  payload: Record<string, unknown>;
}

export interface ResultFrame {
  kind: 'result';
  id: string;
  payload: Record<string, unknown>;
}

export interface ErrorFrame {
  kind: 'error';
  /** Absent when the server refused a frame it could not read an id from. */
  id: string | null;
  code: Open<ErrorCode>;
  message: string;
  /** Whatever else the server attached — `known` on an unknown command, say. */
  detail: Record<string, unknown>;
}

export interface EventFrame {
  kind: 'event';
  event: string;
  payload: Record<string, unknown>;
}

export type ServerFrame = ResultFrame | ErrorFrame | EventFrame;

/** A frame this client could not read. Never thrown at application code. */
export class ProtocolError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ProtocolError';
  }
}

/**
 * A command that failed, as a rejected promise.
 *
 * `code` is what the caller branches on — `conflict` puts a "reload or
 * overwrite" choice in front of the user, `not_found` means the device
 * was deleted while a tab held it open, `disconnected` means try again.
 */
export class CommandError extends Error {
  readonly code: Open<ErrorCode | ClientErrorCode>;
  readonly detail: Record<string, unknown>;

  constructor(code: string, message: string, detail: Record<string, unknown> = {}) {
    super(message);
    this.name = 'CommandError';
    this.code = code;
    this.detail = detail;
  }
}

export function encodeCommand(frame: CommandFrame): string {
  return JSON.stringify({ id: frame.id, type: frame.type, payload: frame.payload });
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function frameId(value: unknown): string | null {
  // The server echoes the id it was given, and `protocol.py` accepts
  // both strings and numbers. This client only ever sends strings, so
  // normalising here means the correlation table has one key type.
  if (typeof value === 'string') return value;
  if (typeof value === 'number') return String(value);
  return null;
}

/** Turn one text frame into a typed frame, or refuse it. */
export function decodeFrame(raw: unknown): ServerFrame {
  if (typeof raw !== 'string') {
    throw new ProtocolError('This endpoint speaks JSON text frames only.');
  }

  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch (cause) {
    throw new ProtocolError(`This frame is not valid JSON: ${String(cause)}`);
  }

  if (typeof data !== 'object' || data === null || Array.isArray(data)) {
    throw new ProtocolError('A frame must be a JSON object.');
  }
  const frame = data as Record<string, unknown>;
  const type = frame.type;

  if (type === 'result') {
    const id = frameId(frame.id);
    if (id === null) {
      throw new ProtocolError('A result frame must carry the id of the command it answers.');
    }
    return { kind: 'result', id, payload: asRecord(frame.payload) };
  }

  if (type === 'error') {
    const error = asRecord(frame.error);
    const { code, message, ...detail } = error;
    return {
      kind: 'error',
      id: frameId(frame.id),
      code: typeof code === 'string' ? code : 'internal_error',
      message: typeof message === 'string' ? message : 'The server refused this command.',
      detail,
    };
  }

  if (type === 'event') {
    const name = frame.event;
    if (typeof name !== 'string' || name === '') {
      throw new ProtocolError('An event frame must name its event.');
    }
    return { kind: 'event', event: name, payload: asRecord(frame.payload) };
  }

  throw new ProtocolError(`Unknown frame type: ${JSON.stringify(type)}.`);
}
