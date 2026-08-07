// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Styles more than one component needs.
 *
 * Everything here is expressed in Web Awesome's custom properties rather
 * than in colours of our own: they are what the `wa-light`/`wa-dark`
 * class on the root element switches, so a component styled this way
 * follows the theme without knowing that a theme exists.
 */

import { css } from 'lit';

export const sharedStyles = css`
  :host {
    display: block;
    color: var(--wa-color-text-normal);
    font-family: var(--wa-font-family-body);
  }

  a {
    color: var(--wa-color-text-link);
  }

  h2 {
    font-size: var(--wa-font-size-l);
    font-weight: var(--wa-font-weight-semibold);
    margin: 0 0 var(--wa-space-s);
  }

  p {
    margin: 0 0 var(--wa-space-s);
  }

  .quiet {
    color: var(--wa-color-text-quiet);
  }

  .mono {
    font-family: var(--wa-font-family-code);
    font-size: var(--wa-font-size-s);
  }

  .row {
    display: flex;
    align-items: center;
    gap: var(--wa-space-s);
    flex-wrap: wrap;
  }

  .spread {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--wa-space-s);
    flex-wrap: wrap;
  }

  .stack {
    display: flex;
    flex-direction: column;
    gap: var(--wa-space-m);
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    margin: -1px;
    padding: 0;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
`;
