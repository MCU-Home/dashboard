// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Snapshot-then-events on the client side (ADR 0004 decision 3).
 *
 * The property that matters is that the store is a *function of what it
 * received* — and that it says so when it stops being one, which is what
 * `events_dropped` means.
 */

import { describe, expect, it, vi } from 'vitest';

import { DeviceStore } from '../src/state/device-store';
import type { DeviceEntry, TreeState } from '../src/api/types';

function device(name: string, hash = 'h1'): DeviceEntry {
  return {
    name,
    entry: `devices/${name}/main.yaml`,
    content_hash: hash,
    modified: 0,
    size: 0,
    summary: { name, board: 'nrf7002dk/nrf5340/cpuapp', endpoint_count: 1 },
  };
}

const TREE: TreeState = { root: '/config', available: true, device_count: 2 };

describe('the snapshot', () => {
  it('is unknown before the first one arrives', () => {
    const store = new DeviceStore();
    expect(store.loaded).toBe(false);
    expect(store.devices).toEqual([]);
  });

  it('replaces everything, so a reconnect cannot leave a ghost behind', () => {
    const store = new DeviceStore();
    store.setSnapshot([device('a'), device('b')], TREE);
    store.setSnapshot([device('b')], TREE);

    expect(store.devices.map((entry) => entry.name)).toEqual(['b']);
  });

  it('is name-ordered, matching the order the server lists them in', () => {
    const store = new DeviceStore();
    store.setSnapshot([device('zeta'), device('alpha'), device('mid')], TREE);
    expect(store.devices.map((entry) => entry.name)).toEqual(['alpha', 'mid', 'zeta']);
  });
});

describe('events', () => {
  it('adds, replaces and removes', () => {
    const store = new DeviceStore();
    store.setSnapshot([device('a')], TREE);

    store.applyEvent('device_added', { device: device('b') });
    expect(store.devices.map((entry) => entry.name)).toEqual(['a', 'b']);

    store.applyEvent('device_changed', { device: device('a', 'h2') });
    expect(store.get('a')?.content_hash).toBe('h2');

    store.applyEvent('device_removed', { name: 'a' });
    expect(store.get('a')).toBeUndefined();
  });

  it('reports whether it changed anything', () => {
    const store = new DeviceStore();
    store.setSnapshot([], TREE);
    expect(store.applyEvent('device_removed', { name: 'ghost' })).toBe(false);
    expect(store.applyEvent('device_added', { device: device('a') })).toBe(true);
  });

  it('follows the tree becoming unavailable', () => {
    const store = new DeviceStore();
    store.setSnapshot([device('a')], TREE);
    store.applyEvent('tree_state', { root: '/config', available: false });
    expect(store.tree).toMatchObject({ available: false, root: '/config' });
  });

  it('ignores an event this build has never heard of', () => {
    // A newer backend is not a reason to stop rendering the list.
    const store = new DeviceStore();
    store.setSnapshot([device('a')], TREE);
    expect(store.applyEvent('job_state_changed', { job: 7 })).toBe(false);
    expect(store.devices).toHaveLength(1);
  });

  it('ignores a malformed payload rather than storing a nameless device', () => {
    const store = new DeviceStore();
    store.setSnapshot([], TREE);
    expect(store.applyEvent('device_added', { device: { entry: 'x' } })).toBe(false);
    expect(store.devices).toHaveLength(0);
  });

  it('marks itself stale when the server discarded events for this connection', () => {
    const store = new DeviceStore();
    store.setSnapshot([device('a')], TREE);
    expect(store.stale).toBe(false);

    store.applyEvent('events_dropped', { count: 4, total: 4 });
    expect(store.stale).toBe(true);
  });

  it('is no longer stale after a fresh snapshot', () => {
    const store = new DeviceStore();
    store.setSnapshot([device('a')], TREE);
    store.applyEvent('events_dropped', { count: 1, total: 1 });
    store.setSnapshot([device('a')], TREE);
    expect(store.stale).toBe(false);
  });
});

describe('listeners', () => {
  it('fire on every change and stop when unsubscribed', () => {
    const store = new DeviceStore();
    const listener = vi.fn();
    const stop = store.subscribe(listener);

    store.setSnapshot([device('a')], TREE);
    store.applyEvent('device_added', { device: device('b') });
    expect(listener).toHaveBeenCalledTimes(2);

    stop();
    store.applyEvent('device_removed', { name: 'b' });
    expect(listener).toHaveBeenCalledTimes(2);
  });
});

describe('reset', () => {
  it('goes back to knowing nothing, which is what a disconnect leaves', () => {
    const store = new DeviceStore();
    store.setSnapshot([device('a')], TREE);
    store.reset();

    expect(store.loaded).toBe(false);
    expect(store.devices).toEqual([]);
    expect(store.tree).toBeNull();
  });
});
