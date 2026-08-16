// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Describing a device from the registry, instead of from memory.
 *
 * Writing a device's first `main.yaml` by hand means knowing the Zephyr
 * board target verbatim, which devicetree node the board's I2C is
 * called, which compatible string names the part, and which Matter
 * cluster a temperature reading belongs in. Every one of those is
 * already written down — in the builder's registry — and this form is
 * that registry made choosable.
 *
 * **Nothing here knows any hardware.** Boards, parts, buses, clusters
 * and device types all arrive from `device/boards`, and the choices are
 * constrained against each other from the same data: a part is only
 * offered a bus of the kind its driver speaks, and a cluster is only
 * offered a channel measuring the quantity it wants. That is what makes
 * this form correct rather than merely convenient, and it is why it will
 * grow when the registry does without anybody editing it. It is also
 * why it looks thin today: MCUHome supports one board and one part.
 *
 * **The preview is the builder's own text**, not a re-rendering. Every
 * keystroke would be a round trip, so it is not fetched — the form
 * shows the picks it will send, and the file itself appears in the
 * editor a moment later. What is *written* is rendered by
 * `mcuhome.workbench.api.render_starter` and by nothing on this side.
 *
 * The refusals are the builder's too. A name that cannot become a
 * hostname, a board nobody brought up, a device that already exists —
 * this form does not pre-empt any of them, because a check here would be
 * a second opinion about a rule that lives somewhere else.
 */

import { css, html, LitElement, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

import type {
  BoardsResult,
  ClusterDef,
  DeviceOutline,
  DriverDef,
  OutlineEndpoint,
  OutlinePeripheral,
} from '../api/types';
import { t } from '../strings';
import { sharedStyles } from '../styles/shared';

/** One row of the hardware section, while it is being filled in. */
interface PeripheralDraft {
  key: number;
  id: string;
  driver: string;
  controller: string;
}

/** One row of the endpoint section. `sources` is per mandatory cluster. */
interface EndpointDraft {
  key: number;
  deviceType: string;
  sources: Record<string, string>;
}

/** The bus id every peripheral is attached under. */
function busId(kind: string, index: number): string {
  return `${kind}${index}`;
}

function hex(value: number): string {
  return `0x${value.toString(16).padStart(2, '0')}`;
}

@customElement('mh-new-device')
export class MhNewDevice extends LitElement {
  static override styles = [
    sharedStyles,
    css`
      form {
        display: flex;
        flex-direction: column;
        gap: var(--wa-space-l);
      }

      section {
        display: flex;
        flex-direction: column;
        gap: var(--wa-space-s);
      }

      h3 {
        margin: 0;
        font-size: var(--wa-font-size-m);
      }

      .row {
        display: flex;
        gap: var(--wa-space-m);
        flex-wrap: wrap;
        align-items: flex-end;
      }

      .row > * {
        flex: 1 1 12rem;
        min-width: 0;
      }

      .row .shrink {
        flex: 0 0 auto;
      }

      .card {
        border: 1px solid var(--wa-color-surface-border);
        border-radius: var(--wa-border-radius-m);
        padding: var(--wa-space-m);
        display: flex;
        flex-direction: column;
        gap: var(--wa-space-s);
      }

      .actions {
        display: flex;
        gap: var(--wa-space-s);
        justify-content: flex-end;
      }

      pre {
        margin: 0;
        padding: var(--wa-space-s);
        border-radius: var(--wa-border-radius-m);
        background: var(--wa-color-neutral-fill-quiet);
        font-size: var(--wa-font-size-s);
        overflow-x: auto;
      }
    `,
  ];

  /** The registry, or `null` while `device/boards` is in flight. */
  @property({ attribute: false })
  registry: BoardsResult | null = null;

  /** True while `device/new` is in flight. */
  @property({ type: Boolean })
  submitting = false;

  /** What the backend refused with, if it did. */
  @property({ type: String })
  failure: string | null = null;

  /** Set when the refusal was "this device already exists". */
  @property({ type: String })
  conflict: string | null = null;

  @state() private name = '';
  @state() private friendly = '';
  @state() private board = '';
  @state() private peripherals: PeripheralDraft[] = [];
  @state() private endpoints: EndpointDraft[] = [];

  #nextKey = 1;

  override render() {
    const registry = this.registry;
    if (registry === null) return html`<wa-spinner></wa-spinner>`;
    return html`
      <form @submit=${this.#submit}>
        <p class="quiet">${t.newDevice.lead}</p>
        ${this.#identity(registry)} ${this.#hardware(registry)} ${this.#endpoints(registry)}
        ${this.#preview()} ${this.#failure()}
        <div class="actions">
          <wa-button appearance="plain" type="button" @click=${this.#cancel}>
            ${t.newDevice.cancel}
          </wa-button>
          <wa-button
            variant="brand"
            type="submit"
            ?disabled=${this.submitting || this.name === '' || this.board === ''}
          >
            ${this.submitting ? t.newDevice.submitting : t.newDevice.submit}
          </wa-button>
        </div>
      </form>
    `;
  }

  // -- who it is ---------------------------------------------------

  #identity(registry: BoardsResult) {
    return html`
      <section>
        <div class="row">
          <wa-input
            label=${t.newDevice.nameLabel}
            hint=${t.newDevice.nameHint}
            .value=${this.name}
            required
            @input=${(event: Event) => {
              this.name = this.#value(event);
            }}
          ></wa-input>
          <wa-input
            label=${t.newDevice.friendlyLabel}
            hint=${t.newDevice.friendlyHint}
            .value=${this.friendly}
            @input=${(event: Event) => {
              this.friendly = this.#value(event);
            }}
          ></wa-input>
        </div>
        <wa-select
          label=${t.newDevice.boardLabel}
          hint=${t.newDevice.boardHint}
          placeholder=${t.newDevice.boardPlaceholder}
          .value=${this.board}
          @change=${this.#onBoardChange}
        >
          ${registry.boards.map(
            (board) => html`<wa-option value=${board.name}>${board.name}</wa-option>`,
          )}
        </wa-select>
        ${
          registry.planned_boards.length === 0
            ? nothing
            : html`<details>
                <summary class="quiet">${t.newDevice.plannedBoards}</summary>
                <ul class="quiet">
                  ${registry.planned_boards.map(
                    (entry) => html`<li>${t.newDevice.planned(entry.name, entry.reason)}</li>`,
                  )}
                </ul>
              </details>`
        }
      </section>
    `;
  }

  // -- what is wired up --------------------------------------------

  #hardware(registry: BoardsResult) {
    const usable = this.#usableDrivers(registry);
    return html`
      <section>
        <h3>${t.newDevice.hardwareTitle}</h3>
        <p class="quiet">${t.newDevice.hardwareLead}</p>
        ${this.peripherals.map((entry) => this.#peripheral(entry, usable))}
        ${
          usable.length === 0
            ? html`<p class="quiet">
                ${this.board === '' ? t.newDevice.noDrivers : t.newDevice.noBuses}
              </p>`
            : html`<div>
                <wa-button
                  size="s"
                  appearance="outlined"
                  type="button"
                  @click=${() => this.#addPeripheral(usable)}
                >
                  ${t.newDevice.addPeripheral}
                </wa-button>
              </div>`
        }
      </section>
    `;
  }

  #peripheral(entry: PeripheralDraft, usable: readonly DriverDef[]) {
    const driver = usable.find((candidate) => candidate.compatible === entry.driver) ?? null;
    const buses = this.#busesFor(driver);
    return html`
      <div class="card">
        <div class="row">
          <wa-input
            label=${t.newDevice.peripheralName}
            hint=${t.newDevice.peripheralNameHint}
            .value=${entry.id}
            @input=${(event: Event) => this.#editPeripheral(entry.key, { id: this.#value(event) })}
          ></wa-input>
          <wa-select
            label=${t.newDevice.peripheralDriver}
            .value=${entry.driver}
            @change=${(event: Event) =>
              this.#editPeripheral(entry.key, { driver: this.#value(event) })}
          >
            ${usable.map(
              (candidate) =>
                html`<wa-option value=${candidate.compatible}>${candidate.compatible}</wa-option>`,
            )}
          </wa-select>
          ${
            buses.length === 0
              ? nothing
              : html`<wa-select
                  label=${t.newDevice.peripheralBus}
                  .value=${entry.controller}
                  @change=${(event: Event) =>
                    this.#editPeripheral(entry.key, { controller: this.#value(event) })}
                >
                  ${buses.map(
                    (bus) =>
                      html`<wa-option value=${bus.controller}>${bus.description}</wa-option>`,
                  )}
                </wa-select>`
          }
          <wa-button
            class="shrink"
            size="s"
            appearance="plain"
            type="button"
            @click=${() => this.#removePeripheral(entry.key)}
          >
            ${t.newDevice.removePeripheral}
          </wa-button>
        </div>
        ${
          driver?.fixed_address == null
            ? nothing
            : html`<p class="quiet">${t.newDevice.fixedAddress(hex(driver.fixed_address))}</p>`
        }
      </div>
    `;
  }

  // -- what a controller sees --------------------------------------

  #endpoints(registry: BoardsResult) {
    const available = this.#availableTypes(registry);
    return html`
      <section>
        <h3>${t.newDevice.endpointsTitle}</h3>
        <p class="quiet">${t.newDevice.endpointsLead}</p>
        ${this.endpoints.map((entry) => this.#endpoint(entry, registry))}
        ${
          available.length === 0
            ? html`<p class="quiet">${t.newDevice.needHardwareFirst}</p>`
            : html`<div>
                <wa-button
                  size="s"
                  appearance="outlined"
                  type="button"
                  @click=${() => this.#addEndpoint(registry)}
                >
                  ${t.newDevice.addEndpoint}
                </wa-button>
              </div>`
        }
      </section>
    `;
  }

  #endpoint(entry: EndpointDraft, registry: BoardsResult) {
    const available = this.#availableTypes(registry);
    const clusters = this.#clustersOf(entry.deviceType, registry);
    return html`
      <div class="card">
        <div class="row">
          <wa-select
            label=${t.newDevice.endpointType}
            .value=${entry.deviceType}
            @change=${(event: Event) => this.#editEndpoint(entry.key, this.#value(event), registry)}
          >
            ${available.map((type) => html`<wa-option value=${type.name}>${type.name}</wa-option>`)}
          </wa-select>
          <wa-button
            class="shrink"
            size="s"
            appearance="plain"
            type="button"
            @click=${() => this.#removeEndpoint(entry.key)}
          >
            ${t.newDevice.removeEndpoint}
          </wa-button>
        </div>
        ${clusters.map((cluster) => this.#source(entry, cluster, registry))}
      </div>
    `;
  }

  #source(entry: EndpointDraft, cluster: ClusterDef, registry: BoardsResult) {
    const sources = this.#sourcesFor(cluster, registry);
    if (sources.length === 0) {
      return html`<p class="quiet">${t.newDevice.noSource}</p>`;
    }
    return html`
      <wa-select
        label=${t.newDevice.endpointSource(cluster.name)}
        .value=${entry.sources[cluster.name] ?? ''}
        @change=${(event: Event) => this.#editSource(entry.key, cluster.name, this.#value(event))}
      >
        ${sources.map((source) => html`<wa-option value=${source}>${source}</wa-option>`)}
      </wa-select>
    `;
  }

  // -- what will be sent -------------------------------------------

  #preview() {
    const outline = this.outline();
    if (outline.peripherals.length === 0 && outline.endpoints.length === 0) return nothing;
    return html`
      <section>
        <h3>${t.newDevice.previewTitle}</h3>
        <p class="quiet mono">${t.newDevice.previewLead}</p>
        <pre>${JSON.stringify(outline, null, 2)}</pre>
      </section>
    `;
  }

  #failure() {
    if (this.conflict !== null) {
      return html`<wa-callout variant="warning">
        ${t.newDevice.exists(this.conflict)}
        <wa-button size="s" appearance="plain" type="button" @click=${this.#openExisting}>
          ${t.newDevice.openExisting}
        </wa-button>
      </wa-callout>`;
    }
    if (this.failure === null) return nothing;
    return html`<wa-callout variant="danger">${t.newDevice.failed(this.failure)}</wa-callout>`;
  }

  // -- the registry, constrained against itself --------------------

  /** The board this form is describing, or `null` before one is picked. */
  #board(registry: BoardsResult) {
    return registry.boards.find((entry) => entry.name === this.board) ?? null;
  }

  /**
   * Parts that can actually be attached to the chosen board.
   *
   * A driver speaks one kind of bus and a board breaks out some set of
   * them; offering a part the board cannot carry would produce a
   * configuration that fails at the overlay, far from the choice that
   * caused it.
   */
  #usableDrivers(registry: BoardsResult): DriverDef[] {
    const board = this.#board(registry);
    if (board === null) return [];
    const kinds = new Set(board.buses.map((bus) => bus.kind));
    return registry.drivers.filter((driver) => driver.bus === null || kinds.has(driver.bus));
  }

  #busesFor(driver: DriverDef | null) {
    const registry = this.registry;
    if (registry === null || driver === null || driver.bus === null) return [];
    return (this.#board(registry)?.buses ?? []).filter((bus) => bus.kind === driver.bus);
  }

  /** Device types every mandatory cluster of which some attached part can feed. */
  #availableTypes(registry: BoardsResult) {
    return registry.device_types.filter((type) =>
      type.mandatory_clusters.every((name) => {
        const cluster = registry.clusters.find((entry) => entry.name === name);
        return cluster !== undefined && this.#sourcesFor(cluster, registry).length > 0;
      }),
    );
  }

  #clustersOf(deviceType: string, registry: BoardsResult): ClusterDef[] {
    const type = registry.device_types.find((entry) => entry.name === deviceType);
    if (type === undefined) return [];
    return type.mandatory_clusters
      .map((name) => registry.clusters.find((entry) => entry.name === name))
      .filter((entry): entry is ClusterDef => entry !== undefined);
  }

  /**
   * `<part>.<channel>` for every attached channel measuring the right
   * quantity — the constraint the builder states as "a cluster only
   * accepts a channel of the same quantity".
   */
  #sourcesFor(cluster: ClusterDef, registry: BoardsResult): string[] {
    const sources: string[] = [];
    for (const entry of this.peripherals) {
      const driver = registry.drivers.find((candidate) => candidate.compatible === entry.driver);
      if (driver === undefined || entry.id === '') continue;
      for (const channel of driver.channels) {
        if (channel.quantity === cluster.quantity) sources.push(`${entry.id}.${channel.name}`);
      }
    }
    return sources;
  }

  // -- the picks, as the wire takes them ---------------------------

  /**
   * What `device/new` will be sent.
   *
   * Buses are derived rather than asked for: a person wiring a sensor to
   * a board picks the connector, not the YAML key that names it, so each
   * distinct controller becomes one bus entry and the peripherals point
   * at it.
   */
  outline(): DeviceOutline {
    const controllers: string[] = [];
    const peripherals: OutlinePeripheral[] = [];
    for (const entry of this.peripherals) {
      if (entry.id === '' || entry.driver === '') continue;
      if (entry.controller === '') {
        peripherals.push({ id: entry.id, driver: entry.driver });
        continue;
      }
      if (!controllers.includes(entry.controller)) controllers.push(entry.controller);
      peripherals.push({
        id: entry.id,
        driver: entry.driver,
        bus: busId(this.#kindOf(entry.controller), controllers.indexOf(entry.controller)),
      });
    }

    const endpoints: OutlineEndpoint[] = [];
    for (const entry of this.endpoints) {
      const clusters = Object.entries(entry.sources)
        .filter(([, source]) => source !== '')
        .map(([cluster, source]) => ({ cluster, source }));
      if (entry.deviceType === '' || clusters.length === 0) continue;
      endpoints.push({ device_type: entry.deviceType, clusters });
    }

    return {
      buses: controllers.map((controller, index) => ({
        id: busId(this.#kindOf(controller), index),
        controller,
      })),
      peripherals,
      endpoints,
    };
  }

  #kindOf(controller: string): string {
    const registry = this.registry;
    if (registry === null) return 'bus';
    const board = this.#board(registry);
    return board?.buses.find((bus) => bus.controller === controller)?.kind ?? 'bus';
  }

  // -- editing -----------------------------------------------------

  #value(event: Event): string {
    const target = event.target as { value?: unknown } | null;
    return typeof target?.value === 'string' ? target.value : '';
  }

  #onBoardChange = (event: Event): void => {
    this.board = this.#value(event);
    // A part that was chosen for another board may not exist on this
    // one, and an endpoint reading from it would name nothing. Starting
    // the hardware over is honest; silently keeping a dangling reference
    // is not.
    this.peripherals = [];
    this.endpoints = [];
  };

  #addPeripheral(usable: readonly DriverDef[]): void {
    const driver = usable[0];
    if (driver === undefined) return;
    const buses = this.#busesFor(driver);
    this.peripherals = [
      ...this.peripherals,
      {
        key: this.#nextKey++,
        id: this.#freeName(),
        driver: driver.compatible,
        controller: buses[0]?.controller ?? '',
      },
    ];
  }

  /** `sensor`, `sensor2`, … — a default the user can overwrite. */
  #freeName(): string {
    const taken = new Set(this.peripherals.map((entry) => entry.id));
    if (!taken.has('sensor')) return 'sensor';
    for (let index = 2; ; index += 1) {
      const candidate = `sensor${index}`;
      if (!taken.has(candidate)) return candidate;
    }
  }

  #editPeripheral(key: number, patch: Partial<PeripheralDraft>): void {
    this.peripherals = this.peripherals.map((entry) => {
      if (entry.key !== key) return entry;
      const next = { ...entry, ...patch };
      if (patch.driver !== undefined) {
        const registry = this.registry;
        const driver =
          registry?.drivers.find((candidate) => candidate.compatible === patch.driver) ?? null;
        next.controller = this.#busesFor(driver)[0]?.controller ?? '';
      }
      return next;
    });
    this.#pruneSources();
  }

  #removePeripheral(key: number): void {
    this.peripherals = this.peripherals.filter((entry) => entry.key !== key);
    this.#pruneSources();
  }

  #addEndpoint(registry: BoardsResult): void {
    const type = this.#availableTypes(registry)[0];
    if (type === undefined) return;
    this.endpoints = [
      ...this.endpoints,
      { key: this.#nextKey++, deviceType: type.name, sources: this.#defaultSources(type.name) },
    ];
  }

  #editEndpoint(key: number, deviceType: string, registry: BoardsResult): void {
    void registry;
    this.endpoints = this.endpoints.map((entry) =>
      entry.key === key
        ? { ...entry, deviceType, sources: this.#defaultSources(deviceType) }
        : entry,
    );
  }

  #editSource(key: number, cluster: string, source: string): void {
    this.endpoints = this.endpoints.map((entry) =>
      entry.key === key ? { ...entry, sources: { ...entry.sources, [cluster]: source } } : entry,
    );
  }

  #removeEndpoint(key: number): void {
    this.endpoints = this.endpoints.filter((entry) => entry.key !== key);
  }

  #defaultSources(deviceType: string): Record<string, string> {
    const registry = this.registry;
    if (registry === null) return {};
    const sources: Record<string, string> = {};
    for (const cluster of this.#clustersOf(deviceType, registry)) {
      const first = this.#sourcesFor(cluster, registry)[0];
      if (first !== undefined) sources[cluster.name] = first;
    }
    return sources;
  }

  /**
   * Drop endpoint sources that no longer name an attached channel.
   *
   * Renaming or removing a part while an endpoint reads from it would
   * otherwise send a source pointing at nothing — a refusal the builder
   * would give correctly, for a mistake this form caused.
   */
  #pruneSources(): void {
    const registry = this.registry;
    if (registry === null) return;
    this.endpoints = this.endpoints.map((entry) => {
      const sources: Record<string, string> = {};
      for (const cluster of this.#clustersOf(entry.deviceType, registry)) {
        const valid = this.#sourcesFor(cluster, registry);
        const current = entry.sources[cluster.name];
        const kept = current !== undefined && valid.includes(current) ? current : valid[0];
        if (kept !== undefined) sources[cluster.name] = kept;
      }
      return { ...entry, sources };
    });
  }

  // -- leaving ------------------------------------------------------

  #submit = (event: Event): void => {
    event.preventDefault();
    if (this.submitting || this.name === '' || this.board === '') return;
    this.dispatchEvent(
      new CustomEvent('device-requested', {
        bubbles: true,
        composed: true,
        detail: {
          name: this.name,
          board: this.board,
          friendlyName: this.friendly,
          outline: this.outline(),
        },
      }),
    );
  };

  #cancel = (): void => {
    this.dispatchEvent(new CustomEvent('new-device-cancelled', { bubbles: true, composed: true }));
  };

  #openExisting = (): void => {
    this.dispatchEvent(
      new CustomEvent('open-device', {
        bubbles: true,
        composed: true,
        detail: { name: this.conflict },
      }),
    );
  };
}

declare global {
  interface HTMLElementTagNameMap {
    'mh-new-device': MhNewDevice;
  }
}
