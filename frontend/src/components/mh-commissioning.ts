// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The codes that add this device to a Matter controller.
 *
 * **The QR payload contains the device's passcode.** That is not an
 * oversight to work around — it is what a commissioning code *is*, and a
 * view that hid it would commission nothing. So the design question is
 * not whether to show it but when, and the answer here is: never on page
 * load, only after an explicit "show commissioning codes". A device page
 * left open on a screen in a workshop does not put a passcode on that
 * screen, and the backend only sends the codes when this button was
 * pressed (`device/commissioning`).
 *
 * ADR 0009 decision 4 says plainly that secrets are visible in the
 * browser and that we say so; the warning below is that sentence in the
 * place a user reads it.
 *
 * Rendering is `wa-qr-code` from Web Awesome, which draws to a canvas in
 * the page — the payload never leaves the browser and no image is
 * fetched from anywhere (ADR 0005).
 */

import { css, html, LitElement } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

import type { CommissioningCodes, Diagnostic } from '../api/types';
import { t } from '../strings';
import { sharedStyles } from '../styles/shared';

@customElement('mh-commissioning')
export class MhCommissioning extends LitElement {
  static override styles = [
    sharedStyles,
    css`
      .codes {
        display: flex;
        gap: var(--wa-space-xl);
        flex-wrap: wrap;
        align-items: flex-start;
      }

      dl {
        margin: 0;
        display: grid;
        grid-template-columns: auto 1fr;
        gap: var(--wa-space-2xs) var(--wa-space-m);
        align-items: baseline;
      }

      dt {
        color: var(--wa-color-text-quiet);
        font-size: var(--wa-font-size-s);
      }

      dd {
        margin: 0;
        font-family: var(--wa-font-family-code);
      }

      wa-qr-code {
        background: white;
        padding: var(--wa-space-s);
        border-radius: var(--wa-border-radius-m);
      }
    `,
  ];

  /** The device this belongs to; changing it hides the codes again. */
  @property({ type: String })
  device = '';

  @property({ attribute: false })
  codes: CommissioningCodes | null = null;

  @property({ attribute: false })
  errors: readonly Diagnostic[] = [];

  /** True while `device/commissioning` is in flight. */
  @property({ type: Boolean })
  loading = false;

  @property({ type: String })
  failure: string | null = null;

  /** True once the codes were answered — `codes: null` then means "none". */
  @property({ type: Boolean })
  answered = false;

  @state()
  private revealed = false;

  override willUpdate(changed: Map<string, unknown>): void {
    if (changed.has('device') && changed.get('device') !== undefined) {
      // Navigating to another device must not carry a revealed passcode
      // across with it.
      this.revealed = false;
    }
  }

  override render() {
    return html`
      <h2>${t.commissioning.title}</h2>
      <p class="quiet">${t.commissioning.lead}</p>
      ${this.revealed ? this.#body() : this.#revealButton()}
    `;
  }

  #revealButton() {
    return html`
      <wa-button variant="brand" appearance="outlined" @click=${this.#reveal}>
        ${t.commissioning.reveal}
      </wa-button>
    `;
  }

  #body() {
    if (this.loading) {
      return html`<wa-spinner></wa-spinner>`;
    }
    if (this.failure !== null) {
      return html`<wa-callout variant="danger"
        >${t.commissioning.failed(this.failure)}</wa-callout
      >`;
    }
    if (this.errors.length > 0) {
      return html`<wa-callout variant="warning">${t.commissioning.invalid}</wa-callout>`;
    }
    if (this.codes === null) {
      return html`
        <wa-callout variant="neutral">
          <strong>${t.commissioning.none}</strong>
          <p>${this.answered ? t.commissioning.noneHint : ''}</p>
        </wa-callout>
      `;
    }
    return this.#codes(this.codes);
  }

  #codes(codes: CommissioningCodes) {
    return html`
      <div class="stack">
        <wa-callout variant="warning" appearance="outlined">${t.commissioning.warning}</wa-callout>
        ${
          codes.test_credentials
            ? html`<wa-callout variant="danger" appearance="outlined">
                ${t.commissioning.testCredentials}
              </wa-callout>`
            : null
        }
        <div class="codes">
          <div>
            <wa-qr-code
              value=${codes.qr_payload}
              size="180"
              label=${t.commissioning.qrCode}
            ></wa-qr-code>
          </div>
          <dl>
            <dt>${t.commissioning.manualCode}</dt>
            <dd>
              ${codes.manual_code}
              <wa-copy-button value=${codes.manual_code}></wa-copy-button>
            </dd>
            <dt>${t.commissioning.discriminator}</dt>
            <dd>${codes.discriminator}</dd>
            <dt>${t.commissioning.qrCode}</dt>
            <dd>
              ${codes.qr_payload}
              <wa-copy-button value=${codes.qr_payload}></wa-copy-button>
            </dd>
          </dl>
        </div>
        <div>
          <wa-button appearance="plain" @click=${this.#hide}>${t.commissioning.hide}</wa-button>
        </div>
      </div>
    `;
  }

  #reveal = (): void => {
    this.revealed = true;
    this.dispatchEvent(
      new CustomEvent('commissioning-requested', { bubbles: true, composed: true }),
    );
  };

  #hide = (): void => {
    this.revealed = false;
    this.dispatchEvent(new CustomEvent('commissioning-hidden', { bubbles: true, composed: true }));
  };
}

declare global {
  interface HTMLElementTagNameMap {
    'mh-commissioning': MhCommissioning;
  }
}
