// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The prefix problem, which is the one thing every deployment gets a
 * different answer to (ADR 0005 decision 4).
 */

import { describe, expect, it } from 'vitest';

import { basePath, httpUrl, webSocketUrl } from '../src/base-path';

function fakeWindow(base: unknown, location = { protocol: 'http:', host: 'ha.local:8123' }) {
  return { MCUHOME_BASE_PATH: base, location } as unknown as Window;
}

describe('basePath', () => {
  it('is empty in a standalone deployment, which makes every URL root-relative', () => {
    expect(basePath(fakeWindow(''))).toBe('');
    expect(basePath(fakeWindow(undefined))).toBe('');
  });

  it('treats a bare slash as no prefix at all', () => {
    expect(basePath(fakeWindow('/'))).toBe('');
  });

  it('keeps an ingress prefix and drops its trailing slash', () => {
    expect(basePath(fakeWindow('/api/hassio_ingress/abc123/'))).toBe('/api/hassio_ingress/abc123');
  });

  it('survives a prefix that lost its leading slash somewhere', () => {
    expect(basePath(fakeWindow('proxy/mcuhome'))).toBe('/proxy/mcuhome');
  });

  it('ignores anything that is not a string', () => {
    expect(basePath(fakeWindow(42))).toBe('');
  });
});

describe('httpUrl', () => {
  it('is root-relative without a prefix', () => {
    expect(httpUrl('/health', fakeWindow(''))).toBe('/health');
  });

  it('carries the prefix', () => {
    expect(httpUrl('/auth/login', fakeWindow('/api/hassio_ingress/abc'))).toBe(
      '/api/hassio_ingress/abc/auth/login',
    );
  });

  it('does not care whether the caller wrote the leading slash', () => {
    expect(httpUrl('health', fakeWindow('/x'))).toBe('/x/health');
  });
});

describe('webSocketUrl', () => {
  it('is absolute, because `new WebSocket()` ignores <base>', () => {
    expect(webSocketUrl(fakeWindow('/api/hassio_ingress/abc'))).toBe(
      'ws://ha.local:8123/api/hassio_ingress/abc/ws',
    );
  });

  it('follows the page onto TLS', () => {
    const win = fakeWindow('', { protocol: 'https:', host: 'dash.example.org' });
    expect(webSocketUrl(win)).toBe('wss://dash.example.org/ws');
  });
});
