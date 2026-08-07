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

export interface Identity {
  kind: Open<'ingress' | 'password' | 'open'>;
  user_id: string | null;
  user_name: string | null;
}

export interface ServerInfo {
  dashboard: { name: string; version: string; uptime_seconds: number };
  builder: { package: string; version: string; supported: string };
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
