// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest';

import { deviceHref, parseRoute } from '../src/router';

describe('parseRoute', () => {
  it.each(['', '#', '#/', '#/devices'])('sends %s to the device list', (hash) => {
    expect(parseRoute(hash)).toEqual({ view: 'devices' });
  });

  it('reads a device name', () => {
    expect(parseRoute('#/devices/bench-node')).toEqual({ view: 'device', name: 'bench-node' });
  });

  it('tolerates a trailing slash', () => {
    expect(parseRoute('#/devices/bench-node/')).toEqual({ view: 'device', name: 'bench-node' });
  });

  it('decodes a name that needed escaping', () => {
    expect(parseRoute('#/devices/living%20room')).toEqual({ view: 'device', name: 'living room' });
  });

  it('does not invent a route for a path it does not know', () => {
    expect(parseRoute('#/builds/17')).toEqual({ view: 'not-found', path: '/builds/17' });
  });
});

describe('deviceHref', () => {
  it('round-trips through parseRoute', () => {
    const name = 'kitchen sensor/2';
    expect(parseRoute(deviceHref(name))).toEqual({ view: 'device', name });
  });

  it('stays a fragment, so no prefix is ever needed', () => {
    expect(deviceHref('a')).toMatch(/^#/);
  });
});
