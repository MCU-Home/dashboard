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

    // The project is there but unusable. The dashboard is pointed at a
    // project somebody else created and does not manage one itself, so
    // every answer here ends with who does — and that differs by
    // deployment, which is why these take `ingress`.
    projectUnusable: 'This project cannot be opened',
    upgradeRequired: (from: number, to: number) =>
      `The project was written by older tools (project version ${from}; these tools need ${to}).`,
    upgradeHow: (root: string | null, ingress: boolean) =>
      ingress
        ? 'The Home Assistant App upgrades the project when it starts, so this should not appear. Restarting the App is the first thing to try.'
        : `Back the project up, then upgrade it with the MCUHome command line: \`mcuhome project upgrade ${root ?? ''}\`.`.trim(),
    upgradeLink: 'What the upgrade changes',
    versionUnsupported: (from: number, to: number) =>
      `The project was written by newer tools than these (project version ${from}; these tools understand ${to}).`,
    versionUnsupportedHow: (ingress: boolean) =>
      ingress
        ? 'Update the Home Assistant App to a version that understands this project.'
        : 'Update the dashboard to a version that understands this project. Downgrading the project is not possible.',
    projectUpgrading: 'An upgrade is working on this project',
    projectUpgradingHow:
      'The project is held until it finishes. If nothing is running, the upgrade was interrupted and the project has to be restored from its backup.',
    projectUnreadable: 'The project file cannot be read',
    projectUnreadableHow: (root: string | null) =>
      `Check ${root ?? 'the project directory'} — its marker file is missing, unreadable or not valid.`,
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
    /**
     * The step bar, and the one line each step leaves behind.
     *
     * The names say what the step *does*, not what the code calls it,
     * and each one that runs somewhere says where — which is half of
     * why a person watches a build at all: a fifteen-minute silence is
     * a compile in a container, not a hang.
     */
    steps: {
      title: 'Progress',
      validate: 'Check configuration',
      context: 'Collect sources',
      compile: 'Compile',
      artifacts: 'Collect files',
      sign: 'Sign',
      /** A step from a newer backend: its own name is the honest label. */
      unknown: (key: string) => key,
      state: {
        pending: 'not started',
        running: 'running',
        done: 'done',
        failed: 'failed',
        unknown: 'unknown',
      },
      /** Where a step runs, appended to its name. */
      inContainer: 'build container',
      onServer: 'build server',
      inWorkspace: 'local workspace',
      here: 'dashboard',
      /** The whole bar, for a screen reader that reads it as one thing. */
      label: 'Build progress',
      stepLabel: (name: string, state: string) => `${name}: ${state}`,
    },

    /**
     * What a step established, in a person's words.
     *
     * Each returns the *parts* of a line rather than the line, because
     * a fact that did not arrive should leave no gap and no stray
     * separator — the caller joins what it got.
     */
    facts: {
      transport: (transport: string | null, threadRole: string | null) => {
        if (transport === null) return 'no network';
        if (transport !== 'thread' || threadRole === null) return transport;
        return threadRole === 'ftd' ? 'Thread router' : 'Thread end device';
      },
      matterOn: 'Matter on',
      matterOff: 'Matter off',
      endpoints: (count: number) => `${count} ${count === 1 ? 'endpoint' : 'endpoints'}`,
      channels: (count: number) => `${count} ${count === 1 ? 'channel' : 'channels'}`,
      sdk: (version: string) => `SDK ${version}`,
      zephyr: (line: string) => `Zephyr ${line}`,
      patches: (names: string[]) =>
        names.length === 0 ? 'no patches' : `patches: ${names.join(', ')}`,
      files: (count: number) => `${count} ${count === 1 ? 'file' : 'files'}`,
      /** Twelve hex digits: what a person compares two builds with. */
      contextId: (id: string) => `id ${id}`,
      image: (reference: string) => reference,
      jobs: (count: number) => `${count} parallel ${count === 1 ? 'job' : 'jobs'}`,
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
