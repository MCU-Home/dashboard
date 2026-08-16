// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Every device in the configuration tree.
 *
 * Fed from the snapshot-then-events store, so it is current without ever
 * polling: a file edited by Studio Code Server or a git checkout redraws
 * a row here without this component asking anybody anything.
 *
 * A pure render of what it is given — the store, the validity tracker
 * and the WebSocket all belong to `mh-app`. That is what keeps this
 * testable without a server.
 */

import { html, LitElement, css, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { repeat } from 'lit/directives/repeat.js';

import type { DeviceEntry, TreeState } from '../api/types';
import { links } from '../links';
import { deviceHref } from '../router';
import type { Validity } from '../state/validity';
import { t } from '../strings';
import { sharedStyles } from '../styles/shared';

import './mh-validity-badge';

@customElement('mh-device-list')
export class MhDeviceList extends LitElement {
  static override styles = [
    sharedStyles,
    css`
      ul {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: var(--wa-space-s);
      }

      li a {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--wa-space-m);
        flex-wrap: wrap;
        padding: var(--wa-space-m);
        border: 1px solid var(--wa-color-surface-border);
        border-radius: var(--wa-border-radius-m);
        background: var(--wa-color-surface-default);
        color: inherit;
        text-decoration: none;
      }

      li a:hover {
        border-color: var(--wa-color-brand-border-loud);
      }

      .name {
        font-weight: var(--wa-font-weight-semibold);
      }

      .meta {
        color: var(--wa-color-text-quiet);
        font-size: var(--wa-font-size-s);
      }
    `,
  ];

  @property({ attribute: false })
  devices: readonly DeviceEntry[] = [];

  @property({ attribute: false })
  tree: TreeState | null = null;

  @property({ type: Boolean })
  loaded = false;

  /**
   * Whether this is the Home Assistant App rather than a standalone
   * deployment. It changes who repairs an unusable project, and that is
   * the only thing it is used for here.
   */
  @property({ type: Boolean })
  ingress = false;

  /** Looked up per row, so a verdict arriving late redraws one badge. */
  @property({ attribute: false })
  validityOf: (name: string) => Validity = () => ({ status: 'unknown', errorCount: 0, errors: [] });

  override render() {
    if (this.tree !== null && !this.tree.available) {
      return this.#renderUnusable(this.tree);
    }
    if (!this.loaded) {
      return html`<p class="quiet">${t.devices.loading}</p>`;
    }
    if (this.devices.length === 0) {
      return html`
        <wa-callout variant="neutral">
          <strong>${t.devices.empty}</strong>
          <p>
            ${this.tree?.root == null ? t.devices.createHint : t.devices.emptyHint(this.tree.root)}
          </p>
        </wa-callout>
      `;
    }

    return html`
      <ul>
        ${repeat(
          this.devices,
          (device) => device.name,
          (device) => this.#row(device),
        )}
      </ul>
    `;
  }

  #row(device: DeviceEntry) {
    const summary = device.summary;
    const board = summary.board ?? null;
    return html`
      <li>
        <a href=${deviceHref(device.name)}>
          <span>
            <span class="name">${summary.friendly_name ?? device.name}</span>
            <br />
            <span class="meta mono">
              ${board ?? t.devices.unknownBoard} ·
              ${t.devices.endpoints(summary.endpoint_count ?? 0)}
            </span>
          </span>
          <mh-validity-badge .validity=${this.validityOf(device.name)}></mh-validity-badge>
        </a>
      </li>
    `;
  }

  /**
   * An unusable tree, and what the person looking at it can do.
   *
   * No device is listed in any of these cases on purpose: every command
   * touching one would be refused, and a list of devices that cannot be
   * opened is a worse answer than none.
   */
  #renderUnusable(tree: TreeState) {
    const problem = tree.problem ?? null;
    const { root } = tree;

    if (problem === null || problem.code === 'no_project') {
      return html`
        <wa-callout variant="warning">
          <strong>${t.devices.noTree}</strong>
          <p>${t.devices.noTreeHint(root)}</p>
        </wa-callout>
      `;
    }

    switch (problem.code) {
      case 'project_upgrade_required':
        return this.#callout(
          'warning',
          t.devices.projectUnusable,
          t.devices.upgradeRequired(problem.project_version ?? 0, problem.expected_version ?? 0),
          t.devices.upgradeHow(root, this.ingress),
          links.projectUpgrade,
          t.devices.upgradeLink,
        );
      case 'project_version_unsupported':
        return this.#callout(
          'warning',
          t.devices.projectUnusable,
          t.devices.versionUnsupported(problem.project_version ?? 0, problem.expected_version ?? 0),
          t.devices.versionUnsupportedHow(this.ingress),
        );
      case 'project_upgrading':
        return this.#callout('neutral', t.devices.projectUpgrading, t.devices.projectUpgradingHow);
      default:
        // `project_file_unreadable`, and anything a newer backend
        // invents: say the honest general thing rather than nothing.
        return this.#callout(
          'danger',
          t.devices.projectUnreadable,
          t.devices.projectUnreadableHow(root),
        );
    }
  }

  #callout(
    variant: 'warning' | 'neutral' | 'danger',
    title: string,
    body: string,
    how?: string,
    href?: string,
    linkText?: string,
  ) {
    return html`
      <wa-callout variant=${variant}>
        <strong>${title}</strong>
        <p>${body}</p>
        ${how ? html`<p>${how}</p>` : nothing}
        ${
          href && linkText
            ? html`<p><a href=${href} target="_blank" rel="noreferrer noopener">${linkText}</a></p>`
            : nothing
        }
      </wa-callout>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'mh-device-list': MhDeviceList;
  }
}
