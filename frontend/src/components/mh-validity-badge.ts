// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Whether one device resolves, as a badge.
 *
 * `failed` deliberately renders as "not checked" rather than as a
 * problem: the command did not come back, which says something about the
 * connection and nothing about the configuration. Showing a red badge
 * for it would be the UI inventing a verdict the builder never gave.
 */

import { html, LitElement } from 'lit';
import { customElement, property } from 'lit/decorators.js';

import type { Validity } from '../state/validity';
import { t } from '../strings';
import { sharedStyles } from '../styles/shared';

@customElement('mh-validity-badge')
export class MhValidityBadge extends LitElement {
  static override styles = [sharedStyles];

  @property({ attribute: false })
  validity: Validity = { status: 'unknown', errorCount: 0, errors: [] };

  override render() {
    switch (this.validity.status) {
      case 'ok':
        return html`<wa-badge variant="success" appearance="filled" pill
          >${t.validity.ok}</wa-badge
        >`;
      case 'invalid':
        return html`<wa-badge variant="danger" appearance="filled" pill>
          ${t.validity.problems(this.validity.errorCount)}
        </wa-badge>`;
      case 'checking':
        return html`<wa-badge variant="neutral" appearance="outlined" pill>
          ${t.validity.checking}
        </wa-badge>`;
      default:
        return html`<wa-badge variant="neutral" appearance="outlined" pill>
          ${t.validity.unknown}
        </wa-badge>`;
    }
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'mh-validity-badge': MhValidityBadge;
  }
}
