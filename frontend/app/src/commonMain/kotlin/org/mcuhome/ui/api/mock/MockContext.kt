// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.mcuhome.ui.api.ApiError
import org.mcuhome.ui.api.ApiErrorCode
import org.mcuhome.ui.api.ApiEvent
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.DeviceChanged
import org.mcuhome.ui.api.Job
import org.mcuhome.ui.api.JobAdded
import org.mcuhome.ui.api.JobChanged

/**
 * What every area of the mock shares: the project, the clock, the event
 * sink, and the few operations all of them need.
 *
 * It exists so that each area is a small class that reads and writes one
 * state object, instead of one class that is the whole mock.
 */
internal class MockContext(
    val state: MutableStateFlow<MockState>,
    private val events: MutableSharedFlow<ApiEvent>,
    private val clock: MockClock,
    private val speed: Double,
) {
    private var counter = 0

    fun now(): Long = clock.nowEpochMillis()

    fun emit(event: ApiEvent) {
        events.tryEmit(event)
    }

    /**
     * Wait for as long as the simulation says, divided by the speed factor.
     *
     * A speed of 1.0 is the wall-clock duration a screen wants; a large
     * factor collapses a four-second build to a few milliseconds, which is
     * what a user-interface test needs. Zero or infinity means no wait at
     * all.
     */
    suspend fun pause(millis: Long) {
        if (millis <= 0 || speed <= 0.0 || speed.isInfinite()) return
        delay((millis / speed).toLong())
    }

    /** A running identifier, so two runs of the mock produce the same ids. */
    fun nextId(prefix: String): String {
        counter += 1
        return "$prefix-$counter"
    }

    fun requireDevice(name: String): MockDevice =
        state.value.device(name) ?: throw notFound("There is no device called \"$name\".")

    fun requireConfig(fileName: String): MockConfigFile = state.value.config(fileName)
        ?: throw notFound("There is no shared configuration called \"$fileName\".")

    /** Announce a device's new state to everything that is watching. */
    fun deviceChanged(device: MockDevice) {
        emit(DeviceChanged(state.value.summary(device, now())))
    }

    fun addJob(job: Job) {
        state.update { it.withJob(job) }
        emit(JobAdded(job))
    }

    fun updateJob(job: Job) {
        state.update { it.withJob(job) }
        emit(JobChanged(job))
    }
}

/** The refusals the mock uses, in the shape the back end will send them. */
internal fun notFound(message: String, hint: String? = null): ApiException =
    ApiException(ApiError(ApiErrorCode.NotFound, message, hint))

internal fun refused(message: String, hint: String? = null): ApiException =
    ApiException(ApiError(ApiErrorCode.Refused, message, hint))

internal fun invalid(message: String, hint: String? = null): ApiException =
    ApiException(ApiError(ApiErrorCode.Invalid, message, hint))
