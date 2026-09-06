// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.api.DiagnosticSeverity
import org.mcuhome.ui.api.ValidationReport

/**
 * The mock's stand-in for the builder's first three stages.
 *
 * It is not a YAML parser and does not pretend to be one. It runs six
 * checks over the text line by line — the ones whose findings the screens
 * have to draw — and reports them with a file, a line, a column, a
 * configuration key and a fix hint, in the shape the builder reports a
 * real configuration error.
 *
 * The point is that the result follows from the text: typing a `pin:` into
 * the editor makes a warning disappear, deleting a secret reference makes
 * an error appear, and the pill in the device table changes with it. A
 * table of canned diagnostics would look the same on the first screenshot
 * and be useless from the second keystroke on.
 */
internal object MockValidation {
    /** The sections a device configuration may declare at the top level. */
    val KNOWN_SECTIONS = setOf("version", "device", "packages", "network", "hardware", "endpoints", "build")

    private val KEY_LINE = Regex("""^([A-Za-z_][A-Za-z0-9_.\-]*):(.*)$""")
    private val SECRET_REFERENCE = Regex("""!secret\s+([A-Za-z0-9_.\-]+)""")
    private val INCLUDE_REFERENCE = Regex("""!include\s+(\S+)""")

    fun validate(
        file: String,
        text: String,
        knownSecrets: Set<String>,
        knownConfigFiles: Set<String>,
        checkedAtEpochMillis: Long,
    ): ValidationReport {
        val lines = text.lines().mapIndexed { index, raw -> SourceLine(index + 1, raw) }
        val found = buildList {
            addAll(tabIndentation(file, lines))
            addAll(unknownSections(file, lines))
            addAll(deviceSection(file, lines))
            addAll(unknownSecrets(file, lines, knownSecrets))
            addAll(missingIncludes(file, lines, knownConfigFiles))
            addAll(pinlessGpioPeripherals(file, lines))
        }.sortedWith(compareBy({ it.line ?: 0 }, { it.column ?: 0 }))

        return ValidationReport(
            ok = found.none { it.severity == DiagnosticSeverity.Error },
            file = file,
            diagnostics = found,
            checkedAtEpochMillis = checkedAtEpochMillis,
        )
    }

    private fun tabIndentation(file: String, lines: List<SourceLine>): List<Diagnostic> =
        lines.filter { it.raw.takeWhile { ch -> ch == ' ' || ch == '\t' }.contains('\t') }
            .map { line ->
                Diagnostic(
                    severity = DiagnosticSeverity.Error,
                    message = "YAML does not allow a tab character for indentation.",
                    file = file,
                    line = line.number,
                    column = line.raw.indexOf('\t') + 1,
                    hint = "indent with spaces — two per level in the files MCUHome writes",
                    kind = "ConfigError",
                )
            }

    private fun unknownSections(file: String, lines: List<SourceLine>): List<Diagnostic> =
        lines.filter { it.indent == 0 && !it.isBlank }
            .mapNotNull { line -> KEY_LINE.find(line.content)?.let { line to it.groupValues[1] } }
            .filter { (_, key) -> key !in KNOWN_SECTIONS }
            .map { (line, key) ->
                Diagnostic(
                    severity = DiagnosticSeverity.Error,
                    message = "\"$key\" is not a section MCUHome knows.",
                    file = file,
                    line = line.number,
                    column = 1,
                    hint = "sections a device configuration may have: ${KNOWN_SECTIONS.sorted().joinToString(", ")}",
                    key = key,
                    kind = "ConfigError",
                )
            }

    private fun deviceSection(file: String, lines: List<SourceLine>): List<Diagnostic> {
        val hasDevice = lines.any { it.indent == 0 && it.content.startsWith("device:") }
        if (!hasDevice) {
            return listOf(
                Diagnostic(
                    severity = DiagnosticSeverity.Error,
                    message = "The configuration has no `device` section.",
                    file = file,
                    line = 1,
                    column = 1,
                    hint = "every device configuration starts with a `device:` section naming its board",
                    key = "device",
                    kind = "ConfigError",
                ),
            )
        }
        return boardValue(file, lines)
    }

    private fun boardValue(file: String, lines: List<SourceLine>): List<Diagnostic> {
        val boardLine = lines.firstOrNull { it.indent == 2 && it.content.startsWith("board:") }
            ?: return listOf(
                Diagnostic(
                    severity = DiagnosticSeverity.Error,
                    message = "The `device` section names no board.",
                    file = file,
                    line = lines.first { it.content.startsWith("device:") }.number,
                    column = 1,
                    hint = "add `board:` with a target from the board list",
                    key = "device.board",
                    kind = "ConfigError",
                ),
            )
        val board = boardLine.content.removePrefix("board:").trim()
        if (board in SAMPLE_SUPPORTED_BOARDS) return emptyList()
        val plannedReason = SAMPLE_PLANNED_BOARDS[board]
        val message = if (plannedReason != null) {
            "MCUHome does not support the board \"$board\" yet: $plannedReason."
        } else {
            "\"$board\" is not a board MCUHome knows."
        }
        return listOf(
            Diagnostic(
                severity = DiagnosticSeverity.Error,
                message = message,
                file = file,
                line = boardLine.number,
                column = boardLine.indent + boardLine.content.indexOf(board) + 1,
                hint = "boards MCUHome supports today: ${SAMPLE_SUPPORTED_BOARDS.sorted().joinToString(", ")}",
                key = "device.board",
                kind = "ConfigError",
            ),
        )
    }

    private fun unknownSecrets(
        file: String,
        lines: List<SourceLine>,
        known: Set<String>,
    ): List<Diagnostic> = lines.filterNot { it.isBlank }.flatMap { line ->
        SECRET_REFERENCE.findAll(line.raw)
            .filterNot { it.groupValues[1] in known }
            .map { match ->
                val key = match.groupValues[1]
                Diagnostic(
                    severity = DiagnosticSeverity.Error,
                    message = "The secret \"$key\" is not set.",
                    file = file,
                    line = line.number,
                    column = match.range.first + 1,
                    hint = "add it on the Secrets screen, or write it into secrets/main.yaml",
                    key = key,
                    kind = "ConfigError",
                )
            }.toList()
    }

    private fun missingIncludes(
        file: String,
        lines: List<SourceLine>,
        known: Set<String>,
    ): List<Diagnostic> = lines.filterNot { it.isBlank }.flatMap { line ->
        INCLUDE_REFERENCE.findAll(line.raw)
            .filterNot { it.groupValues[1].substringAfterLast('/') in known }
            .map { match ->
                Diagnostic(
                    severity = DiagnosticSeverity.Error,
                    message = "The included file \"${match.groupValues[1]}\" does not exist.",
                    file = file,
                    line = line.number,
                    column = match.range.first + 1,
                    hint = "shared configurations live in configs/ — the Configs screen lists them",
                    kind = "ConfigError",
                )
            }.toList()
    }

    /**
     * A GPIO peripheral without a pin is the design's one sample warning:
     * the driver has a default, so the configuration builds, but almost
     * nobody means the default.
     */
    private fun pinlessGpioPeripherals(file: String, lines: List<SourceLine>): List<Diagnostic> =
        peripheralBlocks(lines).filter { block ->
            val driver = block.property("driver")
            driver != null && driver.startsWith("gpio_") && block.property("pin") == null
        }.map { block ->
            Diagnostic(
                severity = DiagnosticSeverity.Warning,
                message = "hardware.peripherals.${block.id} — no pin given; the driver default GPIO5 is used.",
                file = file,
                line = block.line.number,
                column = block.line.indent + 1,
                hint = "state the pin explicitly with `pin:`",
                key = "hardware.peripherals.${block.id}",
                kind = "ConfigWarning",
            )
        }

    private fun peripheralBlocks(lines: List<SourceLine>): List<PeripheralBlock> {
        val header = lines.firstOrNull { it.indent == 2 && it.content.startsWith("peripherals:") }
            ?: return emptyList()
        val body = lines.drop(header.number).takeWhile { it.isBlank || it.indent > header.indent }
        val blocks = mutableListOf<PeripheralBlock>()
        for (line in body.filterNot { it.isBlank }) {
            val id = if (line.indent == header.indent + 2) KEY_LINE.find(line.content)?.groupValues?.get(1) else null
            if (id != null) {
                blocks += PeripheralBlock(id, line, mutableListOf())
            } else if (line.indent > header.indent + 2) {
                blocks.lastOrNull()?.properties?.add(line)
            }
        }
        return blocks
    }

    private data class PeripheralBlock(val id: String, val line: SourceLine, val properties: MutableList<SourceLine>) {
        fun property(name: String): String? = properties
            .firstOrNull { it.content.startsWith("$name:") }
            ?.content?.removePrefix("$name:")?.trim()
    }

    /** One line of the file, with its indentation and its comment removed. */
    private data class SourceLine(val number: Int, val raw: String) {
        val indent: Int = raw.takeWhile { it == ' ' }.length
        val content: String = raw.trim().removePrefix("- ").trim()
        val isBlank: Boolean = content.isEmpty() || content.startsWith("#")
    }
}
