// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The device list, held as snapshot-then-events.
 *
 * ADR 0004 decision 3, on the client side: the subscription's *result*
 * is the current state and every later change arrives as an event on the
 * same socket. There is no polling and no re-fetch here on purpose —
 * ESPHome documented read-once-and-refetch as the source of the races
 * they rebuilt their dashboard to get rid of.
 *
 * The one case that does re-fetch is `events_dropped`: the server has
 * told this connection that it fell so far behind that events were
 * discarded for it, so what is held is no longer a function of what was
 * received. {@link DeviceStore.stale} says so, and the app takes a new
 * snapshot rather than pretending otherwise.
 */

import type { DeviceEntry, TreeProblem, TreeState } from '../api/types';

export type StoreListener = () => void;

export class DeviceStore {
  #devices = new Map<string, DeviceEntry>();
  #tree: TreeState | null = null;
  #stale = false;
  #loaded = false;
  readonly #listeners = new Set<StoreListener>();

  /** Every device, name-ordered — the order the server lists them in. */
  get devices(): DeviceEntry[] {
    return [...this.#devices.values()].sort((left, right) => left.name.localeCompare(right.name));
  }

  get tree(): TreeState | null {
    return this.#tree;
  }

  /** False until the first snapshot, so a view can tell empty from unknown. */
  get loaded(): boolean {
    return this.#loaded;
  }

  /** True when the server discarded events for this connection. */
  get stale(): boolean {
    return this.#stale;
  }

  get(name: string): DeviceEntry | undefined {
    return this.#devices.get(name);
  }

  subscribe(listener: StoreListener): () => void {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }

  /** Replace everything with a fresh snapshot. */
  setSnapshot(devices: DeviceEntry[], tree: TreeState | null): void {
    this.#devices = new Map(devices.map((device) => [device.name, device]));
    this.#tree = tree;
    this.#stale = false;
    this.#loaded = true;
    this.#notify();
  }

  /** Forget everything — what a disconnect leaves behind. */
  reset(): void {
    this.#devices.clear();
    this.#tree = null;
    this.#stale = false;
    this.#loaded = false;
    this.#notify();
  }

  /**
   * Apply one event frame. Returns true when it changed anything.
   *
   * Unknown event names are ignored rather than refused: a newer backend
   * publishing an event this build has never heard of is not a reason to
   * stop rendering the list.
   */
  applyEvent(event: string, payload: Record<string, unknown>): boolean {
    switch (event) {
      case 'device_added':
      case 'device_changed': {
        const device = payload.device as DeviceEntry | undefined;
        if (device === undefined || typeof device.name !== 'string') return false;
        this.#devices.set(device.name, device);
        this.#notify();
        return true;
      }
      case 'device_removed': {
        const name = payload.name;
        if (typeof name !== 'string' || !this.#devices.delete(name)) return false;
        this.#notify();
        return true;
      }
      case 'tree_state': {
        const root = (payload.root ?? null) as string | null;
        const available = payload.available === true;
        const problem = (payload.problem ?? null) as TreeProblem | null;
        this.#tree = { root, available, device_count: this.#devices.size, problem };
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

  #notify(): void {
    for (const listener of this.#listeners) listener();
  }
}
