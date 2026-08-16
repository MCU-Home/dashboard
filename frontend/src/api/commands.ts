// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * One function per command, so a command name is spelled once.
 *
 * The thin half of the API surface: {@link WsClient} owns the
 * connection, this owns the vocabulary. Keeping them apart is what lets
 * the client's tests be about timing and these be about names.
 */

import type { WsClient } from './client';
import type {
  BoardsResult,
  BuildLogResult,
  BuildStartResult,
  BuildStatusResult,
  BuildSubscribeResult,
  DeviceCommissioningResult,
  DeviceGetResult,
  DeviceListResult,
  DeviceNewResult,
  DeviceOutline,
  DeviceSaveResult,
  DeviceValidateResult,
  MatterPairingResult,
  ServerInfo,
  SubscribeResult,
} from './types';

/** The topic the device list lives on. */
export const TOPIC_DEVICES = 'devices';

/** The topic builds live on. */
export const TOPIC_BUILDS = 'builds';

export function serverInfo(client: WsClient): Promise<ServerInfo> {
  return client.send<ServerInfo>('server/info');
}

export function ping(client: WsClient): Promise<{ pong: boolean; time: number }> {
  return client.send('ping');
}

export function deviceList(client: WsClient): Promise<DeviceListResult> {
  return client.send<DeviceListResult>('device/list');
}

export function deviceGet(client: WsClient, name: string): Promise<DeviceGetResult> {
  return client.send<DeviceGetResult>('device/get', { name });
}

/**
 * Write a device's configuration file.
 *
 * `expectedHash` is the `content_hash` the editor was opened with. It
 * says "I edited *that* version": if the file changed since — Studio
 * Code Server, a git checkout, another tab — the save is refused with a
 * `conflict` and nobody's work is lost. Passing `null` is a deliberate
 * overwrite, which is what a client does *after* the user chose to
 * resolve the conflict that way.
 */
export function deviceSave(
  client: WsClient,
  name: string,
  content: string,
  expectedHash: string | null,
): Promise<DeviceSaveResult> {
  const payload: Record<string, unknown> = { name, content };
  if (expectedHash !== null) {
    payload.expected_hash = expectedHash;
  }
  return client.send<DeviceSaveResult>('device/save', payload);
}

/**
 * The hardware a device can be described with.
 *
 * The builder's registry, verbatim: boards, drivers, clusters and device
 * types, each beside the ones that are planned rather than supported.
 * Nothing here is cached, because it changes only when the installed
 * builder does and a form asks for it once.
 */
export function deviceBoards(client: WsClient): Promise<BoardsResult> {
  return client.send<BoardsResult>('device/boards');
}

/**
 * Create a device's first configuration file.
 *
 * `outline` is what a form collected — buses, peripherals and endpoints
 * over the names `deviceBoards` offered. Left out, the file carries the
 * commented example the command line writes instead.
 *
 * Every refusal comes back as an error frame carrying the builder's own
 * diagnostics, including the ones a form should have prevented: nothing
 * is checked twice on this side, and a device that already exists is a
 * `conflict` rather than an overwrite.
 */
export function deviceNew(
  client: WsClient,
  name: string,
  board: string,
  options: { friendlyName?: string; outline?: DeviceOutline } = {},
): Promise<DeviceNewResult> {
  const payload: Record<string, unknown> = { name, board };
  if (options.friendlyName !== undefined && options.friendlyName !== '') {
    payload.friendly_name = options.friendlyName;
  }
  if (options.outline !== undefined) {
    payload.outline = options.outline;
  }
  return client.send<DeviceNewResult>('device/new', payload);
}

/**
 * Draw this device's commissioning identity, once.
 *
 * Writes `!secret` references into `main.yaml` and the values into the
 * device's own secrets file. It answers with **none of the codes** —
 * those come from `deviceCommissioning` and from nothing else, so that
 * "one command carries passcodes" stays a rule rather than a habit.
 *
 * `force` replaces credentials that are already there. Every controller
 * that knows the device would have to commission it again, which is why
 * the backend refuses without it.
 */
export function deviceMatterPairing(
  client: WsClient,
  name: string,
  force = false,
): Promise<MatterPairingResult> {
  return client.send<MatterPairingResult>('device/matter-pairing', { name, force });
}

export function deviceValidate(client: WsClient, name: string): Promise<DeviceValidateResult> {
  return client.send<DeviceValidateResult>('device/validate', { name });
}

/**
 * The codes that add this device to a controller.
 *
 * Sent only in answer to an explicit user action: the QR payload
 * contains the device's passcode, and a UI that fetched it on page load
 * would be putting it in front of anyone who walks past the screen.
 */
export function deviceCommissioning(
  client: WsClient,
  name: string,
): Promise<DeviceCommissioningResult> {
  return client.send<DeviceCommissioningResult>('device/commissioning', { name });
}

/**
 * The device list, and every later change to it.
 *
 * Snapshot-then-events (ADR 0004 decision 3): the result *is* the
 * current state, and the client never re-fetches the list afterwards.
 */
export function configSubscribe(client: WsClient): Promise<SubscribeResult> {
  return client.send<SubscribeResult>('config/subscribe');
}

/**
 * Compile one device's firmware.
 *
 * The result is the record as it was *accepted* — `queued`, with no
 * output and no artifacts. Everything after that arrives on the `builds`
 * topic, so a caller subscribes before it starts one rather than polling
 * for what it just asked for.
 *
 * `method` overrides this deployment's configured build method for one
 * build (ADR 0013 decision 1) and is deliberately not validated here:
 * the builder owns the list of real names and its refusal carries all of
 * them, which a copy of the list in this file could only go stale
 * against.
 */
export function buildStart(
  client: WsClient,
  name: string,
  method?: string,
): Promise<BuildStartResult> {
  const payload: Record<string, unknown> = { name };
  if (method !== undefined) {
    payload.method = method;
  }
  return client.send<BuildStartResult>('build/start', payload);
}

/** Every build this backend process knows, and which one holds the slot. */
export function buildStatus(client: WsClient): Promise<BuildStatusResult> {
  return client.send<BuildStatusResult>('build/status');
}

/** One build by id — for a caller that holds an id and no subscription. */
export function buildStatusOf(client: WsClient, buildId: string): Promise<BuildStartResult> {
  return client.send<BuildStartResult>('build/status', { build_id: buildId });
}

/**
 * Build output from an offset — the resumable half of the stream.
 *
 * Output arrives as `build_output` events carrying the line offset each
 * batch starts at, and the server's event bus drops the oldest events
 * for a connection that fell behind. A client that saw `events_dropped`,
 * or a batch that did not start where the last one ended, asks here for
 * everything from the last offset it holds and is whole again.
 */
export function buildLog(
  client: WsClient,
  buildId: string,
  fromOffset?: number,
): Promise<BuildLogResult> {
  const payload: Record<string, unknown> = { build_id: buildId };
  if (fromOffset !== undefined) {
    payload.from_offset = fromOffset;
  }
  return client.send<BuildLogResult>('build/log', payload);
}

/**
 * Stop the dashboard's part of a running build.
 *
 * Cancelling a build that already finished is not an error: it answers
 * with the record unchanged, because the caller's intent — "this build
 * should not be running" — is already true.
 */
export function buildCancel(client: WsClient, buildId: string): Promise<BuildStartResult> {
  return client.send<BuildStartResult>('build/cancel', { build_id: buildId });
}

/**
 * Every build, and every change to them.
 *
 * The build counterpart of {@link configSubscribe}, and for the same
 * reason: a client that subscribed without a snapshot would be holding
 * deltas against a state it never received.
 */
export function buildSubscribe(client: WsClient): Promise<BuildSubscribeResult> {
  return client.send<BuildSubscribeResult>('build/subscribe');
}
