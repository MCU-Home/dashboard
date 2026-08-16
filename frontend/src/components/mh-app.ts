// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The shell: one connection, the stores, one route, one theme.
 *
 * Everything with a lifetime longer than a view lives here, because ADR
 * 0004's "one connection carries the entire UI" only holds if there is
 * exactly one owner of it. Views are given what they need as properties
 * and own nothing — a build in particular outlives the page that started
 * it, so the store holding it cannot belong to that page.
 *
 * The reconnect contract is the part worth reading. Subscriptions are
 * per socket on the server and die with it, so `onConnected` — which
 * fires on every open, first or fiftieth — re-subscribes to *both*
 * topics and takes fresh snapshots rather than trying to repair what it
 * was holding. The same path handles `events_dropped`, where the server
 * has said in so many words that what this client holds is no longer a
 * function of what it received.
 *
 * Build output is the one thing a fresh snapshot does not restore: the
 * records come back whole, but the lines that were printed while this
 * connection was away were only ever events. That is what the log
 * offsets are for, and `#refillLogs` is the other half of them — after
 * any resubscribe, and after any batch that arrived past what the store
 * holds, the log is refetched from the offset the store already has.
 */

import { css, html, LitElement } from 'lit';
import { customElement, state } from 'lit/decorators.js';

import { WsClient } from '../api/client';
import type { ConnectionState } from '../api/client';
import {
  buildSubscribe,
  configSubscribe,
  deviceBoards,
  deviceNew,
  serverInfo,
} from '../api/commands';
import { CommandError } from '../api/protocol';
import { logout, serverReachable } from '../api/session';
import type { BoardsResult, DeviceOutline, ServerInfo } from '../api/types';
import { deviceHref, watchRoute } from '../router';
import type { Route } from '../router';
import { BuildStore, coalesced, fillLogGaps } from '../state/build-store';
import { DeviceStore } from '../state/device-store';
import type { ResolvedTheme, ThemePreference } from '../state/theme';
import {
  applyTheme,
  readPreference,
  resolveTheme,
  watchSystemTheme,
  writePreference,
} from '../state/theme';
import { ValidityTracker } from '../state/validity';
import { t } from '../strings';
import { sharedStyles } from '../styles/shared';

import './mh-connection-status';
import './mh-device-list';
import './mh-device-page';
import './mh-login';
import './mh-new-device';

/** One shared empty array, so a device with no build re-renders nothing. */
const NO_LINES: readonly string[] = Object.freeze([]);

function messageOf(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

function codeOf(cause: unknown): string | null {
  return cause instanceof CommandError ? cause.code : null;
}

@customElement('mh-app')
export class MhApp extends LitElement {
  static override styles = [
    sharedStyles,
    css`
      :host {
        display: block;
        min-height: 100vh;
        background: var(--wa-color-surface-lowered);
      }

      .frame {
        max-width: 60rem;
        margin: 0 auto;
        padding: var(--wa-space-l) var(--wa-space-m) var(--wa-space-2xl);
        display: flex;
        flex-direction: column;
        min-height: 100vh;
        gap: var(--wa-space-l);
      }

      header.bar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--wa-space-m);
        flex-wrap: wrap;
      }

      .brand {
        font-size: var(--wa-font-size-l);
        font-weight: var(--wa-font-weight-semibold);
        text-decoration: none;
        color: inherit;
      }

      main {
        flex: 1 1 auto;
      }

      footer {
        border-top: 1px solid var(--wa-color-surface-border);
        padding-top: var(--wa-space-m);
        font-size: var(--wa-font-size-s);
        color: var(--wa-color-text-quiet);
        display: flex;
        gap: var(--wa-space-l);
        flex-wrap: wrap;
      }

      footer .mono {
        color: var(--wa-color-text-normal);
      }
    `,
  ];

  readonly #store = new DeviceStore();
  readonly #builds = new BuildStore();
  readonly #client = new WsClient({ probe: serverReachable });
  readonly #validity = new ValidityTracker(this.#client);
  readonly #cleanup: (() => void)[] = [];

  /** Both stores see `events_dropped`; one resubscribe answers it. */
  #resubscribing = false;

  @state() private connection: ConnectionState = 'closed';
  @state() private route: Route = { view: 'devices' };
  @state() private info: ServerInfo | null = null;
  @state() private themePreference: ThemePreference = 'system';
  @state() private theme: ResolvedTheme = 'light';
  /** The new-device form, when it is open. */
  @state() private creating = false;
  @state() private registry: BoardsResult | null = null;
  @state() private createBusy = false;
  @state() private createFailure: string | null = null;
  @state() private createConflict: string | null = null;
  /** Bumped whenever the store or the validity tracker changed. */
  @state() private revision = 0;

  override connectedCallback(): void {
    super.connectedCallback();

    this.themePreference = readPreference();
    this.#applyTheme();

    this.#cleanup.push(
      watchSystemTheme(() => {
        if (this.themePreference === 'system') this.#applyTheme();
      }),
      watchRoute((route) => {
        this.route = route;
        // A device page just opened wants its build's log, which nothing
        // has fetched while it was not on screen.
        this.#refillLogs();
      }),
      this.#store.subscribe(() => this.#onStoreChanged()),
      this.#builds.subscribe(() => this.#onBuildsChanged()),
      this.#validity.subscribe(() => {
        this.revision += 1;
      }),
      this.#client.onState((state) => {
        this.connection = state;
      }),
      this.#client.onEvent((frame) => this.#onEvent(frame.event, frame.payload)),
      this.#client.onConnected(() => void this.#resubscribe()),
    );

    this.#client.connect();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    for (const stop of this.#cleanup.splice(0)) stop();
    this.#client.close();
  }

  override render() {
    return html`
      <div class="frame">
        <header class="bar">
          <a class="brand" href="#/">${t.appName}</a>
          <div class="row">
            ${this.#themePicker()}
            <mh-connection-status .state=${this.connection}></mh-connection-status>
            ${
              this.connection === 'refused'
                ? html`<wa-button size="s" appearance="outlined" @click=${this.#retry}>
                    ${t.connection.retry}
                  </wa-button>`
                : null
            }
            ${
              this.info?.identity?.kind === 'password'
                ? html`<wa-button size="s" appearance="plain" @click=${this.#logout}>
                    ${t.login.logout}
                  </wa-button>`
                : null
            }
          </div>
        </header>

        <main>${this.#view()}</main>

        <footer>${this.#footer()}</footer>
      </div>
      ${this.#newDeviceDialog()}
    `;
  }

  /**
   * The new-device form, in a dialog over whatever is on screen.
   *
   * A dialog and not a route: creating a device is one action with one
   * outcome, and a URL for a half-filled form is a URL that promises to
   * restore something it cannot.
   */
  #newDeviceDialog() {
    if (!this.creating) return null;
    return html`
      <wa-dialog open label=${t.newDevice.title} @wa-hide=${this.#closeCreate}>
        <mh-new-device
          .registry=${this.registry}
          .submitting=${this.createBusy}
          .failure=${this.createFailure}
          .conflict=${this.createConflict}
          @device-requested=${this.#createDevice}
          @new-device-cancelled=${this.#closeCreate}
          @open-device=${this.#openDevice}
        ></mh-new-device>
      </wa-dialog>
    `;
  }

  #view() {
    if (this.connection === 'refused') {
      return html`<mh-login @logged-in=${this.#retry}></mh-login>`;
    }
    switch (this.route.view) {
      case 'devices':
        return html`
          <h1>${t.devices.title}</h1>
          <mh-device-list
            .devices=${this.#store.devices}
            .tree=${this.#store.tree}
            .loaded=${this.#store.loaded}
            .ingress=${this.info?.deployment.trust === 'ingress'}
            .validityOf=${(name: string) => this.#validity.get(name)}
            @new-device=${this.#openCreate}
          ></mh-device-list>
        `;
      case 'device': {
        const build = this.#builds.forDevice(this.route.name) ?? null;
        return html`
          <mh-device-page
            .client=${this.#client}
            .device=${this.route.name}
            .build=${build}
            .buildLines=${build === null ? NO_LINES : this.#builds.linesFor(build.id)}
            .buildTruncated=${build !== null && this.#builds.truncatedFor(build.id)}
            .buildMethod=${this.info?.build.method ?? this.info?.build.default_method ?? null}
            ?dark-theme=${this.theme === 'dark'}
          ></mh-device-page>
        `;
      }
      default:
        return html`
          <wa-callout variant="neutral">
            <strong>${t.notFound.title}</strong>
            <p>${t.notFound.body(this.route.path)}</p>
          </wa-callout>
        `;
    }
  }

  #themePicker() {
    return html`
      <wa-select
        size="s"
        appearance="outlined"
        value=${this.themePreference}
        label=${t.theme.label}
        @change=${this.#onThemeChange}
      >
        <wa-option value="system">${t.theme.system}</wa-option>
        <wa-option value="light">${t.theme.light}</wa-option>
        <wa-option value="dark">${t.theme.dark}</wa-option>
      </wa-select>
    `;
  }

  #footer() {
    const info = this.info;
    const unknown = t.footer.unknown;
    return html`
      <span
        >${t.footer.dashboard}:
        <span class="mono">${info?.dashboard.version ?? unknown}</span></span
      >
      <span
        >${t.footer.builder}: <span class="mono">${info?.builder.version ?? unknown}</span></span
      >
      <span
        >${t.footer.modelVersion}:
        <span class="mono">${info?.model_version.sends ?? unknown}</span></span
      >
      <span>${t.footer.tree}: <span class="mono">${info?.tree.root ?? unknown}</span></span>
    `;
  }

  // -- creating a device -------------------------------------------

  /**
   * Open the form, and fetch the registry it is made of.
   *
   * Fetched on opening rather than at startup: it is the same answer for
   * the lifetime of the backend process, and most sessions never open
   * this form at all. Kept afterwards, so a second attempt is instant.
   */
  #openCreate = (): void => {
    this.creating = true;
    this.createFailure = null;
    this.createConflict = null;
    if (this.registry !== null) return;
    void deviceBoards(this.#client)
      .then((registry) => {
        this.registry = registry;
      })
      .catch((error: unknown) => {
        this.createFailure = messageOf(error);
      });
  };

  #closeCreate = (): void => {
    this.creating = false;
  };

  #createDevice = (event: Event): void => {
    const detail = (event as CustomEvent).detail as {
      name: string;
      board: string;
      friendlyName: string;
      outline: DeviceOutline;
    };
    this.createBusy = true;
    this.createFailure = null;
    this.createConflict = null;
    void deviceNew(this.#client, detail.name, detail.board, {
      friendlyName: detail.friendlyName,
      outline: detail.outline,
    })
      .then((result) => {
        this.creating = false;
        // Straight into the editor on what was just written: the form
        // asked for the shape of the device, and the file is where the
        // rest of it is decided.
        window.location.hash = deviceHref(result.device.name);
      })
      .catch((error: unknown) => {
        if (codeOf(error) === 'conflict') {
          this.createConflict = detail.name;
        } else {
          this.createFailure = messageOf(error);
        }
      })
      .finally(() => {
        this.createBusy = false;
      });
  };

  #openDevice = (event: Event): void => {
    const detail = (event as CustomEvent).detail as { name: string | null };
    if (detail.name === null) return;
    this.creating = false;
    window.location.hash = deviceHref(detail.name);
  };

  // -- wiring ------------------------------------------------------

  #onStoreChanged(): void {
    this.revision += 1;
    this.#validity.sync(this.#store.devices.map((device) => device.name));
    if (this.#store.stale) {
      // The server discarded events for this connection. Re-subscribing
      // is the contract's own answer to that (ADR 0004).
      void this.#resubscribe();
    }
  }

  /**
   * The build store changed, which is also how a hole announces itself.
   *
   * `build_output` that arrived past what the store holds leaves a gap
   * behind, and a fresh snapshot leaves one whenever the server got
   * further than this connection did. Both come out here as "some build
   * needs its log fetched", and both are answered the same way.
   */
  #onBuildsChanged(): void {
    this.revision += 1;
    if (this.#builds.stale) {
      void this.#resubscribe();
      return;
    }
    this.#refillLogs();
  }

  #onEvent(event: string, payload: Record<string, unknown>): void {
    this.#builds.applyEvent(event, payload);
    const changed = this.#store.applyEvent(event, payload);
    if (!changed) return;
    if (event === 'device_added' || event === 'device_changed') {
      const device = payload.device as { name?: unknown } | undefined;
      if (typeof device?.name === 'string') {
        this.#validity.invalidate(device.name);
        this.#reloadOpenDevice(device.name);
      }
    }
  }

  /**
   * An open editor whose file changed underneath it.
   *
   * Only re-read when there is nothing to lose: a page with unsaved
   * edits keeps them, and the user finds out about the other write when
   * their save is refused with a `conflict`, which is where the choice
   * belongs.
   */
  #reloadOpenDevice(name: string): void {
    if (this.route.view !== 'device' || this.route.name !== name) return;
    const page = this.renderRoot.querySelector('mh-device-page');
    if (page !== null && !page.dirty) page.reload();
  }

  async #resubscribe(): Promise<void> {
    // Both stores mark themselves stale on the same `events_dropped`, so
    // without this the one event would take two snapshots.
    if (this.#resubscribing) return;
    this.#resubscribing = true;
    this.#validity.reset();
    try {
      const [snapshot, builds, info] = await Promise.all([
        configSubscribe(this.#client),
        buildSubscribe(this.#client),
        serverInfo(this.#client),
      ]);
      this.#store.setSnapshot(snapshot.devices, snapshot.tree);
      // The records are whole again; the output printed while this
      // connection was away is not, and `#refillLogs` is what fetches it.
      this.#builds.setSnapshot(builds.builds, builds.running);
      this.info = info;
    } catch {
      // A failed re-subscribe is a dropped connection nine times out of
      // ten, and the client is already reconnecting. The tenth is a
      // backend that refused, and the connection indicator says so.
      this.#store.reset();
      this.#builds.reset();
    } finally {
      this.#resubscribing = false;
    }
  }

  /**
   * Refetch what the store is missing of the logs worth having.
   *
   * Not every build's — a snapshot can carry twenty finished ones, and
   * fetching all of their logs to render none of them would spend a
   * command per build for nothing. The two that are worth it are the
   * build that is running and the one the open device page is showing.
   */
  readonly #refillLogs = coalesced(async () => {
    const targets: string[] = [];
    const running = this.#builds.running;
    if (running !== null) targets.push(running);
    if (this.route.view === 'device') {
      const shown = this.#builds.forDevice(this.route.name);
      if (shown !== undefined && !targets.includes(shown.id)) targets.push(shown.id);
    }
    // The targets are decided here, when the round starts — which is why
    // a call arriving during one is owed another rather than dropped
    // ({@link coalesced}): a device page opened mid-fetch is not in this
    // list, and with nothing building no event would ever ask again.
    if (!targets.some((id) => this.#builds.needsLog(id))) return;
    await fillLogGaps(this.#client, this.#builds, targets);
  });

  #retry = (): void => {
    this.#client.retry();
  };

  #logout = (): void => {
    void logout().then(() => {
      this.info = null;
      this.#store.reset();
      this.#builds.reset();
      this.#client.close();
      this.#client.connect();
    });
  };

  #onThemeChange = (event: Event): void => {
    const value = (event.target as unknown as { value?: string }).value;
    if (value !== 'system' && value !== 'light' && value !== 'dark') return;
    this.themePreference = value;
    writePreference(value);
    this.#applyTheme();
  };

  #applyTheme(): void {
    this.theme = resolveTheme(this.themePreference);
    applyTheme(this.theme);
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'mh-app': MhApp;
  }
}
