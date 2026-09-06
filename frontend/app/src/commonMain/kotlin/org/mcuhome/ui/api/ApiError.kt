// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why a command could not be carried out.
 *
 * The fields are the ones the builder itself reports for a configuration
 * problem — message, an optional fix hint — plus a code the interface can
 * branch on without reading English. Anything that has a place in a file
 * to point at is not an error of this kind: it is a [Diagnostic] inside a
 * successful [ValidationReport].
 */
@Serializable
data class ApiError(val code: ApiErrorCode, val message: String, val hint: String? = null)

/** The kinds of refusal an area can answer with. */
@Serializable
enum class ApiErrorCode {
    /** The named device, file, secret or option does not exist. */
    @SerialName("not_found")
    NotFound,

    /** The request was malformed or the value is not one the option accepts. */
    @SerialName("invalid")
    Invalid,

    /** The operation is understood but refused in this state (a build is already running). */
    @SerialName("refused")
    Refused,

    /**
     * The capability exists in the vocabulary but nothing behind it can do
     * the work yet. Flashing, first-time setup and the device log answer
     * this until the workbench provides them; see [Availability].
     */
    @SerialName("not_available")
    NotAvailable,

    /** Something broke that the caller cannot act on. */
    @SerialName("internal")
    Internal,
}

/** The exception every suspending function of this API throws on [ApiError]. */
class ApiException(val error: ApiError) : Exception(error.message)

/**
 * The answer of a capability that may not exist yet.
 *
 * Flashing, first-time setup and the device log are part of the command
 * vocabulary from the start, but neither the workbench nor the back end can
 * perform them today. A screen must be able to render "not available, and
 * here is why" as an ordinary state rather than as a failure, so those
 * calls answer with this type instead of throwing.
 *
 * It is never serialized: on the wire the refusal is an [ApiError] with
 * code [ApiErrorCode.NotAvailable], and the client turns that back into
 * [NotAvailable] with the error's message and hint.
 */
sealed interface Availability<out T> {
    /** The capability answered. */
    data class Available<out T>(val value: T) : Availability<T>

    /** The capability is declared but cannot be performed yet. */
    data class NotAvailable(val reason: String, val hint: String? = null) : Availability<Nothing>
}
