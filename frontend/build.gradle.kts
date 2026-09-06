// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0
//
// The root project builds nothing itself; it only carries the
// coordinates every subproject shares. No version is set: nothing here
// is published to a repository, and the repository's version lives in
// its git tags.

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

allprojects {
    group = "org.mcuhome"
}
