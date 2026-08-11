// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vite';

/**
 * Where `pnpm dev` sends everything that is not a frontend asset.
 *
 * The dev server is a static file server with a proxy in front; the
 * backend is a separate process the developer starts themselves. Point
 * it somewhere else with `MCUHOME_DASHBOARD_URL=http://host:port pnpm dev`.
 */
const backend = process.env.MCUHOME_DASHBOARD_URL ?? 'http://127.0.0.1:8099';

export default defineConfig({
  // Relative asset URLs, so the `<base href>` the backend injects per
  // request decides what they resolve against (ADR 0005 decision 4).
  // Nothing in the bundle may name an absolute path: the same build has
  // to serve ingress, a reverse-proxy sub-path and a bare root.
  base: './',

  build: {
    outDir: 'dist',
    target: 'es2022',
    sourcemap: true,
    // A dashboard is loaded once and kept open; splitting it further
    // trades a smaller first paint for more round trips over an ingress
    // proxy, which is the slower half of the connection.
    chunkSizeWarningLimit: 1200,
  },

  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/ws': { target: backend, ws: true },
      '/health': { target: backend },
      '/auth': { target: backend },
      // The artifact route (ADR 0013 decision 5). Without a rule here the
      // SPA fallback answers a firmware download with 200 and index.html,
      // and `<a download>` saves the shell HTML as firmware.signed.bin —
      // an error that looks like a corrupt image and reports nothing.
      '/api': { target: backend },
    },
  },
});
