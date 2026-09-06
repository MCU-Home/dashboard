// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0
//
// The browser entry point. It holds the `main` function, the page shell
// (`index.html`) and nothing else — everything the user sees lives in
// `:app`.
//
// `output.publicPath = "./"` is what makes the produced bundle
// relocatable: webpack then emits relative URLs for the JavaScript
// chunks, the `.wasm` module and the Skia runtime, so the same
// distribution works when it is served from the site root and when it is
// served under a base path (a Home Assistant ingress URL, for example).
// The default is `/`, which only ever works at the root.

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "mcuhome-ui"
        browser {
            commonWebpackConfig {
                outputFileName = "mcuhome-ui.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":app"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.navigation.compose)
        }
    }
}
