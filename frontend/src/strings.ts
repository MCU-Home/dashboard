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

  build: {
    title: 'Firmware',
    lead: 'Compile this device and download what it produced.',
    start: 'Build firmware',
    starting: 'Starting…',
    cancel: 'Cancel build',
    cancelling: 'Cancelling…',
    never: 'This device has not been built since the dashboard started.',
    method: (name: string) => `Method: ${name}`,
    queued: 'Queued',
    running: 'Building',
    succeeded: 'Succeeded',
    failed: 'Failed',
    cancelled: 'Cancelled',
    unknownState: 'Unknown',
    elapsed: (duration: string) => `running for ${duration}`,
    took: (duration: string) => `took ${duration}`,
    duration: (seconds: number) => {
      const total = Math.max(0, Math.round(seconds));
      if (total < 60) return `${total} s`;
      const minutes = Math.floor(total / 60);
      if (minutes < 60) return `${minutes} min ${total % 60} s`;
      return `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
    },
    log: 'Build output',
    logEmpty: 'No output yet.',
    logTruncated: '…earlier output was discarded.',
    problems: 'Why this build failed',
    artifacts: 'Artifacts',
    noArtifacts: 'This build produced no files to download.',
    signed: 'signed',
    ota: (version: string, softwareVersion: number) =>
      `Matter OTA image for version ${version} (software version ${softwareVersion}).`,
    createdKeyTitle: 'A firmware signing key was created',
    createdKeyBody:
      'This deployment had no signing key, so one was generated for this build. Back it up: it is the only key these devices will accept an update from, and every device already bootstrapped against it is unreachable if it is lost.',
    startFailed: (message: string) => `This build could not be started: ${message}`,
    busy: (device: string) =>
      `A build of "${device}" is already running. This dashboard builds one device at a time.`,
    busyCancelled: (device: string) =>
      `The build of "${device}" was cancelled, but the work it started has not ended yet — a container cannot be interrupted. The next build can start when it has.`,
    cancelFailed: (message: string) => `This build could not be cancelled: ${message}`,
    size: (bytes: number) => {
      if (bytes < 1024) return `${bytes} B`;
      const kibibytes = bytes / 1024;
      if (kibibytes < 1024) return `${kibibytes.toFixed(1)} KiB`;
      return `${(kibibytes / 1024).toFixed(1)} MiB`;
    },
  },

  device: {
    notFound: (name: string) => `There is no device called "${name}" in this configuration tree.`,
    back: 'All devices',
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
