// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * One device: its configuration, its problems, its commissioning codes.
 *
 * The editing loop is deliberately explicit — open, edit, save, validate
 * — rather than a save-on-every-keystroke that runs the builder
 * continuously against a file the user is halfway through writing.
 * Saving does not validate on the backend either (`device/save`); this
 * component chains the two because that is the sequence a person means
 * by "save", not because the server couples them.
 *
 * **The conflict path is the interesting one.** The configuration tree
 * has other writers (ADR 0008): Studio Code Server, a git checkout, a
 * second tab. A save carries the `content_hash` the editor was opened
 * with, and when the file moved on since, the backend refuses with
 * `conflict` and the user gets the choice — reload and lose their edit,
 * or overwrite and lose the other one. Nothing is decided for them,
 * because either answer can be the right one and only they know which.
 */

import { css, html, LitElement } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

import type { WsClient } from '../api/client';
import { deviceCommissioning, deviceGet, deviceSave, deviceValidate } from '../api/commands';
import { CommandError } from '../api/protocol';
import type { CommissioningCodes, Diagnostic } from '../api/types';
import { devicesHref } from '../router';
import { t } from '../strings';
import { sharedStyles } from '../styles/shared';

import './mh-commissioning';
import './mh-diagnostics';
import './mh-yaml-editor';
import type { MhYamlEditor } from './mh-yaml-editor';

type SaveState = 'idle' | 'saving' | 'saved' | 'conflict';

@customElement('mh-device-page')
export class MhDevicePage extends LitElement {
  static override styles = [
    sharedStyles,
    css`
      header {
        margin-block-end: var(--wa-space-m);
      }

      h1 {
        font-size: var(--wa-font-size-xl);
        margin: 0;
      }

      section {
        margin-block-end: var(--wa-space-xl);
      }

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
    `,
  ];

  @property({ attribute: false })
  client: WsClient | null = null;

  @property({ type: String })
  device = '';

  @property({ type: Boolean, attribute: 'dark-theme' })
  darkTheme = false;

  @state() private loading = true;
  @state() private loadError: string | null = null;
  @state() private content = '';
  @state() private draft = '';
  @state() private contentHash: string | null = null;
  @state() private saveState: SaveState = 'idle';
  @state() private saveError: string | null = null;
  @state() private validating = false;
  @state() private diagnostics: readonly Diagnostic[] = [];
  @state() private validated = false;

  @state() private codes: CommissioningCodes | null = null;
  @state() private codesLoading = false;
  @state() private codesAnswered = false;
  @state() private codesFailure: string | null = null;
  @state() private codesErrors: readonly Diagnostic[] = [];

  override willUpdate(changed: Map<string, unknown>): void {
    if (changed.has('device') || (changed.has('client') && this.client !== null)) {
      this.#reset();
      void this.#load();
    }
  }

  /** Re-read from disk — what an external change to this device means. */
  reload(): void {
    void this.#load();
  }

  get dirty(): boolean {
    return this.draft !== this.content;
  }

  override render() {
    return html`
      <header>
        <a href=${devicesHref()}>&larr; ${t.device.back}</a>
        <h1>${this.device}</h1>
      </header>
      ${
        this.loadError !== null
          ? html`<wa-callout variant="danger">${t.editor.loadFailed(this.loadError)}</wa-callout>`
          : this.#editor()
      }
      <section>
        <mh-commissioning
          .device=${this.device}
          .codes=${this.codes}
          .errors=${this.codesErrors}
          .loading=${this.codesLoading}
          .answered=${this.codesAnswered}
          .failure=${this.codesFailure}
          @commissioning-requested=${this.#loadCodes}
          @commissioning-hidden=${this.#forgetCodes}
        ></mh-commissioning>
      </section>
      <p class="quiet">${t.device.buildPending}</p>
    `;
  }

  #editor() {
    if (this.loading) {
      return html`<wa-spinner></wa-spinner>`;
    }
    return html`
      <section>
        <div class="toolbar">
          <h2 class="grow">${t.editor.title}</h2>
          ${this.dirty ? html`<span class="quiet">${t.editor.unsaved}</span>` : null}
          ${
            this.saveState === 'saved' && !this.dirty
              ? html`<span class="quiet">${t.editor.saved}</span>`
              : null
          }
          <wa-button
            appearance="outlined"
            ?disabled=${!this.dirty || this.saveState === 'saving'}
            @click=${this.#revert}
          >
            ${t.editor.revert}
          </wa-button>
          <wa-button
            variant="brand"
            ?loading=${this.saveState === 'saving'}
            ?disabled=${!this.dirty}
            @click=${this.#save}
          >
            ${this.saveState === 'saving' ? t.editor.saving : t.editor.save}
          </wa-button>
          <wa-button appearance="outlined" ?loading=${this.validating} @click=${this.#validate}>
            ${this.validating ? t.editor.validating : t.editor.validate}
          </wa-button>
        </div>

        ${this.saveState === 'conflict' ? this.#conflict() : null}
        ${
          this.saveError !== null && this.saveState !== 'conflict'
            ? html`<wa-callout variant="danger">${t.editor.saveFailed(this.saveError)}</wa-callout>`
            : null
        }

        <mh-yaml-editor
          .value=${this.content}
          .diagnostics=${this.diagnostics}
          ?dark-theme=${this.darkTheme}
          @editor-change=${this.#onChange}
        ></mh-yaml-editor>
      </section>

      <section>
        <h2>${t.editor.problems}</h2>
        ${
          this.validated && this.diagnostics.length === 0
            ? html`<wa-callout variant="success" appearance="outlined"
                >${t.editor.valid}</wa-callout
              >`
            : html`<mh-diagnostics
                .diagnostics=${this.diagnostics}
                @diagnostic-selected=${this.#reveal}
              ></mh-diagnostics>`
        }
      </section>
    `;
  }

  #conflict() {
    return html`
      <wa-callout variant="warning">
        <strong>${t.editor.conflictTitle}</strong>
        <p>${t.editor.conflictBody}</p>
        <div class="row">
          <wa-button appearance="outlined" @click=${this.#revertToDisk}>
            ${t.editor.conflictReload}
          </wa-button>
          <wa-button variant="danger" appearance="outlined" @click=${this.#forceSave}>
            ${t.editor.conflictOverwrite}
          </wa-button>
        </div>
      </wa-callout>
    `;
  }

  // -- data --------------------------------------------------------

  #reset(): void {
    this.loading = true;
    this.loadError = null;
    this.content = '';
    this.draft = '';
    this.contentHash = null;
    this.saveState = 'idle';
    this.saveError = null;
    this.diagnostics = [];
    this.validated = false;
    this.#forgetCodes();
  }

  async #load(): Promise<void> {
    const client = this.client;
    if (client === null || this.device === '') return;
    this.loading = true;
    try {
      const result = await deviceGet(client, this.device);
      this.content = result.content;
      this.draft = result.content;
      this.contentHash = result.device.content_hash;
      this.loadError = null;
      this.saveState = 'idle';
      await this.#validate();
    } catch (cause) {
      this.loadError = messageOf(cause, this.device);
    } finally {
      this.loading = false;
    }
  }

  async #save(): Promise<void> {
    await this.#write(this.contentHash);
  }

  async #forceSave(): Promise<void> {
    // The user chose their version over what is on disk. Passing no hash
    // is the wire-level way to say that (`device/save`).
    await this.#write(null);
  }

  async #write(expectedHash: string | null): Promise<void> {
    const client = this.client;
    if (client === null) return;
    this.saveState = 'saving';
    this.saveError = null;
    const written = this.draft;
    try {
      const result = await deviceSave(client, this.device, written, expectedHash);
      this.content = written;
      this.contentHash = result.content_hash;
      this.saveState = 'saved';
      await this.#validate();
    } catch (cause) {
      if (cause instanceof CommandError && cause.code === 'conflict') {
        this.saveState = 'conflict';
        this.saveError = cause.message;
        return;
      }
      this.saveState = 'idle';
      this.saveError = messageOf(cause, this.device);
    }
  }

  async #validate(): Promise<void> {
    const client = this.client;
    if (client === null) return;
    this.validating = true;
    try {
      const result = await deviceValidate(client, this.device);
      this.diagnostics = result.errors ?? [];
      this.validated = true;
    } catch {
      // A validation that never came back says nothing about the file;
      // leaving the previous markers up would be a lie either way, so
      // they go.
      this.diagnostics = [];
      this.validated = false;
    } finally {
      this.validating = false;
    }
  }

  #loadCodes = (): void => {
    const client = this.client;
    if (client === null) return;
    this.codesLoading = true;
    this.codesFailure = null;
    void deviceCommissioning(client, this.device)
      .then((result) => {
        this.codes = result.commissioning;
        this.codesErrors = result.errors ?? [];
        this.codesAnswered = true;
      })
      .catch((cause: unknown) => {
        this.codesFailure = messageOf(cause, this.device);
      })
      .finally(() => {
        this.codesLoading = false;
      });
  };

  /** Drop the codes from memory the moment the view is closed. */
  #forgetCodes = (): void => {
    this.codes = null;
    this.codesErrors = [];
    this.codesAnswered = false;
    this.codesFailure = null;
    this.codesLoading = false;
  };

  // -- editor wiring -----------------------------------------------

  #onChange = (event: Event): void => {
    this.draft = (event as CustomEvent<{ value: string }>).detail.value;
    if (this.saveState === 'saved') this.saveState = 'idle';
  };

  #revert = (): void => {
    // `content` is already what we want back, so no property changes and
    // no re-render would reach the editor — this has to be imperative.
    this.draft = this.content;
    this.renderRoot.querySelector<MhYamlEditor>('mh-yaml-editor')?.setDocument(this.content);
  };

  #revertToDisk = (): void => {
    this.saveState = 'idle';
    void this.#load();
  };

  #reveal = (event: Event): void => {
    const { diagnostic } = (event as CustomEvent<{ diagnostic: Diagnostic }>).detail;
    if (diagnostic.line === null) return;
    this.renderRoot
      .querySelector<MhYamlEditor>('mh-yaml-editor')
      ?.revealLine(diagnostic.line, diagnostic.column);
  };
}

function messageOf(cause: unknown, device: string): string {
  if (cause instanceof CommandError) {
    return cause.code === 'not_found' ? t.device.notFound(device) : cause.message;
  }
  return cause instanceof Error ? cause.message : String(cause);
}

declare global {
  interface HTMLElementTagNameMap {
    'mh-device-page': MhDevicePage;
  }
}
