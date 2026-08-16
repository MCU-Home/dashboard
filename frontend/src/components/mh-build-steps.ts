// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * How far along a build is, and what each step of it established.
 *
 * A build is minutes of somebody else's output, and until now this
 * interface answered neither of the two questions a person actually has
 * while it runs: *how far along is it* and *where is this happening*. A
 * spinner and a wall of compiler lines answer neither. The backend has
 * had the material all along — `BuildRequest.on_step` is the builder's
 * honest-progress seam and the record carries what it announced — and
 * this renders it.
 *
 * **Two parts, and they are different kinds of thing.** The bar is
 * *state*: five steps, the one running highlighted, everything before it
 * done. The notes are *findings*: a step that established something says
 * it in one line — which SDK the firmware comes from, which Zephyr line,
 * whether anything patches it, how many endpoints the device has — and
 * that line stays for the rest of the build. It is the same split the
 * command line's live view makes, over the same seam, so that watching a
 * build in a browser and watching one in a terminal are the same
 * experience twice rather than two products.
 *
 * **Nothing here trusts a fact.** `facts` is append-only display
 * material from another repository's vocabulary, so every value is
 * read through a type guard and anything unrecognized is skipped. A line
 * that describes a build must never be the thing that stops it being
 * rendered.
 *
 * The *place* a step runs comes from the record's `method`, which is
 * known before the build starts — never guessed from a fact that may or
 * may not arrive.
 */

import { css, html, LitElement, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';

import type { BuildStep } from '../api/types';
import { t } from '../strings';
import { sharedStyles } from '../styles/shared';

/** The four states this build knows how to draw. */
type KnownState = 'pending' | 'running' | 'done' | 'failed';

/** What each state looks like. The glyphs match the command line's. */
const GLYPHS: Record<KnownState, string> = {
  pending: '·',
  running: '▶',
  done: '✓',
  failed: '✗',
};

/**
 * `state` narrowed to one this file wrote itself, or `null`.
 *
 * A string off the wire is never used to look anything up. `GLYPHS[state]`
 * on a plain object walks the **prototype chain**, so a state of
 * `"constructor"` — nonsense, but the type deliberately admits one,
 * because both sides of this vocabulary may grow — resolves to a function
 * and draws `function Object() { [native code] }` into the bar. The `??`
 * fallback cannot save it: what the lookup found is truthy. Narrowing
 * first is one fix for all three places a state is read — the glyph, the
 * CSS class and the word a screen reader says.
 */
function knownState(state: string): KnownState | null {
  switch (state) {
    case 'pending':
    case 'running':
    case 'done':
    case 'failed':
      return state;
    default:
      return null;
  }
}

/** How many hex digits of a context id are worth showing. */
const ID_DIGITS = 12;

function text(facts: Record<string, unknown>, key: string): string | null {
  const value = facts[key];
  return typeof value === 'string' && value !== '' ? value : null;
}

function count(facts: Record<string, unknown>, key: string): number | null {
  const value = facts[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function flag(facts: Record<string, unknown>, key: string): boolean | null {
  const value = facts[key];
  return typeof value === 'boolean' ? value : null;
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string');
}

function list(facts: Record<string, unknown>, key: string): string[] | null {
  const value = facts[key];
  return isStringArray(value) ? value : null;
}

@customElement('mh-build-steps')
export class MhBuildSteps extends LitElement {
  static override styles = [
    sharedStyles,
    css`
      ol.bar {
        list-style: none;
        margin: 0 0 var(--wa-space-s);
        padding: 0;
        display: flex;
        flex-wrap: wrap;
        gap: var(--wa-space-xs) var(--wa-space-m);
        font-size: var(--wa-font-size-s);
      }

      ol.bar li {
        display: flex;
        align-items: baseline;
        gap: var(--wa-space-2xs);
        color: var(--wa-color-text-quiet);
      }

      .glyph {
        font-family: var(--wa-font-family-code);
      }

      li.running {
        color: var(--wa-color-brand-on-quiet);
        font-weight: var(--wa-font-weight-semibold);
      }

      li.done {
        color: var(--wa-color-success-on-quiet);
      }

      li.failed {
        color: var(--wa-color-danger-on-quiet);
        font-weight: var(--wa-font-weight-semibold);
      }

      .where {
        color: var(--wa-color-text-quiet);
        font-weight: var(--wa-font-weight-normal);
      }

      dl.notes {
        margin: 0;
        display: grid;
        grid-template-columns: auto 1fr;
        gap: var(--wa-space-3xs) var(--wa-space-s);
        font-size: var(--wa-font-size-s);
      }

      dl.notes dt {
        color: var(--wa-color-text-quiet);
        white-space: nowrap;
      }

      dl.notes dd {
        margin: 0;
        overflow-wrap: anywhere;
      }
    `,
  ];

  @property({ attribute: false })
  steps: readonly BuildStep[] = [];

  /** Which build method ran — where the middle steps happen. */
  @property({ type: String })
  method = '';

  override render() {
    if (this.steps.length === 0) return nothing;
    const noted = this.steps
      .map((step) => ({ step, parts: this.#facts(step) }))
      .filter((entry) => entry.parts.length > 0);
    return html`
      <ol class="bar" aria-label=${t.build.steps.label}>
        ${this.steps.map((step) => this.#chip(step))}
      </ol>
      ${
        noted.length === 0
          ? nothing
          : html`<dl class="notes">
              ${noted.map(
                (entry) => html`
                  <dt>${this.#name(entry.step)}</dt>
                  <dd>${entry.parts.join(' · ')}</dd>
                `,
              )}
            </dl>`
      }
    `;
  }

  // -- the bar -----------------------------------------------------

  #chip(step: BuildStep) {
    const states = t.build.steps.state;
    const state = knownState(step.state);
    // A state this build does not know draws like one not reached yet —
    // but it is *said* as "unknown", never as "not started": the two are
    // different claims, and only one of them is true.
    const look: KnownState = state ?? 'pending';
    const word = state === null ? states.unknown : states[state];
    const where = this.#where(step);
    return html`
      <li class=${look} aria-label=${t.build.steps.stepLabel(this.#name(step), word)}>
        <span class="glyph" aria-hidden="true">${GLYPHS[look]}</span>
        <span>${this.#name(step)}</span>
        ${where === null ? nothing : html`<span class="where">(${where})</span>`}
      </li>
    `;
  }

  #name(step: BuildStep): string {
    const names = t.build.steps;
    switch (step.key) {
      case 'validate':
        return names.validate;
      case 'context':
        return names.context;
      case 'compile':
        return names.compile;
      case 'artifacts':
        return names.artifacts;
      case 'sign':
        return names.sign;
      default:
        return names.unknown(step.key);
    }
  }

  /**
   * Where this step happens, or `null` when saying so adds nothing.
   *
   * Only the two steps that can happen somewhere else are labelled. The
   * others run in this process by construction, and writing "dashboard"
   * on all five would turn the one distinction worth making into
   * decoration.
   */
  #where(step: BuildStep): string | null {
    const names = t.build.steps;
    if (step.key === 'sign') return names.here;
    if (step.key !== 'compile') return null;
    switch (this.method) {
      case 'local':
        return names.inContainer;
      case 'remote':
        return names.onServer;
      case 'local-dev':
        return names.inWorkspace;
      default:
        return null;
    }
  }

  // -- the notes ---------------------------------------------------

  #facts(step: BuildStep): string[] {
    const facts = step.facts as Record<string, unknown> | undefined;
    if (facts === undefined || facts === null) return [];
    switch (step.key) {
      case 'validate':
        return this.#validateFacts(facts);
      case 'context':
        return this.#contextFacts(facts);
      case 'compile':
        return this.#compileFacts(facts);
      default:
        return [];
    }
  }

  #validateFacts(facts: Record<string, unknown>): string[] {
    const parts: string[] = [];
    const board = text(facts, 'board');
    if (board !== null) parts.push(board);
    const transport = text(facts, 'transport');
    const matter = flag(facts, 'matter');
    // The transport line is worth printing even without a transport —
    // "no network" is a fact about this device, not a missing value.
    if (transport !== null || matter !== null) {
      parts.push(t.build.facts.transport(transport, text(facts, 'thread_role')));
    }
    if (matter !== null) parts.push(matter ? t.build.facts.matterOn : t.build.facts.matterOff);
    const endpoints = count(facts, 'endpoints');
    if (endpoints !== null) parts.push(t.build.facts.endpoints(endpoints));
    const channels = count(facts, 'channels');
    if (channels !== null) parts.push(t.build.facts.channels(channels));
    return parts;
  }

  #contextFacts(facts: Record<string, unknown>): string[] {
    const parts: string[] = [];
    const sdk = text(facts, 'sdk');
    if (sdk !== null) parts.push(t.build.facts.sdk(sdk));
    const zephyr = text(facts, 'zephyr');
    if (zephyr !== null) parts.push(t.build.facts.zephyr(zephyr));
    const patches = list(facts, 'patches');
    if (patches !== null) parts.push(t.build.facts.patches(patches));
    const files = count(facts, 'files');
    if (files !== null) parts.push(t.build.facts.files(files));
    const identity = text(facts, 'id');
    if (identity !== null) {
      // `sha256:<hex>`; the algorithm is the same for every build and the
      // full digest is in the manifest. What a person compares is the
      // head of the hex.
      const digits = identity.slice(identity.indexOf(':') + 1).slice(0, ID_DIGITS);
      if (digits !== '') parts.push(t.build.facts.contextId(digits));
    }
    return parts;
  }

  #compileFacts(facts: Record<string, unknown>): string[] {
    const parts: string[] = [];
    const image = text(facts, 'image');
    if (image !== null) parts.push(t.build.facts.image(image));
    const jobs = count(facts, 'jobs');
    if (jobs !== null) parts.push(t.build.facts.jobs(jobs));
    return parts;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'mh-build-steps': MhBuildSteps;
  }
}
