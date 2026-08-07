// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * One WebSocket, the whole UI.
 *
 * ADR 0004's consequence — "one connection carries the entire UI, so
 * reconnect-and-resubscribe is the single failure mode worth designing
 * carefully" — is this file's whole job. Three things follow from it:
 *
 * **Subscriptions die with the connection.** The server keeps them per
 * socket on purpose, so a reconnect is not a repair of old state: it
 * re-subscribes and takes a fresh snapshot. {@link WsClient.onConnected}
 * fires on *every* successful open, including reconnects, and is where
 * views hook that.
 *
 * **A sent command is never retried.** `device/save` is not idempotent,
 * so a request that was on the wire when the socket died is rejected
 * with `disconnected` and the user decides. A request that was still
 * queued was never seen by anyone and is simply sent on the next open.
 *
 * **`events_dropped` is a real state, not a warning.** The server tells
 * a connection that fell too far behind that it discarded events for it;
 * the honest response is to re-subscribe rather than keep rendering a
 * list that quietly stopped being true.
 *
 * Everything the class touches from the outside world — sockets, timers,
 * randomness, the reachability probe — is injectable, because the
 * behaviour worth testing here is the timing.
 */

import { webSocketUrl } from '../base-path';
import type { CommandFrame, ServerFrame } from './protocol';
import { CommandError, decodeFrame, encodeCommand, ProtocolError } from './protocol';

/**
 * What the connection indicator shows.
 *
 * `refused` is its own state because it is the only one a user can do
 * something about: the socket was rejected before it opened while the
 * server itself answers, which on the public site means "log in".
 */
export type ConnectionState = 'connecting' | 'open' | 'reconnecting' | 'refused' | 'closed';

/** The part of `WebSocket` this client uses, so a test can be one. */
export interface SocketLike {
  send(data: string): void;
  close(code?: number, reason?: string): void;
  onopen: (() => void) | null;
  onclose: (() => void) | null;
  onerror: (() => void) | null;
  onmessage: ((event: { data: unknown }) => void) | null;
}

export type SocketFactory = (url: string) => SocketLike;

export interface Timers {
  setTimeout(handler: () => void, timeout: number): number;
  clearTimeout(handle: number): void;
}

export interface WsClientOptions {
  /** Recomputed per attempt, so a base path that arrived late is picked up. */
  url?: () => string;
  socketFactory?: SocketFactory;
  /**
   * "Is the server there at all?" — used to tell a refused upgrade from
   * an unreachable host. A browser cannot read the HTTP status of a
   * failed WebSocket handshake, so a socket that closed without ever
   * opening is ambiguous: the server may be down, or it may have
   * answered 401/403. `GET /health` is open on both sites and settles it.
   */
  probe?: () => Promise<boolean>;
  /** Seconds between reconnect attempts; the last value repeats. */
  backoff?: number[];
  /** Seconds before an unanswered command rejects. */
  requestTimeout?: number;
  timers?: Timers;
  random?: () => number;
}

const DEFAULT_BACKOFF = [0.5, 1, 2, 4, 8, 15];
const DEFAULT_REQUEST_TIMEOUT = 30;

interface Pending {
  frame: CommandFrame;
  resolve: (payload: Record<string, unknown>) => void;
  reject: (error: CommandError) => void;
  timer: number;
  /** False while the frame is still queued, so it survives a reconnect. */
  sent: boolean;
}

type Unsubscribe = () => void;

export class WsClient {
  #socket: SocketLike | null = null;
  #state: ConnectionState = 'closed';
  #attempt = 0;
  #counter = 0;
  #stopped = true;
  #reconnectTimer: number | null = null;
  #openedOnce = false;

  readonly #pending = new Map<string, Pending>();
  readonly #stateListeners = new Set<(state: ConnectionState) => void>();
  readonly #eventListeners = new Set<
    (frame: { event: string; payload: Record<string, unknown> }) => void
  >();
  readonly #connectedListeners = new Set<() => void>();

  readonly #url: () => string;
  readonly #socketFactory: SocketFactory;
  readonly #probe: () => Promise<boolean>;
  readonly #backoff: number[];
  readonly #requestTimeout: number;
  readonly #timers: Timers;
  readonly #random: () => number;

  constructor(options: WsClientOptions = {}) {
    this.#url = options.url ?? (() => webSocketUrl());
    this.#socketFactory =
      options.socketFactory ?? ((url) => new WebSocket(url) as unknown as SocketLike);
    this.#probe = options.probe ?? (() => Promise.resolve(false));
    this.#backoff = options.backoff ?? DEFAULT_BACKOFF;
    this.#requestTimeout = options.requestTimeout ?? DEFAULT_REQUEST_TIMEOUT;
    this.#timers = options.timers ?? {
      setTimeout: (handler, timeout) => globalThis.setTimeout(handler, timeout),
      clearTimeout: (handle) => globalThis.clearTimeout(handle),
    };
    this.#random = options.random ?? Math.random;
  }

  get state(): ConnectionState {
    return this.#state;
  }

  /** How many commands are waiting for an answer or for a connection. */
  get pendingCount(): number {
    return this.#pending.size;
  }

  // -- listeners ---------------------------------------------------

  onState(listener: (state: ConnectionState) => void): Unsubscribe {
    this.#stateListeners.add(listener);
    return () => this.#stateListeners.delete(listener);
  }

  /** Every event frame, in arrival order. */
  onEvent(
    listener: (frame: { event: string; payload: Record<string, unknown> }) => void,
  ): Unsubscribe {
    this.#eventListeners.add(listener);
    return () => this.#eventListeners.delete(listener);
  }

  /** Fires after *every* successful open. Re-subscribe from here. */
  onConnected(listener: () => void): Unsubscribe {
    this.#connectedListeners.add(listener);
    return () => this.#connectedListeners.delete(listener);
  }

  // -- lifecycle ---------------------------------------------------

  connect(): void {
    this.#stopped = false;
    if (this.#socket !== null || this.#reconnectTimer !== null) {
      return;
    }
    this.#open();
  }

  /**
   * Try again now, from a clean slate.
   *
   * What a "log in" that succeeded and a "retry" button both call: it
   * drops the backoff, because the reason for the last failure was just
   * removed and making the user wait fifteen seconds for the schedule to
   * catch up would be arbitrary.
   */
  retry(): void {
    this.#cancelReconnect();
    this.#attempt = 0;
    this.#closeSocket();
    this.connect();
  }

  /** Deliberate shutdown: no reconnect, and every waiter is told. */
  close(): void {
    this.#stopped = true;
    this.#cancelReconnect();
    this.#closeSocket();
    this.#failPending('disconnected', 'The connection to the dashboard was closed.', true);
    this.#setState('closed');
  }

  // -- commands ----------------------------------------------------

  /**
   * Send one command and wait for the frame that answers it.
   *
   * Rejects with a {@link CommandError} — never with an error frame, and
   * never with a raw `Error`, so a caller has one thing to catch and one
   * field (`code`) to branch on.
   */
  send<T>(type: string, payload: Record<string, unknown> = {}): Promise<T> {
    this.#counter += 1;
    const frame: CommandFrame = { id: String(this.#counter), type, payload };

    return new Promise<T>((resolve, reject) => {
      const timer = this.#timers.setTimeout(() => {
        this.#pending.delete(frame.id);
        reject(new CommandError('timeout', `"${type}" was not answered in time.`));
      }, this.#requestTimeout * 1000);

      this.#pending.set(frame.id, {
        frame,
        resolve: resolve as (payload: Record<string, unknown>) => void,
        reject,
        timer,
        sent: false,
      });
      this.#flush();
    });
  }

  // -- internals ---------------------------------------------------

  #setState(state: ConnectionState): void {
    if (this.#state === state) return;
    this.#state = state;
    for (const listener of this.#stateListeners) listener(state);
  }

  #open(): void {
    this.#setState(this.#openedOnce ? 'reconnecting' : 'connecting');

    let socket: SocketLike;
    try {
      socket = this.#socketFactory(this.#url());
    } catch {
      // A URL the browser refuses to open at all — mixed content, say.
      this.#scheduleReconnect(false);
      return;
    }
    this.#socket = socket;
    let opened = false;

    socket.onopen = () => {
      opened = true;
      this.#openedOnce = true;
      this.#attempt = 0;
      this.#setState('open');
      this.#flush();
      for (const listener of this.#connectedListeners) listener();
    };

    socket.onmessage = (message) => {
      let frame: ServerFrame;
      try {
        frame = decodeFrame(message.data);
      } catch (cause) {
        if (cause instanceof ProtocolError) {
          console.warn('mcuhome: discarding an unreadable frame —', cause.message);
          return;
        }
        throw cause;
      }
      this.#dispatch(frame);
    };

    // `onerror` carries nothing a browser is allowed to tell us; the
    // close that follows it is where the handling lives.
    socket.onerror = () => {};

    socket.onclose = () => {
      if (this.#socket !== socket) return;
      this.#socket = null;
      // Only sent commands are lost. Queued ones were never seen by the
      // server and go out again on the next open.
      this.#failPending('disconnected', 'The connection dropped before this command was answered.');
      if (this.#stopped) {
        this.#setState('closed');
        return;
      }
      this.#scheduleReconnect(opened);
    };
  }

  #dispatch(frame: ServerFrame): void {
    if (frame.kind === 'event') {
      for (const listener of this.#eventListeners) listener(frame);
      return;
    }

    const id = frame.id;
    if (id === null) {
      // Only an error frame can lack an id: the server could not read
      // one from the frame it is refusing. Nobody is waiting for it.
      if (frame.kind === 'error') {
        console.warn('mcuhome: server error without a command id —', frame.message);
      }
      return;
    }
    const pending = this.#pending.get(id);
    if (pending === undefined) {
      // A late answer to a command that already timed out.
      return;
    }
    this.#pending.delete(id);
    this.#timers.clearTimeout(pending.timer);

    if (frame.kind === 'error') {
      pending.reject(new CommandError(frame.code, frame.message, frame.detail));
    } else {
      pending.resolve(frame.payload);
    }
  }

  #flush(): void {
    const socket = this.#socket;
    if (socket === null || this.#state !== 'open') return;
    for (const pending of this.#pending.values()) {
      if (pending.sent) continue;
      pending.sent = true;
      socket.send(encodeCommand(pending.frame));
    }
  }

  #failPending(code: string, message: string, includeQueued = false): void {
    for (const [id, pending] of [...this.#pending]) {
      if (!pending.sent && !includeQueued) continue;
      this.#pending.delete(id);
      this.#timers.clearTimeout(pending.timer);
      pending.reject(new CommandError(code, message));
    }
  }

  #closeSocket(): void {
    const socket = this.#socket;
    if (socket === null) return;
    this.#socket = null;
    socket.onopen = null;
    socket.onclose = null;
    socket.onerror = null;
    socket.onmessage = null;
    try {
      socket.close();
    } catch {
      // Closing a socket that never opened throws in some browsers.
    }
  }

  #scheduleReconnect(hadOpened: boolean): void {
    if (!hadOpened) {
      // Never opened: either the server is down or it refused the
      // upgrade. Only the probe can tell those apart, and only one of
      // them is the user's to fix.
      void this.#probe().then((reachable) => {
        if (this.#stopped) return;
        if (reachable) {
          this.#cancelReconnect();
          this.#setState('refused');
          this.#failPending(
            'disconnected',
            'The dashboard refused this connection. Log in and try again.',
            true,
          );
          return;
        }
        this.#armReconnect();
      });
      this.#armReconnect();
      return;
    }
    this.#armReconnect();
  }

  #armReconnect(): void {
    if (this.#stopped || this.#reconnectTimer !== null || this.#state === 'refused') return;
    const step = this.#backoff[Math.min(this.#attempt, this.#backoff.length - 1)] ?? 1;
    this.#attempt += 1;
    // Jitter, so a dashboard restart does not get every open tab back at
    // the same millisecond.
    const delay = step * (0.75 + this.#random() * 0.5);
    this.#setState('reconnecting');
    this.#reconnectTimer = this.#timers.setTimeout(() => {
      this.#reconnectTimer = null;
      if (this.#stopped) return;
      this.#open();
    }, delay * 1000);
  }

  #cancelReconnect(): void {
    if (this.#reconnectTimer === null) return;
    this.#timers.clearTimeout(this.#reconnectTimer);
    this.#reconnectTimer = null;
  }
}
