// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0
//
// The application itself: user interface, state and the API interface.
// It is a multiplatform library so that the entry point of every target
// stays a thin module of its own — `:web` today, a desktop or mobile
// entry point later. Only `wasmJs` is declared for now; adding a target
// means adding it here and adding an entry-point module, not moving code.

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // Navigation appears in the signature of `App`, so the entry
            // point of every platform needs it on its own classpath.
            api(libs.navigation.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    // The generated accessor class is used from `:app` only; `:web`
    // starts the application and touches no resource directly.
    publicResClass = false
    packageOfResClass = "org.mcuhome.ui.resource"
    generateResClass = always
}
