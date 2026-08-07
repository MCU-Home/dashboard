// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Whether each device in the list actually resolves.
 *
 * The list itself cannot answer this. `device/list` carries an
 * *unresolved* summary — parsed YAML and nothing more, with no `!secret`
 * looked up — because producing a validity flag means running the
 * builder's stages 1-3 over a device, and doing that for every device on
 * every list response would put a YAML parse per device in the way of
 * every subscription. So the badge is filled in afterwards, one device
 * at a time.
 *
 * **One at a time on purpose.** Each `device/validate` occupies a thread
 * on the backend (`commands.py`), and a tree of thirty devices firing
 * thirty of them at once would be a client-side denial of service
 * against a Raspberry Pi. The queue is the whole design.
 *
 * TODO(block-0): if the builder ever grows a cheap "does this resolve"
 * pass, or `device/list` starts carrying a validity flag, this file
 * becomes a cache instead of a scheduler.
 */

import type { WsClient } from '../api/client';
import { deviceValidate } from '../api/commands';
import type { Diagnostic } from '../api/types';

export type ValidityStatus = 'unknown' | 'checking' | 'ok' | 'invalid' | 'failed';

export interface Validity {
  status: ValidityStatus;
  errorCount: number;
  errors: readonly Diagnostic[];
}

const UNKNOWN: Validity = { status: 'unknown', errorCount: 0, errors: [] };

export class ValidityTracker {
  readonly #client: WsClient;
  readonly #validity = new Map<string, Validity>();
  readonly #queue: string[] = [];
  readonly #listeners = new Set<() => void>();
  #running = false;

  constructor(client: WsClient) {
    this.#client = client;
  }

  get(name: string): Validity {
    return this.#validity.get(name) ?? UNKNOWN;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }

  /**
   * Line the tracker up with the current device list.
   *
   * Devices it has never seen are queued; devices that are gone are
   * forgotten, so a name reused later starts from `unknown` rather than
   * from the verdict on a different device that happened to share it.
   */
  sync(names: readonly string[]): void {
    const present = new Set(names);
    for (const known of [...this.#validity.keys()]) {
      if (!present.has(known)) this.#validity.delete(known);
    }
    for (const name of names) {
      if (!this.#validity.has(name)) this.#enqueue(name);
    }
  }

  /** Re-check one device — what a `device_changed` event means here. */
  invalidate(name: string): void {
    this.#enqueue(name);
  }

  /** Drop everything. A reconnect re-checks from scratch. */
  reset(): void {
    this.#validity.clear();
    this.#queue.length = 0;
    this.#notify();
  }

  #enqueue(name: string): void {
    this.#validity.set(name, { status: 'checking', errorCount: 0, errors: [] });
    if (!this.#queue.includes(name)) this.#queue.push(name);
    this.#notify();
    void this.#drain();
  }

  async #drain(): Promise<void> {
    if (this.#running) return;
    this.#running = true;
    try {
      while (this.#queue.length > 0) {
        const name = this.#queue.shift();
        if (name === undefined) break;
        // A device deleted while it sat in the queue.
        if (!this.#validity.has(name)) continue;
        this.#validity.set(name, await this.#check(name));
        this.#notify();
      }
    } finally {
      this.#running = false;
    }
  }

  async #check(name: string): Promise<Validity> {
    try {
      const result = await deviceValidate(this.#client, name);
      const errors = result.errors ?? [];
      return {
        status: result.ok ? 'ok' : 'invalid',
        errorCount: errors.length,
        errors,
      };
    } catch {
      // The command itself failed — disconnected, or the device is gone.
      // Not a verdict on the configuration, and must not be shown as one.
      return { status: 'failed', errorCount: 0, errors: [] };
    }
  }

  #notify(): void {
    for (const listener of this.#listeners) listener();
  }
}
