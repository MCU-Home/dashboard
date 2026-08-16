// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Stable links this interface shows a person.
 *
 * `t.mcuhome.org` is the project's target host: one path per page a
 * shipped tool links to, under the scheme
 * `/<source-repo>/<target-area>/<target-detail>/<version>`. The linking
 * tool is part of a link's identity — the dashboard's page about a
 * project it cannot open is not the command line's page about upgrading
 * one, even though they are about the same file — and the version is
 * this interface's major.minor.
 *
 * A path here is kept alive for the lifetime of every release that
 * links to it, so the version is pinned rather than derived from
 * anything that moves on its own.
 */

/** Major.minor of the dashboard these links belong to. */
const VERSION = '0.1';

const base = (page: string) => `https://t.mcuhome.org/dashboard/docs/${page}/${VERSION}/`;

export const links = {
  /** Why a project cannot be opened, and who repairs it. */
  projectUpgrade: base('project-upgrade'),
} as const;
