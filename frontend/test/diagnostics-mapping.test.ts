// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Where a builder diagnostic lands in the editor.
 *
 * The builder counts lines and columns from 1; CodeMirror addresses
 * document offsets. This conversion is the only place an off-by-one
 * would put every marker one line off, which is worse than no marker at
 * all — so it is tested against the same broken configuration the
 * backend's suite uses (`conftest.BROKEN_CONFIG`: a bad board on line 3,
 * column 10).
 */

import { EditorState } from '@codemirror/state';
import { describe, expect, it } from 'vitest';

import { toCodeMirrorDiagnostics } from '../src/components/mh-yaml-editor';
import type { Diagnostic } from '../src/api/types';

const CONFIG = ['device:', '  name: broken-node', '  board: nrf99dk-does-not-exist', ''].join('\n');

function diagnostic(overrides: Partial<Diagnostic> = {}): Diagnostic {
  return {
    message: '"nrf99dk-does-not-exist" is not a board MCUHome knows.',
    file: 'devices/broken-node/main.yaml',
    line: 3,
    column: 10,
    key: 'device.board',
    hint: 'Run `mcuhome boards` to see the list.',
    kind: 'ConfigError',
    ...overrides,
  };
}

describe('toCodeMirrorDiagnostics', () => {
  it('puts the marker on the line and column the builder named', () => {
    const state = EditorState.create({ doc: CONFIG });
    const [marker] = toCodeMirrorDiagnostics(state, [diagnostic()]);

    const line = state.doc.lineAt(marker!.from);
    expect(line.number).toBe(3);
    expect(marker!.from - line.from).toBe(9); // column 10, 1-based
  });

  it('carries the fix hint into the tooltip, because that is the point', () => {
    const state = EditorState.create({ doc: CONFIG });
    const [marker] = toCodeMirrorDiagnostics(state, [diagnostic()]);
    expect(marker!.message).toContain('Run `mcuhome boards`');
  });

  it('uses the dotted key as the marker source', () => {
    const state = EditorState.create({ doc: CONFIG });
    const [marker] = toCodeMirrorDiagnostics(state, [diagnostic()]);
    expect(marker!.source).toBe('device.board');
  });

  it('anchors a diagnostic without a position at the top rather than dropping it', () => {
    // A BuildError about a missing tool has no line, and is still
    // something the user has to read.
    const state = EditorState.create({ doc: CONFIG });
    const [marker] = toCodeMirrorDiagnostics(state, [
      diagnostic({ line: null, column: null, key: null, hint: null, kind: 'BuildError' }),
    ]);
    expect(marker!.from).toBe(0);
    expect(marker!.source).toBe('BuildError');
  });

  it('clamps a line past the end of a document the user has since shortened', () => {
    const state = EditorState.create({ doc: 'device:\n' });
    const [marker] = toCodeMirrorDiagnostics(state, [diagnostic({ line: 99 })]);
    expect(marker!.from).toBeLessThanOrEqual(state.doc.length);
  });

  it('clamps a column past the end of its line', () => {
    const state = EditorState.create({ doc: CONFIG });
    const [marker] = toCodeMirrorDiagnostics(state, [diagnostic({ column: 999 })]);
    expect(marker!.from).toBe(state.doc.line(3).to);
  });

  it('maps every diagnostic, so one pass produces all the markers', () => {
    const state = EditorState.create({ doc: CONFIG });
    const markers = toCodeMirrorDiagnostics(state, [
      diagnostic({ line: 2 }),
      diagnostic({ line: 3 }),
    ]);
    expect(markers).toHaveLength(2);
    expect(markers.every((marker) => marker.severity === 'error')).toBe(true);
  });
});
