// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * What the one connection is doing, said out loud.
 *
 * ADR 0004: one WebSocket carries the entire UI. When it is not up, the
 * device list is not stale-but-plausible — it is not being maintained at
 * all, and hiding that behind a UI that still looks alive is how a user
 * ends up editing a file they think they are looking at.
 */

import { css, html, LitElement } from 'lit';
import { customElement, property } from 'lit/decorators.js';

import type { ConnectionState } from '../api/client';
import { t } from '../strings';
import { sharedStyles } from '../styles/shared';

const VARIANT: Record<ConnectionState, string> = {
  connecting: 'neutral',
  open: 'success',
  reconnecting: 'warning',
  refused: 'danger',
  closed: 'danger',
};

@customElement('mh-connection-status')
export class MhConnectionStatus extends LitElement {
  static override styles = [
    sharedStyles,
    css`
      :host {
        display: inline-flex;
      }

      wa-tag::part(base) {
        white-space: nowrap;
      }
    `,
  ];

  @property({ type: String })
  state: ConnectionState = 'connecting';

  override render() {
    return html`
      <wa-tag variant=${VARIANT[this.state]} appearance="filled" size="s" pill>
        ${t.connection[this.state]}
      </wa-tag>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'mh-connection-status': MhConnectionStatus;
  }
}
