// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Doubles for the three things `WsClient` reaches outside itself.
 *
 * The behaviour worth testing in that class is *timing* — what happens
 * between a close and the next open, and in which order — so the socket,
 * the clock and the reachability probe are all injected and all driven
 * by hand here. No real timer runs in any of these tests, which is why
 * they are deterministic and take microseconds.
 */

import type { SocketLike, Timers } from '../src/api/client';

export class FakeSocket implements SocketLike {
  readonly url: string;
  readonly sent: string[] = [];
  closeCount = 0;

  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;

  constructor(url = 'ws://test/ws') {
    this.url = url;
  }

  send(data: string): void {
    this.sent.push(data);
  }

  close(): void {
    this.closeCount += 1;
  }

  /** The server accepted the upgrade. */
  open(): void {
    this.onopen?.();
  }

  /** One frame arrives. */
  receive(frame: unknown): void {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }

  /** Raw text, for the frames a well-formed encoder would never produce. */
  receiveRaw(data: unknown): void {
    this.onmessage?.({ data });
  }

  /** The connection went away. */
  die(): void {
    this.onclose?.();
  }

  /** The commands this socket was asked to send, decoded. */
  get commands(): { id: string; type: string; payload: Record<string, unknown> }[] {
    return this.sent.map((text) => JSON.parse(text) as never);
  }
}

interface Scheduled {
  at: number;
  handler: () => void;
}

export class ManualClock implements Timers {
  now = 0;
  #next = 1;
  readonly #scheduled = new Map<number, Scheduled>();

  setTimeout(handler: () => void, timeout: number): number {
    const handle = this.#next++;
    this.#scheduled.set(handle, { at: this.now + timeout, handler });
    return handle;
  }

  clearTimeout(handle: number): void {
    this.#scheduled.delete(handle);
  }

  get pending(): number {
    return this.#scheduled.size;
  }

  /** Move time forward, running whatever comes due, in order. */
  advance(milliseconds: number): void {
    const target = this.now + milliseconds;
    for (;;) {
      const due = [...this.#scheduled.entries()]
        .filter(([, entry]) => entry.at <= target)
        .sort((left, right) => left[1].at - right[1].at)[0];
      if (due === undefined) break;
      const [handle, entry] = due;
      this.#scheduled.delete(handle);
      this.now = entry.at;
      entry.handler();
    }
    this.now = target;
  }
}

/** Let every already-resolved promise callback run. */
export async function flush(times = 3): Promise<void> {
  for (let index = 0; index < times; index += 1) {
    await Promise.resolve();
  }
}

/** A factory that hands back every socket it made, newest last. */
export function socketRecorder(): { factory: (url: string) => SocketLike; sockets: FakeSocket[] } {
  const sockets: FakeSocket[] = [];
  return {
    sockets,
    factory: (url: string) => {
      const socket = new FakeSocket(url);
      sockets.push(socket);
      return socket;
    },
  };
}
