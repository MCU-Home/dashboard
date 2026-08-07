// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The builder's diagnostics as a panel next to the editor's gutter.
 *
 * The gutter markers say *where*; this says *what*, in full, with the
 * fix hint the builder attached and the dotted configuration key it
 * belongs to. Both come from the same `ConfigError` (ADR 0004 decision
 * 5) — clicking a row moves the editor's cursor to it, which is the only
 * reason the two views need each other at all.
 */

import { css, html, LitElement } from 'lit';
import { customElement, property } from 'lit/decorators.js';

import type { Diagnostic } from '../api/types';
import { t } from '../strings';
import { sharedStyles } from '../styles/shared';

@customElement('mh-diagnostics')
export class MhDiagnostics extends LitElement {
  static override styles = [
    sharedStyles,
    css`
      ol {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: var(--wa-space-xs);
      }

      button {
        display: block;
        width: 100%;
        text-align: start;
        font: inherit;
        color: inherit;
        cursor: pointer;
        padding: var(--wa-space-s) var(--wa-space-m);
        border: 1px solid var(--wa-color-danger-border-quiet);
        border-inline-start-width: 3px;
        border-inline-start-color: var(--wa-color-danger-fill-loud);
        border-radius: var(--wa-border-radius-s);
        background: var(--wa-color-danger-fill-quiet);
      }

      button:hover {
        border-color: var(--wa-color-danger-border-loud);
      }

      .where {
        font-family: var(--wa-font-family-code);
        font-size: var(--wa-font-size-xs);
        color: var(--wa-color-text-quiet);
      }

      .hint {
        margin-block-start: var(--wa-space-2xs);
        font-size: var(--wa-font-size-s);
        color: var(--wa-color-text-quiet);
      }
    `,
  ];

  @property({ attribute: false })
  diagnostics: readonly Diagnostic[] = [];

  override render() {
    if (this.diagnostics.length === 0) {
      return html`<p class="quiet">${t.editor.noProblems}</p>`;
    }
    return html`
      <ol>
        ${this.diagnostics.map((diagnostic, index) => this.#row(diagnostic, index))}
      </ol>
    `;
  }

  #row(diagnostic: Diagnostic, index: number) {
    const where = [diagnostic.file, diagnostic.line === null ? null : `line ${diagnostic.line}`]
      .filter((part) => part !== null)
      .join(' · ');
    return html`
      <li>
        <button type="button" @click=${() => this.#reveal(diagnostic, index)}>
          <div>${diagnostic.message}</div>
          <div class="where">${where}${diagnostic.key === null ? '' : ` · ${diagnostic.key}`}</div>
          ${
            diagnostic.hint === null
              ? null
              : html`<div class="hint"><strong>${t.editor.hint}:</strong> ${diagnostic.hint}</div>`
          }
        </button>
      </li>
    `;
  }

  #reveal(diagnostic: Diagnostic, index: number): void {
    this.dispatchEvent(
      new CustomEvent('diagnostic-selected', {
        detail: { diagnostic, index },
        bubbles: true,
        composed: true,
      }),
    );
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'mh-diagnostics': MhDiagnostics;
  }
}
