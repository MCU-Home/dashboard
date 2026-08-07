// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Every user-facing string, in one place.
 *
 * Translation is out of scope for this block, but the structure it needs
 * is not: no string is written inline in a template, every one of them
 * is looked up here, and the ones that interpolate are functions rather
 * than concatenations — because word order is the first thing a
 * translation changes. Adding a second locale is then a second object
 * and a lookup, not a sweep through every component.
 *
 * House rule from AGENTS.md: it is **"App"**, never "Add-on". Home
 * Assistant renamed them in 2026.2.
 */

export const t = {
  appName: 'MCUHome',

  connection: {
    connecting: 'Connecting…',
    open: 'Connected',
    reconnecting: 'Reconnecting…',
    refused: 'Not signed in',
    closed: 'Disconnected',
    retry: 'Retry',
    staleNotice:
      'This connection fell behind and the server discarded events for it. Reloading the list.',
  },

  login: {
    title: 'Sign in',
    lead: 'This dashboard is password-protected.',
    password: 'Password',
    submit: 'Sign in',
    working: 'Signing in…',
    failed: 'Wrong password.',
    logout: 'Sign out',
  },

  devices: {
    title: 'Devices',
    empty: 'No devices yet.',
    emptyHint: (root: string) => `Nothing in ${root}/devices. Create one with \`mcuhome new\`.`,
    noTree: 'No configuration tree',
    noTreeHint: (root: string | null) =>
      root === null
        ? 'The dashboard was started without a configuration tree. Point it at one with --config-root.'
        : `${root} is not an MCUHome configuration tree.`,
    loading: 'Loading…',
    board: 'Board',
    endpoints: (count: number) => (count === 1 ? '1 endpoint' : `${count} endpoints`),
    unknownBoard: 'no board set',
    // TODO(block-0): a "New device" action needs `mcuhome new` behind
    // `device/create` (ADR 0011 decision 4). Until then the CLI is the
    // only way to add one, and saying so beats a button that cannot work.
    createHint: 'New devices are created with `mcuhome new <name>` for now.',
  },

  validity: {
    checking: 'checking…',
    ok: 'valid',
    problems: (count: number) => (count === 1 ? '1 problem' : `${count} problems`),
    unknown: 'not checked',
  },

  editor: {
    title: 'Configuration',
    save: 'Save',
    saving: 'Saving…',
    saved: 'Saved',
    unsaved: 'Unsaved changes',
    revert: 'Revert',
    validate: 'Validate',
    validating: 'Validating…',
    valid: 'This configuration is valid.',
    problems: 'Problems',
    noProblems: 'No problems found.',
    loadFailed: (message: string) => `This device could not be opened: ${message}`,
    saveFailed: (message: string) => `This device could not be saved: ${message}`,
    conflictTitle: 'Changed on disk',
    conflictBody:
      'Someone else wrote this file after it was opened here — Studio Code Server, a git checkout, or another tab.',
    conflictReload: 'Discard my changes and reload',
    conflictOverwrite: 'Overwrite what is on disk',
    hint: 'Hint',
  },

  commissioning: {
    title: 'Commissioning',
    lead: 'Add this device to a Matter controller.',
    reveal: 'Show commissioning codes',
    hide: 'Hide',
    warning:
      'These codes let anyone who can see them commission this device. Do not screenshot or share them.',
    testCredentials:
      'These are the credentials published with the Matter SDK. Anyone who knows them can commission this device — bench use only.',
    manualCode: 'Manual pairing code',
    qrCode: 'QR code',
    discriminator: 'Discriminator',
    none: 'This device has no Matter commissioning credentials.',
    noneHint: 'Matter is switched off for it, so there is nothing to commission.',
    invalid: 'The configuration has to be valid before its commissioning codes can be derived.',
    failed: (message: string) => `The commissioning codes could not be read: ${message}`,
  },

  device: {
    notFound: (name: string) => `There is no device called "${name}" in this configuration tree.`,
    back: 'All devices',
    // TODO(block-1): build, flash and log views belong to the build
    // server (ADR 0006) and the flash ladder (ADR 0010).
    buildPending: 'Building and flashing arrive with the build server.',
  },

  theme: {
    label: 'Theme',
    system: 'Follow system',
    light: 'Light',
    dark: 'Dark',
  },

  footer: {
    dashboard: 'Dashboard',
    builder: 'Builder',
    modelVersion: 'Device model',
    tree: 'Configuration tree',
    unknown: 'unknown',
  },

  notFound: {
    title: 'Nothing here',
    body: (path: string) => `"${path}" is not a page in this dashboard.`,
  },
} as const;
