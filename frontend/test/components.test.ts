// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Smoke tests for the components whose *behaviour* is not just markup.
 *
 * Web Awesome's own elements are deliberately not registered here — this
 * suite does not test their library — so `wa-*` tags render as inert
 * unknown elements and the assertions are about what our components put
 * around them.
 *
 * The one that earns its place is the commissioning gate: it must not
 * render a passcode until somebody asked.
 */

import { describe, expect, it, vi } from 'vitest';

import '../src/components/mh-build-panel';
import '../src/components/mh-commissioning';
import '../src/components/mh-device-list';
import '../src/components/mh-diagnostics';
import '../src/components/mh-validity-badge';
import type { MhBuildPanel } from '../src/components/mh-build-panel';
import type { MhCommissioning } from '../src/components/mh-commissioning';
import type { MhDeviceList } from '../src/components/mh-device-list';
import type { MhDiagnostics } from '../src/components/mh-diagnostics';
import type { MhValidityBadge } from '../src/components/mh-validity-badge';
import type { BuildRecord, DeviceEntry } from '../src/api/types';
import { WsClient } from '../src/api/client';
import { flush, socketRecorder } from './helpers';

async function mount<T extends HTMLElement>(tag: string, apply: (element: T) => void): Promise<T> {
  const element = document.createElement(tag) as T;
  apply(element);
  document.body.append(element);
  await (element as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  return element;
}

function text(element: HTMLElement): string {
  return element.shadowRoot?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
}

const CODES = {
  qr_payload: 'MT:Y.K9042C00KA0648G00',
  manual_code: '34970112332',
  discriminator: 3840,
  test_credentials: true,
};

describe('mh-commissioning', () => {
  it('shows no codes at all until the user asks', async () => {
    const element = await mount<MhCommissioning>('mh-commissioning', (node) => {
      node.device = 'bench-node';
      node.codes = CODES;
      node.answered = true;
    });

    // The codes are a property already, and still nowhere in the DOM.
    expect(text(element)).not.toContain(CODES.manual_code);
    expect(element.shadowRoot?.querySelector('wa-qr-code')).toBeNull();
  });

  it('asks the page for the codes when the button is pressed', async () => {
    const element = await mount<MhCommissioning>('mh-commissioning', (node) => {
      node.device = 'bench-node';
    });
    const requested = vi.fn();
    element.addEventListener('commissioning-requested', requested);

    element.shadowRoot?.querySelector('wa-button')?.dispatchEvent(new Event('click'));
    await element.updateComplete;

    expect(requested).toHaveBeenCalledOnce();
  });

  it('renders the QR payload and the manual code once revealed', async () => {
    const element = await mount<MhCommissioning>('mh-commissioning', (node) => {
      node.device = 'bench-node';
      node.codes = CODES;
      node.answered = true;
    });
    element.shadowRoot?.querySelector('wa-button')?.dispatchEvent(new Event('click'));
    await element.updateComplete;

    expect(element.shadowRoot?.querySelector('wa-qr-code')?.getAttribute('value')).toBe(
      CODES.qr_payload,
    );
    expect(text(element)).toContain(CODES.manual_code);
  });

  it('says so when the credentials are the published test ones', async () => {
    const element = await mount<MhCommissioning>('mh-commissioning', (node) => {
      node.device = 'bench-node';
      node.codes = CODES;
      node.answered = true;
    });
    element.shadowRoot?.querySelector('wa-button')?.dispatchEvent(new Event('click'));
    await element.updateComplete;

    expect(text(element)).toContain('bench use only');
  });

  it('hides the codes again when the device changes', async () => {
    const element = await mount<MhCommissioning>('mh-commissioning', (node) => {
      node.device = 'bench-node';
      node.codes = CODES;
      node.answered = true;
    });
    element.shadowRoot?.querySelector('wa-button')?.dispatchEvent(new Event('click'));
    await element.updateComplete;
    expect(text(element)).toContain(CODES.manual_code);

    element.device = 'other-node';
    await element.updateComplete;
    expect(text(element)).not.toContain(CODES.manual_code);
  });

  it('explains a device that has nothing to commission', async () => {
    const element = await mount<MhCommissioning>('mh-commissioning', (node) => {
      node.device = 'plain-node';
      node.codes = null;
      node.answered = true;
    });
    element.shadowRoot?.querySelector('wa-button')?.dispatchEvent(new Event('click'));
    await element.updateComplete;

    expect(text(element)).toContain('no Matter commissioning credentials');
  });
});

describe('mh-device-list', () => {
  const device = (name: string, board: string | null): DeviceEntry => ({
    name,
    entry: `devices/${name}/main.yaml`,
    content_hash: 'h',
    modified: 0,
    size: 0,
    summary: { name, board, endpoint_count: 2 },
  });

  it('shows a row per device, with its board', async () => {
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.loaded = true;
      node.tree = { root: '/config', available: true, device_count: 2 };
      node.devices = [device('bench-node', 'nrf7002dk/nrf5340/cpuapp'), device('other', null)];
    });

    expect(element.shadowRoot?.querySelectorAll('li')).toHaveLength(2);
    expect(text(element)).toContain('nrf7002dk/nrf5340/cpuapp');
    expect(text(element)).toContain('no board set');
  });

  it('links each row to its device page', async () => {
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.loaded = true;
      node.tree = { root: '/config', available: true, device_count: 1 };
      node.devices = [device('bench-node', 'x')];
    });

    expect(element.shadowRoot?.querySelector('a')?.getAttribute('href')).toBe(
      '#/devices/bench-node',
    );
  });

  it('carries a validity badge per row', async () => {
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.loaded = true;
      node.tree = { root: '/config', available: true, device_count: 1 };
      node.devices = [device('bench-node', 'x')];
      node.validityOf = () => ({ status: 'invalid', errorCount: 2, errors: [] });
    });

    const badge = element.shadowRoot?.querySelector('mh-validity-badge') as MhValidityBadge | null;
    await badge?.updateComplete;
    expect(text(badge!)).toContain('2 problems');
  });

  it('tells an empty tree apart from one that has not loaded', async () => {
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.loaded = false;
      node.tree = null;
      node.devices = [];
    });
    expect(text(element)).toContain('Loading');

    element.loaded = true;
    element.tree = { root: '/config', available: true, device_count: 0 };
    await element.updateComplete;
    expect(text(element)).toContain('No devices yet');
  });

  it('says when there is no configuration tree at all', async () => {
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.loaded = true;
      node.tree = { root: '/nope', available: false, device_count: 0 };
      node.devices = [];
    });
    expect(text(element)).toContain('not an MCUHome configuration tree');
  });
});

describe('mh-validity-badge', () => {
  it.each([
    ['ok', 'valid'],
    ['checking', 'checking'],
    ['unknown', 'not checked'],
    // A command that failed says nothing about the configuration, so it
    // must not be rendered as a problem.
    ['failed', 'not checked'],
  ] as const)('renders %s as "%s"', async (status, expected) => {
    const element = await mount<MhValidityBadge>('mh-validity-badge', (node) => {
      node.validity = { status, errorCount: 0, errors: [] };
    });
    expect(text(element)).toContain(expected);
  });
});

describe('mh-diagnostics', () => {
  const diagnostic = {
    message: 'This board does not exist.',
    file: 'devices/broken/main.yaml',
    line: 3,
    column: 10,
    key: 'device.board',
    hint: 'Run `mcuhome boards`.',
    kind: 'ConfigError',
  };

  it('shows the message, the position, the key and the hint', async () => {
    const element = await mount<MhDiagnostics>('mh-diagnostics', (node) => {
      node.diagnostics = [diagnostic];
    });
    const rendered = text(element);

    expect(rendered).toContain('This board does not exist.');
    expect(rendered).toContain('line 3');
    expect(rendered).toContain('device.board');
    expect(rendered).toContain('Run `mcuhome boards`.');
  });

  it('asks the page to reveal a diagnostic when its row is clicked', async () => {
    const element = await mount<MhDiagnostics>('mh-diagnostics', (node) => {
      node.diagnostics = [diagnostic];
    });
    const selected = vi.fn();
    element.addEventListener('diagnostic-selected', selected);

    element.shadowRoot?.querySelector('button')?.click();
    expect(selected).toHaveBeenCalledOnce();
  });

  it('says so when there is nothing wrong', async () => {
    const element = await mount<MhDiagnostics>('mh-diagnostics', (node) => {
      node.diagnostics = [];
    });
    expect(text(element)).toContain('No problems found');
  });
});

describe('mh-build-panel', () => {
  const record = (overrides: Partial<BuildRecord> = {}): BuildRecord => ({
    id: 'b1',
    device: 'bench-node',
    method: 'local',
    state: 'succeeded',
    started: 1000,
    finished: 1075,
    context_id: 'ctx',
    image: 'builder:r6',
    status: 'success',
    errors: [],
    artifacts: [],
    signing: null,
    ota: null,
    log_first_offset: 0,
    log_next_offset: 0,
    ...overrides,
  });

  it('offers a build and nothing else for a device never built', async () => {
    const element = await mount<MhBuildPanel>('mh-build-panel', (node) => {
      node.device = 'bench-node';
      node.defaultMethod = 'local';
    });

    expect(text(element)).toContain('has not been built');
    expect(text(element)).toContain('local');
    expect(element.shadowRoot?.querySelector('.log')).toBeNull();
  });

  it('shows the state, the method and how long it took', async () => {
    const element = await mount<MhBuildPanel>('mh-build-panel', (node) => {
      node.device = 'bench-node';
      node.record = record();
    });

    expect(text(element)).toContain('Succeeded');
    expect(text(element)).toContain('Method: local');
    expect(text(element)).toContain('1 min 15 s');
  });

  it('refuses to start a second build while one of this device runs', async () => {
    const element = await mount<MhBuildPanel>('mh-build-panel', (node) => {
      node.device = 'bench-node';
      node.record = record({ state: 'running', finished: null });
    });

    expect(element.active).toBe(true);
    const buttons = [...(element.shadowRoot?.querySelectorAll('wa-button') ?? [])];
    const start = buttons.at(-1);
    expect(start?.hasAttribute('disabled')).toBe(true);
    // …and offers to stop the one that is running instead.
    expect(text(element)).toContain('Cancel build');
  });

  it('renders the output as one monospace block', async () => {
    const element = await mount<MhBuildPanel>('mh-build-panel', (node) => {
      node.device = 'bench-node';
      node.record = record({ state: 'running', finished: null });
      node.lines = ['-- west build --', 'Memory region  Used Size'];
    });

    expect(element.shadowRoot?.querySelector('.log pre')?.textContent).toBe(
      '-- west build --\nMemory region  Used Size',
    );
  });

  it('says when the beginning of the output is gone', async () => {
    const element = await mount<MhBuildPanel>('mh-build-panel', (node) => {
      node.device = 'bench-node';
      node.record = record();
      node.lines = ['…'];
      node.truncated = true;
    });

    expect(text(element)).toContain('earlier output was discarded');
  });

  it('renders a failed build on the diagnostics component, not a second one', async () => {
    const element = await mount<MhBuildPanel>('mh-build-panel', (node) => {
      node.device = 'bench-node';
      node.record = record({
        state: 'failed',
        status: 'failure',
        errors: [
          {
            message: 'This board does not exist.',
            file: 'devices/bench-node/main.yaml',
            line: 3,
            column: 1,
            key: 'device.board',
            hint: 'Run `mcuhome boards`.',
            kind: 'ConfigError',
          },
        ],
      });
    });

    const diagnostics = element.shadowRoot?.querySelector('mh-diagnostics') as MhDiagnostics | null;
    await diagnostics?.updateComplete;
    expect(text(diagnostics!)).toContain('This board does not exist.');
  });

  it('links each artifact relative, so the injected base resolves it', async () => {
    const element = await mount<MhBuildPanel>('mh-build-panel', (node) => {
      node.device = 'bench-node';
      node.record = record({
        artifacts: [
          { role: 'firmware', path: 'zephyr.bin', size: 812_345, signed: false },
          { role: 'firmware-signed', path: 'zephyr.signed.bin', size: 812_600, signed: true },
        ],
      });
    });

    const links = [...(element.shadowRoot?.querySelectorAll('a') ?? [])];
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      'api/builds/b1/artifacts/zephyr.bin',
      'api/builds/b1/artifacts/zephyr.signed.bin',
    ]);
    expect(links[0]?.hasAttribute('download')).toBe(true);
    expect(text(element)).toContain('793.3 KiB');
    expect(text(element)).toContain('signed');
  });

  it('warns that a freshly created signing key has to be backed up', async () => {
    const element = await mount<MhBuildPanel>('mh-build-panel', (node) => {
      node.device = 'bench-node';
      node.record = record({
        signing: { signed: true, created_key: true, outputs: ['zephyr.signed.bin'] },
      });
    });

    expect(text(element)).toContain('A firmware signing key was created');
    expect(text(element)).toContain('Back it up');
  });

  it('says why a start was refused, in the words the holder deserves', async () => {
    // The one build slot belongs to the *work*, not to the record: a
    // cancel stops the dashboard waiting, not the container it started
    // (ADR 0013 decisions 3 and 7). So a refusal can name a build that
    // already reads `cancelled`, and calling that "already running"
    // would contradict the state the same answer carries.
    const recorder = socketRecorder();
    const client = new WsClient({
      url: () => 'ws://test/ws',
      socketFactory: recorder.factory,
      probe: () => Promise.resolve(false),
    });
    client.connect();
    recorder.sockets[0]!.open();

    const element = await mount<MhBuildPanel>('mh-build-panel', (node) => {
      node.device = 'bench-node';
      node.client = client;
    });

    const start = [...(element.shadowRoot?.querySelectorAll('wa-button') ?? [])].at(-1);
    start?.dispatchEvent(new Event('click'));
    await flush();

    const socket = recorder.sockets[0]!;
    const command = socket.commands.at(-1);
    expect(command?.type).toBe('build/start');
    socket.receive({
      id: command!.id,
      type: 'error',
      error: {
        code: 'conflict',
        message: 'The build of "kitchen" was cancelled, but the work it started has not ended.',
        build: record({ id: 'b0', device: 'kitchen', state: 'cancelled' }),
      },
    });
    await flush();
    await element.updateComplete;

    expect(text(element)).toContain('was cancelled');
    expect(text(element)).not.toContain('is already running');
  });

  it('names the OTA image when the device can take one', async () => {
    const element = await mount<MhBuildPanel>('mh-build-panel', (node) => {
      node.device = 'bench-node';
      node.record = record({
        ota: { path: 'bench-node-1.0.0.ota', version: '1.0.0', software_version: 1 },
      });
    });

    expect(text(element)).toContain('Matter OTA image for version 1.0.0');
  });
});
