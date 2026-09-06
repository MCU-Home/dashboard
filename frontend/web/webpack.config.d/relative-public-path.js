// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0
//
// Every file webpack emits a URL for — the JavaScript chunks, the
// WebAssembly module, the Skia runtime — is addressed relative to the
// document instead of relative to the site root. Without this the
// generated loader asks for "/mcuhome-ui.wasm", which only resolves when
// the application is served at the root; with it the same build also
// works under a base path.
config.output = config.output || {};
config.output.publicPath = "";
