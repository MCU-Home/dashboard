// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Building one device: the button, the live log, and what came out.
 *
 * The record and the log lines are handed in rather than fetched: they
 * belong to the store `mh-app` owns, because a build outlives this view
 * — a user navigating away and back must find the build still running
 * and its output still complete, which is impossible for a component
 * that starts from nothing every time it is created. The two commands
 * that *are* actions of this panel, `build/start` and `build/cancel`,
 * are sent from here, because their refusals are what this panel has to
 * render.
 *
 * **A build that failed is not an error.** The refusal is the record's
 * state and its `errors`, in the same `Diagnostic` shape a configuration
 * problem has, so it renders on `mh-diagnostics` — the same component,
 * with the same hints and the same positions, rather than a second
 * renderer that would drift from the first. An error frame from
 * `build/start` is the other thing: the build never began, and that
 * needs its own message.
 *
 * The log follows the tail only while the user is *at* the tail.
 * Scrolling up during a twenty-minute Zephyr build is how somebody reads
 * an error that already went past, and a view that yanked them back to
 * the bottom every 200 ms would make that impossible.
 */

import { css, html, LitElement } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

import type { WsClient } from '../api/client';
import { buildCancel, buildStart } from '../api/commands';
import { CommandError } from '../api/protocol';
import type { BuildArtifact, BuildRecord, Diagnostic } from '../api/types';
import { artifactUrl } from '../base-path';
import { t } from '../strings';
import { sharedStyles } from '../styles/shared';

import './mh-build-steps';
import './mh-diagnostics';

/** How close to the bottom still counts as "following the tail", in pixels. */
const FOLLOW_SLACK = 24;

/** How often the elapsed time is redrawn while a build runs. */
const TICK = 1000;

@customElement('mh-build-panel')
export class MhBuildPanel extends LitElement {
  static override styles = [
    sharedStyles,
    css`
      .toolbar {
        display: flex;
        align-items: center;
        gap: var(--wa-space-s);
        flex-wrap: wrap;
        margin-block-end: var(--wa-space-s);
      }

      .grow {
        flex: 1 1 auto;
      }

      .log {
        max-height: 24rem;
        overflow: auto;
        padding: var(--wa-space-s) var(--wa-space-m);
        border: 1px solid var(--wa-color-surface-border);
        border-radius: var(--wa-border-radius-m);
        background: var(--wa-color-surface-lowered);
      }

      .log pre {
        margin: 0;
        font-family: var(--wa-font-family-code);
        font-size: var(--wa-font-size-s);
        line-height: 1.45;
        white-space: pre-wrap;
        overflow-wrap: anywhere;
      }

      ul.artifacts {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: var(--wa-space-xs);
      }

      ul.artifacts li {
        display: flex;
        align-items: center;
        gap: var(--wa-space-s);
        flex-wrap: wrap;
      }

      ul.artifacts a {
        font-family: var(--wa-font-family-code);
        font-size: var(--wa-font-size-s);
      }

      .meta {
        color: var(--wa-color-text-quiet);
        font-size: var(--wa-font-size-s);
      }

      h3 {
        font-size: var(--wa-font-size-m);
        font-weight: var(--wa-font-weight-semibold);
        margin: var(--wa-space-m) 0 var(--wa-space-xs);
      }
    `,
  ];

  @property({ attribute: false })
  client: WsClient | null = null;

  /** The device this builds; changing it drops whatever was shown. */
  @property({ type: String })
  device = '';

  /** This device's most recent build, or `null` when it has none. */
  @property({ attribute: false })
  record: BuildRecord | null = null;

  @property({ attribute: false })
  lines: readonly string[] = [];

  /** True once output was dropped — the log does not start at line one. */
  @property({ type: Boolean })
  truncated = false;

  /** What this deployment would build with, for a device never built. */
  @property({ type: String })
  defaultMethod: string | null = null;

  @state() private starting = false;
  @state() private cancelling = false;
  @state() private failure: string | null = null;
  @state() private startErrors: readonly Diagnostic[] = [];
  /** The build holding the one slot, when a start was refused for it. */
  @state() private busy: BuildRecord | null = null;

  /** False while the user is reading further up; see the file docstring. */
  #following = true;
  #timer: number | null = null;
  @state() private now = Date.now() / 1000;

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.#stopClock();
  }

  override willUpdate(changed: Map<string, unknown>): void {
    if (changed.has('device')) {
      this.failure = null;
      this.startErrors = [];
      this.busy = null;
      this.#following = true;
    }
  }

  override updated(): void {
    this.#syncClock();
    if (!this.#following) return;
    const view = this.renderRoot.querySelector<HTMLElement>('.log');
    if (view !== null) view.scrollTop = view.scrollHeight;
  }

  /** True while this device has a build that has not ended yet. */
  get active(): boolean {
    const state = this.record?.state;
    return state === 'queued' || state === 'running';
  }

  override render() {
    const record = this.record;
    return html`
      <div class="toolbar">
        <h2 class="grow">${t.build.title}</h2>
        ${record === null ? null : this.#badge(record)} ${this.active ? this.#spinner() : null}
        ${
          this.active
            ? html`<wa-button
                appearance="outlined"
                variant="danger"
                ?loading=${this.cancelling}
                @click=${this.#cancel}
              >
                ${this.cancelling ? t.build.cancelling : t.build.cancel}
              </wa-button>`
            : null
        }
        <wa-button
          variant="brand"
          ?loading=${this.starting}
          ?disabled=${this.active || this.starting || this.client === null}
          @click=${this.#start}
        >
          ${this.starting ? t.build.starting : t.build.start}
        </wa-button>
      </div>

      <p class="quiet">${this.#subtitle(record)}</p>

      ${
        this.busy === null
          ? null
          : html`<wa-callout variant="warning">${this.#busyText(this.busy)}</wa-callout>`
      }
      ${
        this.failure === null
          ? null
          : html`<wa-callout variant="danger">${t.build.startFailed(this.failure)}</wa-callout>`
      }
      ${
        this.startErrors.length === 0
          ? null
          : html`<mh-diagnostics .diagnostics=${this.startErrors}></mh-diagnostics>`
      }
      ${record === null ? null : this.#body(record)}
    `;
  }

  // -- pieces ------------------------------------------------------

  #spinner() {
    return html`<wa-spinner></wa-spinner>`;
  }

  /**
   * Why a start was refused, in the words the holder's state deserves.
   *
   * The slot belongs to the work rather than to the record, so a refusal
   * can name a build that already reads `cancelled`: cancelling stops
   * the dashboard waiting, not the container it started. Saying "is
   * already running" about it would contradict the badge next to it.
   */
  #busyText(holder: BuildRecord): string {
    return holder.state === 'cancelled'
      ? t.build.busyCancelled(holder.device)
      : t.build.busy(holder.device);
  }

  #badge(record: BuildRecord) {
    switch (record.state) {
      case 'running':
        return html`<wa-badge variant="brand" appearance="filled" pill
          >${t.build.running}</wa-badge
        >`;
      case 'queued':
        return html`<wa-badge variant="neutral" appearance="outlined" pill>
          ${t.build.queued}
        </wa-badge>`;
      case 'succeeded':
        return html`<wa-badge variant="success" appearance="filled" pill>
          ${t.build.succeeded}
        </wa-badge>`;
      case 'failed':
        return html`<wa-badge variant="danger" appearance="filled" pill
          >${t.build.failed}</wa-badge
        >`;
      case 'cancelled':
        return html`<wa-badge variant="neutral" appearance="filled" pill>
          ${t.build.cancelled}
        </wa-badge>`;
      default:
        return html`<wa-badge variant="neutral" appearance="outlined" pill>
          ${t.build.unknownState}
        </wa-badge>`;
    }
  }

  /** The one line under the toolbar: what will run, or what did. */
  #subtitle(record: BuildRecord | null) {
    if (record === null) {
      const method = this.defaultMethod;
      return method === null ? t.build.never : `${t.build.never} ${t.build.method(method)}`;
    }
    const seconds = (record.finished ?? this.now) - record.started;
    const spent =
      record.finished === null
        ? t.build.elapsed(t.build.duration(seconds))
        : t.build.took(t.build.duration(seconds));
    return `${t.build.method(record.method)} · ${spent}`;
  }

  #body(record: BuildRecord) {
    return html`
      ${
        record.signing?.created_key === true
          ? html`<wa-callout variant="warning">
              <strong>${t.build.createdKeyTitle}</strong>
              <p>${t.build.createdKeyBody}</p>
            </wa-callout>`
          : null
      }
      ${
        // Above the log on purpose: the bar is what a person reads first
        // and keeps glancing at, and the log is what they scroll into
        // when it says something they want the detail of.
        (record.steps?.length ?? 0) === 0
          ? null
          : html`<h3>${t.build.steps.title}</h3>
              <mh-build-steps .steps=${record.steps} .method=${record.method}></mh-build-steps>`
      }

      <h3>${t.build.log}</h3>
      ${this.truncated ? html`<p class="quiet">${t.build.logTruncated}</p>` : null}
      ${
        this.lines.length === 0
          ? html`<p class="quiet">${t.build.logEmpty}</p>`
          : html`<div class="log" @scroll=${this.#onScroll}>
              <pre>${this.lines.join('\n')}</pre>
            </div>`
      }
      ${
        record.errors.length === 0
          ? null
          : html`<h3>${t.build.problems}</h3>
              <mh-diagnostics .diagnostics=${record.errors}></mh-diagnostics>`
      }

      <h3>${t.build.artifacts}</h3>
      ${
        record.ota === null
          ? null
          : html`<p class="quiet">
              ${t.build.ota(record.ota.version, record.ota.software_version)}
            </p>`
      }
      ${
        record.artifacts.length === 0
          ? html`<p class="quiet">${t.build.noArtifacts}</p>`
          : html`<ul class="artifacts">
              ${record.artifacts.map((artifact) => this.#artifact(record.id, artifact))}
            </ul>`
      }
    `;
  }

  #artifact(buildId: string, artifact: BuildArtifact) {
    return html`
      <li>
        <a download href=${artifactUrl(buildId, artifact.path)}>${artifact.path}</a>
        <span class="meta">${artifact.role} · ${t.build.size(artifact.size)}</span>
        ${
          artifact.signed
            ? html`<wa-badge variant="success" appearance="outlined" pill>
                ${t.build.signed}
              </wa-badge>`
            : null
        }
      </li>
    `;
  }

  // -- actions -----------------------------------------------------

  #start = (): void => {
    const client = this.client;
    if (client === null || this.device === '' || this.active) return;
    this.starting = true;
    this.failure = null;
    this.startErrors = [];
    this.busy = null;
    // The record itself arrives on the `builds` topic; nothing here waits
    // for the answer except to render the refusals.
    void buildStart(client, this.device)
      .catch((cause: unknown) => {
        if (!(cause instanceof CommandError)) {
          this.failure = cause instanceof Error ? cause.message : String(cause);
          return;
        }
        const holder = cause.detail.build as BuildRecord | undefined;
        if (cause.code === 'conflict' && holder !== undefined) {
          this.busy = holder;
          return;
        }
        this.startErrors = (cause.detail.errors as Diagnostic[] | undefined) ?? [];
        this.failure = cause.message;
      })
      .finally(() => {
        this.starting = false;
      });
  };

  #cancel = (): void => {
    const client = this.client;
    const record = this.record;
    if (client === null || record === null) return;
    this.cancelling = true;
    void buildCancel(client, record.id)
      .catch((cause: unknown) => {
        this.failure = t.build.cancelFailed(cause instanceof Error ? cause.message : String(cause));
      })
      .finally(() => {
        this.cancelling = false;
      });
  };

  #onScroll = (event: Event): void => {
    const view = event.currentTarget as HTMLElement;
    this.#following = view.scrollHeight - view.scrollTop - view.clientHeight < FOLLOW_SLACK;
  };

  // -- the elapsed clock -------------------------------------------

  #syncClock(): void {
    if (this.active && this.#timer === null) {
      this.#timer = globalThis.setInterval(() => {
        this.now = Date.now() / 1000;
      }, TICK);
      return;
    }
    if (!this.active) this.#stopClock();
  }

  #stopClock(): void {
    if (this.#timer === null) return;
    globalThis.clearInterval(this.#timer);
    this.#timer = null;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'mh-build-panel': MhBuildPanel;
  }
}
