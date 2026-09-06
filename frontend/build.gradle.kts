// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0
//
// The root project builds nothing itself; it only carries the
// coordinates every subproject shares and the two linters. No version is
// set: nothing here is published to a repository, and the repository's
// version lives in its git tags.
//
// ktlint and detekt are applied to every project, the root included, so
// that `./gradlew ktlintCheck` and `./gradlew detekt` from the root cover
// the build scripts as well as the sources.

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

allprojects {
    group = "org.mcuhome"

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<KtlintExtension> {
        // The engine version is pinned in the version catalog rather than
        // left to whatever the plugin happens to default to, so updating
        // the plugin cannot change the rules underneath us. Code style,
        // indent width and line length come from the repository's
        // .editorconfig.
        version.set(rootProject.libs.versions.ktlint)
        // Compose generates the resource accessors into build/, and
        // generated code is not ours to format.
        filter {
            exclude { it.file.path.contains("${File.separator}build${File.separator}") }
        }
    }

    configure<DetektExtension> {
        // detekt has no notion of Kotlin Multiplatform source sets — its
        // defaults look for src/main/kotlin and src/test/kotlin, neither
        // of which exists here. Pointing it at src/ covers every source
        // set of every target at once and leaves generated code out.
        source.setFrom(files("src"))
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("gradle/detekt.yml"))
        parallel = false
    }

    tasks.withType<Detekt>().configureEach {
        // The console output is what a developer and CI read; the report
        // files would only be written and never looked at.
        reports {
            html.required.set(false)
            xml.required.set(false)
            txt.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }
}
