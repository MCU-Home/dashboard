// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals

class ByteSizeTest {
    @Test
    fun anything_below_a_kilobyte_is_counted_in_bytes() {
        assertEquals("0 B", formatByteSize(0))
        assertEquals("1023 B", formatByteSize(1023))
    }

    @Test
    fun the_image_of_the_design_reads_as_the_design_writes_it() {
        assertEquals("412 KB", formatByteSize(421_888))
    }

    @Test
    fun small_amounts_of_a_unit_keep_one_decimal() {
        assertEquals("1.0 KB", formatByteSize(1024))
        assertEquals("4.0 KB", formatByteSize(4096))
        assertEquals("1.5 MB", formatByteSize(1_572_864))
    }

    @Test
    fun the_unit_grows_with_the_number() {
        assertEquals("1.0 GB", formatByteSize(1024L * 1024 * 1024))
        assertEquals("2.0 GB", formatByteSize(2L * 1024 * 1024 * 1024))
    }
}
