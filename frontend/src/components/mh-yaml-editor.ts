// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * CodeMirror 6, wrapped as a Lit element.
 *
 * ADR 0005 decision 3 rejects Monaco and any form of embedded VS Code:
 * a browser tab is not where a full IDE belongs, and the weight buys
 * nothing an editing session needs. The power-user path is a future
 * MCUHome VS Code extension talking to the same WebSocket API, not a
 * heavier in-page editor.
 *
 * Two things are worth knowing about this file.
 *
 * **The diagnostics are the builder's.** They arrive from
 * `device/validate` with file, line, column, dotted key and a fix hint
 * (ADR 0004 decision 5), and are converted straight into CodeMirror
 * diagnostics — nothing here decides what is valid, and nothing may
 * start to. The configuration schema is the firmware repository's
 * contract.
 *
 * **The theme is CSS custom properties, not two themes.** Every colour
 * below is a `--wa-color-*` variable, so the editor follows the
 * `wa-light`/`wa-dark` class on the root element with no reconfiguration
 * at all; the one thing CodeMirror needs told explicitly is
 * `EditorView.darkTheme`, which some extensions read to pick contrast.
 */

import {
  autocompletion,
  closeBrackets,
  closeBracketsKeymap,
  completionKeymap,
} from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { yaml } from '@codemirror/lang-yaml';
import {
  bracketMatching,
  foldGutter,
  foldKeymap,
  HighlightStyle,
  indentOnInput,
  syntaxHighlighting,
} from '@codemirror/language';
import type { Diagnostic as CmDiagnostic } from '@codemirror/lint';
import { lintGutter, lintKeymap, setDiagnostics } from '@codemirror/lint';
import { highlightSelectionMatches, searchKeymap } from '@codemirror/search';
import { Compartment, EditorState } from '@codemirror/state';
import {
  drawSelection,
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  keymap,
  lineNumbers,
  rectangularSelection,
} from '@codemirror/view';
import { tags } from '@lezer/highlight';
import { css, html, LitElement } from 'lit';
import { customElement, property } from 'lit/decorators.js';

import type { Diagnostic } from '../api/types';

const editorTheme = EditorView.theme({
  '&': {
    color: 'var(--wa-color-text-normal)',
    backgroundColor: 'var(--wa-color-surface-default)',
    fontSize: 'var(--wa-font-size-s)',
    height: '100%',
  },
  '.cm-content': {
    fontFamily: 'var(--wa-font-family-code)',
    caretColor: 'var(--wa-color-text-normal)',
  },
  '.cm-gutters': {
    backgroundColor: 'var(--wa-color-surface-lowered)',
    color: 'var(--wa-color-text-quiet)',
    border: 'none',
    borderInlineEnd: '1px solid var(--wa-color-surface-border)',
  },
  '.cm-activeLine': { backgroundColor: 'var(--wa-color-neutral-fill-quiet)' },
  '.cm-activeLineGutter': { backgroundColor: 'var(--wa-color-neutral-fill-quiet)' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground, ::selection': {
    backgroundColor: 'var(--wa-color-brand-fill-normal)',
  },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--wa-color-text-normal)' },
  '.cm-lintRange-error': { backgroundImage: 'none', textDecoration: 'underline wavy currentColor' },
});

const highlightStyle = HighlightStyle.define([
  { tag: tags.comment, color: 'var(--wa-color-text-quiet)', fontStyle: 'italic' },
  {
    tag: [tags.propertyName, tags.definition(tags.propertyName)],
    color: 'var(--wa-color-brand-on-quiet)',
  },
  { tag: [tags.string, tags.special(tags.string)], color: 'var(--wa-color-success-on-quiet)' },
  { tag: [tags.number, tags.bool, tags.null], color: 'var(--wa-color-warning-on-quiet)' },
  { tag: tags.keyword, color: 'var(--wa-color-danger-on-quiet)' },
  { tag: tags.meta, color: 'var(--wa-color-text-quiet)' },
]);

/**
 * Turn the builder's diagnostics into CodeMirror's.
 *
 * The builder counts lines and columns from 1 and CodeMirror addresses
 * document offsets, so the conversion is where an off-by-one would put
 * every marker on the wrong line. A diagnostic without a position — a
 * `BuildError` about a missing tool, say — is anchored at the start of
 * the document rather than dropped: it is still something the user has
 * to read.
 */
export function toCodeMirrorDiagnostics(
  state: EditorState,
  diagnostics: readonly Diagnostic[],
): CmDiagnostic[] {
  const lineCount = state.doc.lines;
  return diagnostics.map((diagnostic) => {
    const lineNumber = Math.min(Math.max(diagnostic.line ?? 1, 1), lineCount);
    const line = state.doc.line(lineNumber);
    const column = Math.max(diagnostic.column ?? 1, 1);
    const from = Math.min(line.from + column - 1, line.to);
    return {
      from,
      to: line.to > from ? line.to : from,
      severity: 'error' as const,
      source: diagnostic.key ?? diagnostic.kind,
      message: diagnostic.hint ? `${diagnostic.message}\n\n${diagnostic.hint}` : diagnostic.message,
    };
  });
}

@customElement('mh-yaml-editor')
export class MhYamlEditor extends LitElement {
  static override styles = css`
    :host {
      display: block;
      border: 1px solid var(--wa-color-surface-border);
      border-radius: var(--wa-border-radius-m);
      overflow: hidden;
    }

    .host {
      height: var(--mh-editor-height, 30rem);
      overflow: auto;
    }

    .cm-editor {
      height: 100%;
    }

    .cm-editor.cm-focused {
      outline: none;
    }
  `;

  /** The document. Assigning it replaces the editor's contents. */
  @property({ attribute: false })
  value = '';

  /** Server-side diagnostics, rendered as gutter markers and underlines. */
  @property({ attribute: false })
  diagnostics: readonly Diagnostic[] = [];

  @property({ type: Boolean })
  readonly = false;

  @property({ type: Boolean, attribute: 'dark-theme' })
  darkTheme = false;

  #view: EditorView | null = null;
  readonly #readonlyCompartment = new Compartment();
  readonly #themeCompartment = new Compartment();

  /** What the user has typed, which may differ from {@link value}. */
  get document(): string {
    return this.#view?.state.doc.toString() ?? this.value;
  }

  override render() {
    return html`<div class="host"></div>`;
  }

  override firstUpdated(): void {
    const host = this.renderRoot.querySelector('.host');
    if (host === null) return;

    this.#view = new EditorView({
      parent: host,
      state: EditorState.create({
        doc: this.value,
        extensions: [
          lineNumbers(),
          highlightActiveLineGutter(),
          highlightSpecialChars(),
          history(),
          foldGutter(),
          drawSelection(),
          indentOnInput(),
          bracketMatching(),
          closeBrackets(),
          rectangularSelection(),
          highlightActiveLine(),
          highlightSelectionMatches(),
          lintGutter(),
          syntaxHighlighting(highlightStyle),
          yaml(),
          // TODO(block-0): schema-aware completion needs the registry
          // and JSON-Schema export from the firmware repository (ADR
          // 0011 decision 4). Until it exists this is CodeMirror's
          // word-based completion only — the schema may not be
          // reimplemented here, because the firmware repository owns it
          //.
          autocompletion(),
          keymap.of([
            ...closeBracketsKeymap,
            ...defaultKeymap,
            ...searchKeymap,
            ...historyKeymap,
            ...foldKeymap,
            ...completionKeymap,
            ...lintKeymap,
            indentWithTab,
          ]),
          editorTheme,
          this.#themeCompartment.of(EditorView.darkTheme.of(this.darkTheme)),
          this.#readonlyCompartment.of(EditorState.readOnly.of(this.readonly)),
          EditorView.updateListener.of((update) => {
            if (!update.docChanged) return;
            this.dispatchEvent(
              new CustomEvent('editor-change', {
                detail: { value: update.state.doc.toString() },
                bubbles: true,
                composed: true,
              }),
            );
          }),
        ],
      }),
    });
    this.#applyDiagnostics();
  }

  override updated(changed: Map<string, unknown>): void {
    const view = this.#view;
    if (view === null) return;

    if (changed.has('value')) {
      this.setDocument(this.value);
    }
    if (changed.has('readonly')) {
      view.dispatch({
        effects: this.#readonlyCompartment.reconfigure(EditorState.readOnly.of(this.readonly)),
      });
    }
    if (changed.has('darkTheme')) {
      view.dispatch({
        effects: this.#themeCompartment.reconfigure(EditorView.darkTheme.of(this.darkTheme)),
      });
    }
    if (changed.has('diagnostics') || changed.has('value')) {
      this.#applyDiagnostics();
    }
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.#view?.destroy();
    this.#view = null;
  }

  /**
   * Replace the document with *text*.
   *
   * Needed as a method and not only as the `value` property, because
   * "revert" restores a value the property already holds: the user typed
   * over it, so `value` never changed and no re-render would reach the
   * editor. Comparing against the live document rather than against the
   * property is what makes both paths work, and makes a no-op save leave
   * the cursor where it was.
   */
  setDocument(text: string): void {
    const view = this.#view;
    if (view === null) {
      this.value = text;
      return;
    }
    if (view.state.doc.toString() === text) return;
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text } });
  }

  /** Put the cursor on a diagnostic and scroll it into view. */
  revealLine(line: number, column: number | null): void {
    const view = this.#view;
    if (view === null) return;
    const target = view.state.doc.line(Math.min(Math.max(line, 1), view.state.doc.lines));
    const position = Math.min(target.from + Math.max((column ?? 1) - 1, 0), target.to);
    view.dispatch({
      selection: { anchor: position },
      scrollIntoView: true,
    });
    view.focus();
  }

  #applyDiagnostics(): void {
    const view = this.#view;
    if (view === null) return;
    view.dispatch(
      setDiagnostics(view.state, toCodeMirrorDiagnostics(view.state, this.diagnostics)),
    );
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'mh-yaml-editor': MhYamlEditor;
  }
}
