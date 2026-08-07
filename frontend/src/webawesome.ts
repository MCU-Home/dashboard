// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * The Web Awesome components this dashboard uses, and only those.
 *
 * `@home-assistant/webawesome` is Home Assistant's fork of Web Awesome —
 * the component library their own frontend is built from — so importing
 * it is how the native Home Assistant look arrives for free rather than
 * being reimplemented (ADR 0005 decision 2). Each module registers its
 * own custom element as a side effect; importing the package root would
 * register all sixty of them.
 *
 * **No `wa-icon`.** Its default icon family is fetched from a CDN at
 * runtime, and this application has to work on a LAN with no route to
 * the internet — an iframe inside a Home Assistant that a firewall keeps
 * off the public network is the normal case, not the edge case. Inline
 * SVG where an icon is genuinely needed.
 */

import '@home-assistant/webawesome/dist/styles/webawesome.css';

import '@home-assistant/webawesome/dist/components/badge/badge.js';
import '@home-assistant/webawesome/dist/components/button/button.js';
import '@home-assistant/webawesome/dist/components/callout/callout.js';
import '@home-assistant/webawesome/dist/components/card/card.js';
import '@home-assistant/webawesome/dist/components/copy-button/copy-button.js';
import '@home-assistant/webawesome/dist/components/divider/divider.js';
import '@home-assistant/webawesome/dist/components/input/input.js';
import '@home-assistant/webawesome/dist/components/option/option.js';
import '@home-assistant/webawesome/dist/components/qr-code/qr-code.js';
import '@home-assistant/webawesome/dist/components/select/select.js';
import '@home-assistant/webawesome/dist/components/spinner/spinner.js';
import '@home-assistant/webawesome/dist/components/tag/tag.js';
