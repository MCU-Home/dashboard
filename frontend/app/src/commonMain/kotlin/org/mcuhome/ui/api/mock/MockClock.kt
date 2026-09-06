// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

/**
 * Where the mock gets "now" from.
 *
 * The mock never reads the system clock. Every timestamp it produces comes
 * from here, so the same sequence of calls always produces the same data —
 * which is what lets a test assert on a timestamp and a screenshot stay
 * comparable from one run to the next.
 */
fun interface MockClock {
    fun nowEpochMillis(): Long

    companion object {
        /** A clock that always answers the same instant. The mock's default. */
        fun fixed(epochMillis: Long): MockClock = MockClock { epochMillis }
    }
}

/** A clock a test moves by hand. */
class MutableMockClock(var nowEpochMillis: Long) : MockClock {
    override fun nowEpochMillis(): Long = nowEpochMillis

    fun advance(millis: Long) {
        nowEpochMillis += millis
    }
}
