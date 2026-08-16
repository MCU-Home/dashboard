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
import '../src/components/mh-build-steps';
import '../src/components/mh-commissioning';
import '../src/components/mh-device-list';
import '../src/components/mh-diagnostics';
import '../src/components/mh-new-device';
import '../src/components/mh-validity-badge';
import type { MhBuildPanel } from '../src/components/mh-build-panel';
import type { MhBuildSteps } from '../src/components/mh-build-steps';
import type { MhCommissioning } from '../src/components/mh-commissioning';
import type { MhDeviceList } from '../src/components/mh-device-list';
import type { MhDiagnostics } from '../src/components/mh-diagnostics';
import type { MhNewDevice } from '../src/components/mh-new-device';
import type { MhValidityBadge } from '../src/components/mh-validity-badge';
import type { BoardsResult, BuildRecord, BuildStep, DeviceEntry } from '../src/api/types';
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

  it('lists nothing when the project cannot be opened, whatever arrived with it', async () => {
    // The backend sends no devices for an unusable project; this asserts
    // the interface would not show them even if one slipped through,
    // because every action on such a device is refused anyway.
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.loaded = true;
      node.tree = {
        root: '/config',
        available: false,
        device_count: 1,
        problem: { code: 'project_upgrade_required', project_version: 0, expected_version: 1 },
      };
      node.devices = [device('bench-node', 'x')];
    });

    expect(element.shadowRoot?.querySelectorAll('li')).toHaveLength(0);
    expect(text(element)).toContain('cannot be opened');
    expect(text(element)).toContain('project version 0');
  });

  it('names who repairs an old project, and that differs by deployment', async () => {
    const tree = {
      root: '/config',
      available: false,
      device_count: 0,
      problem: { code: 'project_upgrade_required', project_version: 0, expected_version: 1 },
    } as const;

    const standalone = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.loaded = true;
      node.tree = { ...tree };
      node.ingress = false;
    });
    expect(text(standalone)).toContain('mcuhome project upgrade /config');
    expect(text(standalone)).not.toContain('Home Assistant App');

    const app = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.loaded = true;
      node.tree = { ...tree };
      node.ingress = true;
    });
    expect(text(app)).toContain('Home Assistant App');
    expect(text(app)).not.toContain('mcuhome project upgrade');
  });

  it('offers the page that explains the upgrade, and only where it helps', async () => {
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.loaded = true;
      node.tree = {
        root: '/config',
        available: false,
        device_count: 0,
        problem: { code: 'project_upgrade_required', project_version: 0, expected_version: 1 },
      };
    });

    const link = element.shadowRoot?.querySelector('a');
    expect(link?.getAttribute('href')).toBe(
      'https://t.mcuhome.org/dashboard/docs/project-upgrade/0.1/',
    );
    // Opening a documentation site must not hand it this window.
    expect(link?.getAttribute('rel')).toContain('noopener');
  });

  it('tells a project that is too new from one that is too old', async () => {
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.loaded = true;
      node.tree = {
        root: '/config',
        available: false,
        device_count: 0,
        problem: { code: 'project_version_unsupported', project_version: 2, expected_version: 1 },
      };
    });

    const said = text(element);
    expect(said).toContain('newer tools');
    expect(said).toContain('Update the dashboard');
    // Nothing here may suggest an upgrade: projects do not go backwards.
    expect(said).not.toContain('project upgrade');
  });

  it('says to wait while an upgrade holds the project', async () => {
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.loaded = true;
      node.tree = {
        root: '/config',
        available: false,
        device_count: 0,
        problem: { code: 'project_upgrading' },
      };
    });
    expect(text(element)).toContain('upgrade is working on this project');
  });

  it('falls back to something honest for a code it does not know', async () => {
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.loaded = true;
      node.tree = {
        root: '/config',
        available: false,
        device_count: 0,
        // What a newer backend inventing a reason looks like from here.
        problem: { code: 'something_the_future_added' },
      };
    });
    expect(text(element)).toContain('/config');
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
    steps: [],
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

  it('shows how far along a running build is, above its output', async () => {
    const element = await mount<MhBuildPanel>('mh-build-panel', (node) => {
      node.device = 'bench-node';
      node.record = record({
        state: 'running',
        finished: null,
        steps: [
          { key: 'validate', state: 'done', facts: { board: 'nrf7002dk/nrf5340/cpuapp' } },
          { key: 'context', state: 'done', facts: { sdk: '0.1.0' } },
          { key: 'compile', state: 'running', facts: {} },
        ],
      });
    });

    expect(element.shadowRoot?.querySelector('mh-build-steps')).not.toBeNull();
    expect(text(element)).toContain('Progress');
  });

  it('leaves the progress section out for a build that never reported one', async () => {
    const element = await mount<MhBuildPanel>('mh-build-panel', (node) => {
      node.device = 'bench-node';
      node.record = record();
    });

    expect(element.shadowRoot?.querySelector('mh-build-steps')).toBeNull();
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

describe('mh-build-steps', () => {
  const step = (overrides: Partial<BuildStep> = {}): BuildStep => ({
    key: 'compile',
    state: 'pending',
    facts: {},
    ...overrides,
  });

  it('shows every step, including the ones still to come', async () => {
    // Half of "how far along is this" is the part that has not happened.
    const element = await mount<MhBuildSteps>('mh-build-steps', (node) => {
      node.method = 'local';
      node.steps = [
        step({ key: 'validate', state: 'done' }),
        step({ key: 'context', state: 'done' }),
        step({ key: 'compile', state: 'running' }),
        step({ key: 'artifacts' }),
        step({ key: 'sign' }),
      ];
    });

    const items = [...(element.shadowRoot?.querySelectorAll('ol.bar li') ?? [])];
    expect(items).toHaveLength(5);
    expect(items[2]?.className).toBe('running');
    expect(items[3]?.className).toBe('pending');
  });

  it('says where the compile happens, from the method and not from a guess', async () => {
    const remote = await mount<MhBuildSteps>('mh-build-steps', (node) => {
      node.method = 'remote';
      node.steps = [step({ key: 'compile', state: 'running' }), step({ key: 'sign' })];
    });
    expect(text(remote)).toContain('build server');
    expect(text(remote)).toContain('(dashboard)');

    const local = await mount<MhBuildSteps>('mh-build-steps', (node) => {
      node.method = 'local';
      node.steps = [step({ key: 'compile', state: 'running' })];
    });
    expect(text(local)).toContain('build container');
  });

  it('states what the context turned out to be, once it knows', async () => {
    const element = await mount<MhBuildSteps>('mh-build-steps', (node) => {
      node.method = 'local';
      node.steps = [
        step({
          key: 'context',
          state: 'done',
          facts: {
            sdk: '0.1.0',
            zephyr: '4.4.0',
            patches: ['chip-pigweed.patch'],
            files: 214,
            id: 'sha256:0123456789abcdef0123456789abcdef',
          },
        }),
      ];
    });

    const shown = text(element);
    expect(shown).toContain('SDK 0.1.0');
    expect(shown).toContain('Zephyr 4.4.0');
    expect(shown).toContain('patches: chip-pigweed.patch');
    expect(shown).toContain('214 files');
    // Twelve digits of the hex, never the algorithm prefix.
    expect(shown).toContain('id 0123456789ab');
    expect(shown).not.toContain('sha256:');
  });

  it('says "no patches" rather than leaving the question open', async () => {
    const element = await mount<MhBuildSteps>('mh-build-steps', (node) => {
      node.steps = [step({ key: 'context', state: 'done', facts: { sdk: '0.1.0', patches: [] } })];
    });
    expect(text(element)).toContain('no patches');
  });

  it('renders a device with no network without inventing one', async () => {
    const element = await mount<MhBuildSteps>('mh-build-steps', (node) => {
      node.steps = [
        step({
          key: 'validate',
          state: 'done',
          facts: {
            board: 'nrf7002dk/nrf5340/cpuapp',
            transport: null,
            thread_role: null,
            matter: false,
            endpoints: 1,
            channels: 0,
          },
        }),
      ];
    });

    const shown = text(element);
    expect(shown).toContain('no network');
    expect(shown).toContain('Matter off');
    expect(shown).toContain('1 endpoint');
    expect(shown).toContain('0 channels');
  });

  it('skips a fact that arrived as the wrong kind of thing', async () => {
    // `facts` is another repository's append-only vocabulary. A line
    // describing a build must never be what stops it being rendered.
    const element = await mount<MhBuildSteps>('mh-build-steps', (node) => {
      node.steps = [
        step({
          key: 'context',
          state: 'done',
          facts: { sdk: 42, zephyr: '4.4.0', patches: 'not-a-list', files: 'many' },
        }),
      ];
    });

    const shown = text(element);
    expect(shown).toContain('Zephyr 4.4.0');
    expect(shown).not.toContain('42');
    expect(shown).not.toContain('not-a-list');
  });

  it('never looks a state up on a plain object', async () => {
    // `state` is typed Open<> on purpose — both sides of the vocabulary
    // may grow — so a value that happens to name an Object.prototype
    // member has to be as harmless as any other unknown one. Read with a
    // bare GLYPHS[state], "constructor" draws
    // `function Object() { [native code] }` into the bar, and the ??
    // fallback does not fire because what it found is truthy.
    for (const hostile of ['constructor', 'toString', 'hasOwnProperty', '__proto__']) {
      const element = await mount<MhBuildSteps>('mh-build-steps', (node) => {
        node.steps = [step({ key: 'compile', state: hostile })];
      });
      const item = element.shadowRoot?.querySelector('ol.bar li');
      expect(text(element)).not.toContain('native code');
      expect(item?.className).toBe('pending');
      expect(item?.getAttribute('aria-label')).toBe('Compile: unknown');
    }
  });

  it('labels a step it has never heard of by its own name', async () => {
    const element = await mount<MhBuildSteps>('mh-build-steps', (node) => {
      node.steps = [step({ key: 'generate', state: 'running' })];
    });
    expect(text(element)).toContain('generate');
  });

  it('renders nothing at all when there are no steps', async () => {
    const element = await mount<MhBuildSteps>('mh-build-steps', (node) => {
      node.steps = [];
    });
    expect(element.shadowRoot?.querySelector('ol.bar')).toBeNull();
  });
});

/**
 * The registry a wizard is made of, as one fixture.
 *
 * Deliberately *richer* than what MCUHome supports today: a second board
 * with no bus, a part on a bus only one of them breaks out, and a device
 * type whose cluster nothing measures. Those are the cases the form's
 * filtering exists for, and a fixture that mirrored the real registry
 * would not exercise any of them.
 */
const REGISTRY: BoardsResult = {
  boards: [
    {
      name: 'devkit-a',
      transports: ['thread'],
      buses: [{ kind: 'i2c', controller: 'header_i2c', description: 'Header I2C' }],
    },
    { name: 'devkit-b', transports: ['thread'], buses: [] },
  ],
  planned_boards: [{ name: 'devkit-c', reason: 'not brought up yet' }],
  drivers: [
    {
      compatible: 'acme,thermo',
      bus: 'i2c',
      channels: [
        { name: 'temperature', quantity: 'temperature' },
        { name: 'pressure', quantity: 'pressure' },
      ],
      fixed_address: 0x77,
    },
  ],
  planned_drivers: [],
  clusters: [
    { name: 'temperature_measurement', quantity: 'temperature', unit: '°C' },
    { name: 'humidity_measurement', quantity: 'humidity', unit: '%' },
  ],
  planned_clusters: [],
  device_types: [
    { name: 'temperature_sensor', mandatory_clusters: ['temperature_measurement'] },
    { name: 'humidity_sensor', mandatory_clusters: ['humidity_measurement'] },
  ],
  planned_device_types: [],
  registry_version: 1,
};

async function wizard(board = 'devkit-a'): Promise<MhNewDevice> {
  const element = await mount<MhNewDevice>('mh-new-device', (node) => {
    node.registry = REGISTRY;
  });
  set(element, 'wa-select', board);
  await element.updateComplete;
  return element;
}

/** Fire a value change on the nth matching control, the way the UI does. */
function set(element: HTMLElement, selector: string, value: string, index = 0): void {
  const node = [...(element.shadowRoot?.querySelectorAll(selector) ?? [])][index];
  if (node === undefined) throw new Error(`no ${selector} at ${index}`);
  (node as unknown as { value: string }).value = value;
  node.dispatchEvent(new Event('change'));
  node.dispatchEvent(new Event('input'));
}

function press(element: HTMLElement, label: string): void {
  const button = [...(element.shadowRoot?.querySelectorAll('wa-button') ?? [])].find((node) =>
    (node.textContent ?? '').includes(label),
  );
  if (button === undefined) throw new Error(`no button "${label}"`);
  button.dispatchEvent(new Event('click'));
}

describe('mh-new-device', () => {
  it('offers only parts the chosen board can carry', async () => {
    // `devkit-b` breaks out no bus, and the one driver needs I2C.
    const element = await wizard('devkit-b');
    expect(text(element)).toContain('breaks out no bus');

    element.registry = REGISTRY;
    set(element, 'wa-select', 'devkit-a');
    await element.updateComplete;
    expect(text(element)).not.toContain('breaks out no bus');
  });

  it('names what is planned instead of leaving it out', async () => {
    const element = await wizard();
    expect(text(element)).toContain('devkit-c — not brought up yet');
  });

  it('turns the picks into buses, parts and endpoints', async () => {
    const element = await wizard();
    press(element, 'Add a part');
    await element.updateComplete;
    press(element, 'Add an entry');
    await element.updateComplete;

    expect(element.outline()).toEqual({
      buses: [{ id: 'i2c0', controller: 'header_i2c' }],
      peripherals: [{ id: 'sensor', driver: 'acme,thermo', bus: 'i2c0' }],
      endpoints: [
        {
          device_type: 'temperature_sensor',
          clusters: [{ cluster: 'temperature_measurement', source: 'sensor.temperature' }],
        },
      ],
    });
  });

  it('offers no entry type nothing attached can feed', async () => {
    // `humidity_sensor` needs a humidity reading, and the one part
    // measures temperature and pressure. Offering it would produce a
    // configuration the builder rejects, for a choice this form made.
    const element = await wizard();
    press(element, 'Add a part');
    await element.updateComplete;
    press(element, 'Add an entry');
    await element.updateComplete;

    const options = [...(element.shadowRoot?.querySelectorAll('wa-option') ?? [])].map((node) =>
      node.getAttribute('value'),
    );
    expect(options).toContain('temperature_sensor');
    expect(options).not.toContain('humidity_sensor');
  });

  it('will not add an entry before there is anything to read from', async () => {
    const element = await wizard();
    expect(text(element)).toContain('Add a part above');
    expect(element.outline().endpoints).toEqual([]);
  });

  it('follows a renamed part rather than sending a dangling source', async () => {
    const element = await wizard();
    press(element, 'Add a part');
    await element.updateComplete;
    press(element, 'Add an entry');
    await element.updateComplete;

    // 0 is the device name, 1 its display name, 2 the part's.
    set(element, 'wa-input', 'baro', 2);
    await element.updateComplete;

    const outline = element.outline();
    expect(outline.peripherals[0]?.id).toBe('baro');
    expect(outline.endpoints[0]?.clusters[0]?.source).toBe('baro.temperature');
  });

  it('starts the hardware over when the board changes', async () => {
    // A part chosen for one board may not exist on the next, and an
    // entry reading from it would name nothing.
    const element = await wizard();
    press(element, 'Add a part');
    await element.updateComplete;
    expect(element.outline().peripherals).toHaveLength(1);

    set(element, 'wa-select', 'devkit-b');
    await element.updateComplete;
    expect(element.outline().peripherals).toEqual([]);
  });

  it('shows the part’s fixed address rather than asking for one', async () => {
    const element = await wizard();
    press(element, 'Add a part');
    await element.updateComplete;

    expect(text(element)).toContain('Fixed address 0x77');
    expect(element.outline().peripherals[0]?.address).toBeUndefined();
  });

  it('asks for the device before it can be created', async () => {
    const element = await wizard();
    const requested = vi.fn();
    element.addEventListener('device-requested', requested);

    // No name yet: the form must not send anything.
    element.shadowRoot?.querySelector('form')?.dispatchEvent(new Event('submit'));
    expect(requested).not.toHaveBeenCalled();

    set(element, 'wa-input', 'attic');
    await element.updateComplete;
    element.shadowRoot?.querySelector('form')?.dispatchEvent(new Event('submit'));

    expect(requested).toHaveBeenCalledOnce();
    const detail = (requested.mock.calls[0]?.[0] as CustomEvent).detail as { name: string };
    expect(detail.name).toBe('attic');
  });

  it('offers to open the device that is already there', async () => {
    const element = await mount<MhNewDevice>('mh-new-device', (node) => {
      node.registry = REGISTRY;
      node.conflict = 'bench-node';
    });
    const opened = vi.fn();
    element.addEventListener('open-device', opened);

    expect(text(element)).toContain('already a device called "bench-node"');
    press(element, 'Open it');
    expect(opened).toHaveBeenCalledOnce();
  });

  it('waits for the registry rather than offering an empty picker', async () => {
    const element = await mount<MhNewDevice>('mh-new-device', () => {});
    expect(element.shadowRoot?.querySelector('wa-spinner')).not.toBeNull();
    expect(element.shadowRoot?.querySelector('form')).toBeNull();
  });
});

describe('mh-commissioning: drawing the identity', () => {
  async function revealed(apply: (node: MhCommissioning) => void): Promise<MhCommissioning> {
    const element = await mount<MhCommissioning>('mh-commissioning', (node) => {
      node.device = 'bench-node';
      apply(node);
    });
    press(element, 'Show commissioning codes');
    await element.updateComplete;
    return element;
  }

  it('offers to draw them when the device has none', async () => {
    const element = await revealed((node) => {
      node.codes = null;
      node.answered = true;
    });
    const requested = vi.fn();
    element.addEventListener('pairing-requested', requested);

    press(element, 'Draw commissioning codes');
    expect(requested).toHaveBeenCalledOnce();
    expect((requested.mock.calls[0]?.[0] as CustomEvent).detail).toEqual({ force: false });
  });

  it('offers to draw them even while the configuration does not resolve', async () => {
    // A device that was just created cannot resolve *because* it has no
    // credentials, so this is the case the button matters most in.
    const element = await revealed((node) => {
      node.errors = [
        {
          message: 'This device has no commissioning credentials.',
          file: null,
          line: null,
          column: null,
          key: null,
          hint: null,
          kind: 'config',
        },
      ];
      node.answered = true;
    });
    expect(text(element)).toContain('Draw commissioning codes');
  });

  it('does not replace an identity without a second, explicit word', async () => {
    const element = await revealed((node) => {
      node.codes = CODES;
      node.answered = true;
    });
    const requested = vi.fn();
    element.addEventListener('pairing-requested', requested);

    press(element, 'Draw new codes');
    await element.updateComplete;
    expect(requested).not.toHaveBeenCalled();
    expect(text(element)).toContain('has to commission it again');

    press(element, 'Replace them');
    expect((requested.mock.calls[0]?.[0] as CustomEvent).detail).toEqual({ force: true });
  });

  it('lets the replacement be called off', async () => {
    const element = await revealed((node) => {
      node.codes = CODES;
      node.answered = true;
    });
    const requested = vi.fn();
    element.addEventListener('pairing-requested', requested);

    press(element, 'Draw new codes');
    await element.updateComplete;
    press(element, 'Keep the current ones');
    await element.updateComplete;

    expect(requested).not.toHaveBeenCalled();
    expect(text(element)).toContain('Draw new codes');
  });

  it('says where the values went, and never what they are', async () => {
    const element = await revealed((node) => {
      node.codes = null;
      node.answered = true;
      node.drawn = 'Written to secrets/devices/bench-node.yaml. That file is the only copy.';
    });
    const shown = text(element);
    expect(shown).toContain('secrets/devices/bench-node.yaml');
    expect(shown).not.toContain(CODES.manual_code);
    expect(shown).not.toContain(CODES.qr_payload);
  });
});

describe('mh-device-list: creating one', () => {
  it('asks for a new device rather than naming a command', async () => {
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.devices = [];
      node.loaded = true;
      node.tree = { root: '/config', available: true, device_count: 0 };
    });
    const requested = vi.fn();
    element.addEventListener('new-device', requested);

    press(element, 'New device');
    expect(requested).toHaveBeenCalledOnce();
  });

  it('keeps the action reachable once devices exist', async () => {
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.devices = [
        {
          name: 'bench-node',
          entry: 'devices/bench-node/main.yaml',
          content_hash: 'h',
          modified: 0,
          size: 0,
          summary: {},
        },
      ];
      node.loaded = true;
      node.tree = { root: '/config', available: true, device_count: 1 };
    });

    expect(text(element)).toContain('New device');
  });

  it('offers nothing to create when the project cannot be opened', async () => {
    const element = await mount<MhDeviceList>('mh-device-list', (node) => {
      node.devices = [];
      node.loaded = true;
      node.tree = {
        root: '/config',
        available: false,
        device_count: 0,
        problem: { code: 'project_upgrade_required', project_version: 0, expected_version: 1 },
      };
    });

    expect(text(element)).not.toContain('New device');
  });
});
