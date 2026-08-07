// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The public site's password form (ADR 0009 decision 2).
 *
 * Only ever shown on the public site, and only after the WebSocket was
 * refused while `GET /health` still answered — a browser cannot read the
 * status of a failed WebSocket handshake, so "refused by a server that
 * is up" is as close to "401" as this side can get.
 *
 * The password goes to `POST /auth/login` and is never kept: what comes
 * back is an `HttpOnly` cookie this code cannot read and a CSRF token
 * that lives in memory.
 */

import { css, html, LitElement } from 'lit';
import { customElement, state } from 'lit/decorators.js';

import { login, LoginError } from '../api/session';
import { t } from '../strings';
import { sharedStyles } from '../styles/shared';

@customElement('mh-login')
export class MhLogin extends LitElement {
  static override styles = [
    sharedStyles,
    css`
      :host {
        display: grid;
        place-items: center;
        padding: var(--wa-space-2xl) var(--wa-space-m);
      }

      wa-card {
        width: min(24rem, 100%);
      }

      form {
        display: flex;
        flex-direction: column;
        gap: var(--wa-space-m);
      }
    `,
  ];

  @state()
  private busy = false;

  @state()
  private error: string | null = null;

  override render() {
    return html`
      <wa-card>
        <h2>${t.login.title}</h2>
        <p class="quiet">${t.login.lead}</p>
        <form @submit=${this.#submit}>
          <wa-input
            type="password"
            name="password"
            label=${t.login.password}
            autocomplete="current-password"
            required
            ?disabled=${this.busy}
          ></wa-input>
          ${
            this.error === null
              ? null
              : html`<wa-callout variant="danger" appearance="filled">${this.error}</wa-callout>`
          }
          <wa-button type="submit" variant="brand" ?loading=${this.busy}>
            ${this.busy ? t.login.working : t.login.submit}
          </wa-button>
        </form>
      </wa-card>
    `;
  }

  #submit = (event: Event): void => {
    event.preventDefault();
    const form = event.target as HTMLFormElement;
    const field = form.querySelector('wa-input');
    const password = (field as unknown as { value?: string } | null)?.value ?? '';
    if (password === '' || this.busy) return;

    this.busy = true;
    this.error = null;
    void login(password)
      .then(() => {
        this.dispatchEvent(new CustomEvent('logged-in', { bubbles: true, composed: true }));
      })
      .catch((cause: unknown) => {
        this.error =
          cause instanceof LoginError && cause.status !== 401 ? cause.message : t.login.failed;
      })
      .finally(() => {
        this.busy = false;
      });
  };
}

declare global {
  interface HTMLElementTagNameMap {
    'mh-login': MhLogin;
  }
}
