// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scanner behind the editor's YAML highlighting. The tests state the
 * spans it produces as (role, text) pairs in document order, which is
 * what the editor draws; the offsets themselves are checked separately.
 *
 * Tests whose name ends in "IsALimitation" record where the scanner is
 * deliberately narrower than YAML — it knows the shape of the files
 * MCUHome writes, not the whole grammar. They are here so a later change
 * to that boundary is a visible change to the tests.
 */
class YamlHighlightingTest {
    private fun tokens(text: String): List<Pair<YamlToken, String>> = highlightYaml(text)
        .sortedBy { it.start }
        .map { it.token to text.substring(it.start, it.end) }

    @Test
    fun mappingLineSplitsIntoKeyAndValue() {
        assertEquals(
            listOf(YamlToken.Key to "name", YamlToken.Value to "kitchen-light"),
            tokens("name: kitchen-light"),
        )
    }

    @Test
    fun keyWithoutValueIsAKeyAlone() {
        assertEquals(listOf(YamlToken.Key to "esphome"), tokens("esphome:"))
    }

    @Test
    fun indentedKeysAreScannedLikeTopLevelOnes() {
        assertEquals(
            listOf(YamlToken.Key to "board", YamlToken.Value to "nrf52840dk"),
            tokens("    board: nrf52840dk"),
        )
    }

    @Test
    fun valueMayContainSpaces() {
        assertEquals(
            listOf(YamlToken.Key to "friendly_name", YamlToken.Value to "Kitchen Light"),
            tokens("friendly_name: Kitchen Light"),
        )
    }

    @Test
    fun sequenceDashIsStructureAndWhatFollowsIsScanned() {
        assertEquals(
            listOf(YamlToken.Key to "platform", YamlToken.Value to "dht"),
            tokens("  - platform: dht"),
        )
    }

    @Test
    fun sequenceEntryWithoutAKeyIsAValue() {
        assertEquals(listOf(YamlToken.Value to "kitchen"), tokens("  - kitchen"))
    }

    @Test
    fun secretTagIsItsOwnRoleAndTheNameFollowsAsAValue() {
        assertEquals(
            listOf(
                YamlToken.Key to "password",
                YamlToken.Tag to "!secret",
                YamlToken.Value to "wifi_password",
            ),
            tokens("password: !secret wifi_password"),
        )
    }

    @Test
    fun includeTagIsScannedLikeSecret() {
        assertEquals(
            listOf(
                YamlToken.Key to "sensors",
                YamlToken.Tag to "!include",
                YamlToken.Value to "sensors.yaml",
            ),
            tokens("sensors: !include sensors.yaml"),
        )
    }

    @Test
    fun tagWithoutAnArgumentIsStillATag() {
        assertEquals(
            listOf(YamlToken.Key to "password", YamlToken.Tag to "!secret"),
            tokens("password: !secret"),
        )
    }

    @Test
    fun wholeLineCommentIsAComment() {
        assertEquals(listOf(YamlToken.Comment to "# the kitchen light"), tokens("  # the kitchen light"))
    }

    @Test
    fun trailingCommentIsSeparatedFromTheValue() {
        assertEquals(
            listOf(
                YamlToken.Key to "board",
                YamlToken.Value to "nrf52840dk",
                YamlToken.Comment to "# the dev kit",
            ),
            tokens("board: nrf52840dk # the dev kit"),
        )
    }

    @Test
    fun hashInsideADoubleQuotedValueIsNotAComment() {
        assertEquals(
            listOf(YamlToken.Key to "note", YamlToken.Value to "\"channel # 11\""),
            tokens("note: \"channel # 11\""),
        )
    }

    @Test
    fun hashInsideASingleQuotedValueIsNotAComment() {
        assertEquals(
            listOf(YamlToken.Key to "note", YamlToken.Value to "'channel # 11'"),
            tokens("note: 'channel # 11'"),
        )
    }

    @Test
    fun commentAfterAQuotedValueIsStillFound() {
        assertEquals(
            listOf(
                YamlToken.Key to "note",
                YamlToken.Value to "\"channel 11\"",
                YamlToken.Comment to "# for now",
            ),
            tokens("note: \"channel 11\" # for now"),
        )
    }

    @Test
    fun hashWithoutALeadingSpaceBelongsToTheValue() {
        assertEquals(
            listOf(YamlToken.Key to "tag", YamlToken.Value to "v1#2"),
            tokens("tag: v1#2"),
        )
    }

    @Test
    fun colonInsideAQuotedKeyDoesNotEndTheKey() {
        assertEquals(
            listOf(YamlToken.Key to "\"time: start\"", YamlToken.Value to "07:00"),
            tokens("\"time: start\": 07:00"),
        )
    }

    @Test
    fun integersFloatsAndNegativesAreLiterals() {
        assertEquals(listOf(YamlToken.Key to "port", YamlToken.Literal to "8080"), tokens("port: 8080"))
        assertEquals(listOf(YamlToken.Key to "ratio", YamlToken.Literal to "1.5"), tokens("ratio: 1.5"))
        assertEquals(listOf(YamlToken.Key to "offset", YamlToken.Literal to "-3"), tokens("offset: -3"))
    }

    @Test
    fun hexNumbersAreLiterals() {
        assertEquals(listOf(YamlToken.Key to "mask", YamlToken.Literal to "0x1A"), tokens("mask: 0x1A"))
    }

    @Test
    fun booleansAndNullAreLiterals() {
        for (scalar in listOf("true", "false", "yes", "no", "on", "off", "null", "~", "True", "FALSE")) {
            assertEquals(
                listOf(YamlToken.Key to "enabled", YamlToken.Literal to scalar),
                tokens("enabled: $scalar"),
                "scalar $scalar",
            )
        }
    }

    @Test
    fun quotedNumberIsAValueNotALiteral() {
        assertEquals(
            listOf(YamlToken.Key to "version", YamlToken.Value to "\"2\""),
            tokens("version: \"2\""),
        )
    }

    @Test
    fun documentMarkersAndBlankLinesProduceNothing() {
        assertEquals(emptyList(), tokens("---"))
        assertEquals(emptyList(), tokens("..."))
        assertEquals(emptyList(), tokens(""))
        assertEquals(emptyList(), tokens("   "))
        assertEquals(emptyList(), tokens("\n\n"))
    }

    @Test
    fun offsetsAreAbsoluteAcrossLines() {
        val text = "esphome:\n  name: kitchen\n"
        val spans = highlightYaml(text).sortedBy { it.start }
        assertEquals(3, spans.size)
        assertEquals(YamlSpan(0, 7, YamlToken.Key), spans[0])
        assertEquals(YamlSpan(11, 15, YamlToken.Key), spans[1])
        assertEquals(YamlSpan(17, 24, YamlToken.Value), spans[2])
    }

    @Test
    fun spansStayInsideTheDocumentAndDoNotOverlap() {
        val text = """
            # a kitchen light
            esphome:
              name: kitchen-light
              board: nrf52840dk   # the dev kit
            wifi:
              password: !secret wifi_password
              channel: 11
              fast: true
            sensors: !include sensors.yaml
        """.trimIndent()
        val spans = highlightYaml(text).sortedBy { it.start }
        assertTrue(spans.isNotEmpty())
        var previousEnd = 0
        for (span in spans) {
            assertTrue(span.start >= previousEnd, "span $span starts before the previous one ends")
            assertTrue(span.start < span.end, "span $span is empty or inverted")
            assertTrue(span.end <= text.length, "span $span reaches past the document")
            previousEnd = span.end
        }
    }

    @Test
    fun flowCollectionIsOneValueWhichIsALimitation() {
        assertEquals(
            listOf(YamlToken.Key to "boards", YamlToken.Value to "[nrf52840dk, esp32c6]"),
            tokens("boards: [nrf52840dk, esp32c6]"),
        )
    }

    @Test
    fun blockScalarContentIsScannedAsYamlWhichIsALimitation() {
        // Inside a `|` block every line is text, but the scanner has no
        // block state and reads the second line as a key with a trailing
        // comment.
        assertEquals(
            listOf(
                YamlToken.Key to "script",
                YamlToken.Value to "|",
                YamlToken.Key to "echo",
                YamlToken.Value to "hello",
                YamlToken.Comment to "# not a comment",
            ),
            tokens("script: |\n  echo: hello # not a comment"),
        )
    }
}
