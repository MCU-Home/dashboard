// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * What the backend's command results contain.
 *
 * Hand-written from `backend/README.md` and `commands.py`, because a
 * WebSocket API produces no schema to generate from (ADR 0004). They are
 * assertions about the wire, not parsers: nothing here validates, so a
 * backend that changes shape shows up as a rendering bug rather than a
 * type error. That is the accepted cost of one first-party client; the
 * version handshake in `server/info` is what catches the real drift.
 */

import type { Open } from './protocol';

export interface TreeState {
  root: string | null;
  available: boolean;
  device_count: number;
}

/** `builder.raw_summary` — parsed YAML, nothing resolved, no secret looked up. */
export interface RawSummary {
  sections?: string[];
  name?: string | null;
  friendly_name?: string | null;
  board?: string | null;
  endpoint_count?: number;
}

/** One device folder as the tree scanner sees it, without validating it. */
export interface DeviceEntry {
  name: string;
  /** Path of `main.yaml`, relative to the configuration tree root. */
  entry: string;
  /** sha256 over every YAML file in the folder — what a save presents back. */
  content_hash: string;
  modified: number;
  size: number;
  summary: RawSummary;
}

/**
 * One builder error, positioned.
 *
 * ADR 0004 decision 5: the builder's `ConfigError` already carries all
 * of this, which is what puts a marker with a fix hint on the editor's
 * gutter instead of a line of text in a log pane.
 */
export interface Diagnostic {
  message: string;
  /** Relative to the configuration tree root, never a server path. */
  file: string | null;
  /** 1-based, as the builder counts. `null` for errors without a position. */
  line: number | null;
  column: number | null;
  /** The dotted configuration path, e.g. `device.board`. */
  key: string | null;
  hint: string | null;
  /** The builder's exception class name. */
  kind: string;
}

export interface EndpointSummary {
  id: number;
  alias: string | null;
  device_types: string[];
  cluster_count: number;
}

export interface PeripheralSummary {
  id: string;
  compatible: string;
  bus: string | null;
}

/** `builder.device_summary` — the resolved model minus its credentials. */
export interface DeviceSummary {
  model_version: number;
  name: string;
  friendly_name: string | null;
  board: string;
  power_source: string | null;
  transport: string;
  matter_enabled: boolean;
  thread_role: string | null;
  zephyr_line: string | null;
  peripherals: PeripheralSummary[];
  endpoints: EndpointSummary[];
}

/**
 * The two codes a human needs to add a device to a controller.
 *
 * `qr_payload` **contains the passcode** — that is what makes it worth
 * showing and why the backend only sends it in answer to
 * `device/commissioning`, never as part of a list.
 */
export interface CommissioningCodes {
  /** The Matter onboarding payload, `MT:`-prefixed. */
  qr_payload: string;
  manual_code: string;
  discriminator: number;
  /** True for the credentials published with the Matter SDK — bench use only. */
  test_credentials: boolean;
}

/**
 * Every state a build passes through, as `builds.py` names them.
 *
 * `queued` lasts microseconds and exists so a client never holds a build
 * it started in no state at all. The three that end it — `succeeded`,
 * `failed`, `cancelled` — are the only ones for which `finished` is set.
 */
export type BuildState = 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled';

/** One downloadable file a build left behind. */
export interface BuildArtifact {
  /** `firmware`, `firmware-signed`, `ota`, … — what the file is for. */
  role: string;
  /**
   * Relative to the build directory, and never a server path — it is the
   * tail of the artifact URL, which is the only reason a client sees it.
   */
  path: string;
  size: number;
  /** True for what *this dashboard* signed (ADR 0008), not the builder. */
  signed: boolean;
}

/**
 * What the host-side signing step did, without saying where the key is.
 *
 * `created_key` is the field that has to reach a human: ADR 0008 gives a
 * deployment exactly one signing key, and every device bootstrapped
 * against it is orphaned if it is lost. A key that was just generated is
 * a key nobody has backed up yet.
 */
export interface BuildSigning {
  signed: boolean;
  created_key: boolean;
  outputs: string[];
}

/** The Matter OTA image, for the boards and stacks that can take one. */
export interface BuildOta {
  path: string;
  version: string;
  software_version: number;
}

/**
 * One build, from the moment it was accepted to whatever ended it.
 *
 * `errors` is the *same* `Diagnostic` shape `device/validate` answers
 * with, on purpose: a build refusal renders on the same component as a
 * configuration problem, hint and all, instead of on a second renderer
 * that would drift from the first.
 *
 * The two log offsets are what makes the output stream resumable. They
 * are line numbers from the start of this build, monotonic and never
 * reused; `log_first_offset` moves forward as the backend drops old
 * lines, so a client that asks for something below it is told
 * (`truncated`) rather than silently served a log beginning in the
 * middle.
 */
export interface BuildRecord {
  id: string;
  device: string;
  /** Which build method ran — deployment configuration (ADR 0013). */
  method: string;
  state: Open<BuildState>;
  /** Epoch seconds, as Python's `time.time()` produces them. */
  started: number;
  finished: number | null;
  /** The identity the work was attributed to; empty for methods without one. */
  context_id: string;
  /** The build-container reference, where one was used. */
  image: string;
  /** The method's own word for the result — `success`/`failure`. */
  status: string;
  errors: Diagnostic[];
  artifacts: BuildArtifact[];
  signing: BuildSigning | null;
  ota: BuildOta | null;
  log_first_offset: number;
  log_next_offset: number;
}

export interface Identity {
  kind: Open<'ingress' | 'password' | 'open'>;
  user_id: string | null;
  user_name: string | null;
}

export interface ServerInfo {
  dashboard: { name: string; version: string; uptime_seconds: number };
  builder: { package: string; version: string; supported: string };
  /**
   * How this deployment builds (ADR 0013 decision 1).
   *
   * `method` is what this deployment configured and `null` when it
   * configured nothing, in which case `default_method` is what runs — so
   * a view that wants to name the method before a build exists reads
   * `method ?? default_method` rather than assuming either.
   */
  build: {
    method: string | null;
    default_method: string;
    methods: string[];
    server_configured: boolean;
    jobs: number;
  };
  model_version: { sends: number; min: number; max: number };
  deployment: { trust: Open<'ingress' | 'public'>; base_path: string };
  identity: Identity | null;
  tree: TreeState;
}

export interface DeviceListResult {
  devices: DeviceEntry[];
  tree: TreeState;
}

export interface DeviceGetResult {
  device: DeviceEntry;
  /** The file exactly as it is on disk. This is what the editor opens. */
  content: string;
  summary: RawSummary;
}

export interface DeviceSaveResult {
  name: string;
  device: DeviceEntry;
  content_hash: string;
}

export interface DeviceValidateResult {
  name: string;
  ok: boolean;
  errors: Diagnostic[];
  device: DeviceSummary | null;
}

export interface DeviceCommissioningResult {
  name: string;
  ok: boolean;
  errors: Diagnostic[];
  /** `null` when the device has no Matter pairing tuple. */
  commissioning: CommissioningCodes | null;
}

export interface SubscribeResult extends DeviceListResult {
  topic: string;
}

/**
 * The answer of every command that speaks about exactly one build —
 * `build/start`, `build/cancel`, and `build/status` with an id.
 *
 * A build that ran and *failed* answers here like any other: the refusal
 * is the record's `state` and its `errors`. An error frame from
 * `build/start` means the build could not be **started**, which is a
 * different thing and needs a different message.
 */
export interface BuildStartResult {
  build: BuildRecord;
}

/** Every build this backend process knows, oldest first. */
export interface BuildStatusResult {
  builds: BuildRecord[];
  /** The build holding the one slot — one build at a time (ADR 0013). */
  running: string | null;
}

export interface BuildSubscribeResult extends BuildStatusResult {
  topic: string;
}

/**
 * A window onto one build's output, and the offsets around it.
 *
 * `offset` is where the answer actually starts, which is not always what
 * was asked for: the retained log is bounded, and a request for
 * something already discarded comes back from the oldest line still held
 * with `truncated: true`.
 */
export interface BuildLogResult {
  build_id: string;
  offset: number;
  lines: string[];
  next_offset: number;
  first_offset: number;
  truncated: boolean;
  state: Open<BuildState>;
}
