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
  DeviceCommissioningResult,
  DeviceGetResult,
  DeviceListResult,
  DeviceSaveResult,
  DeviceValidateResult,
  ServerInfo,
  SubscribeResult,
} from './types';

/** The topic the device list lives on. */
export const TOPIC_DEVICES = 'devices';

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
