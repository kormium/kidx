package com.juul.indexeddb

import com.juul.indexeddb.external.Event

internal abstract class EventException(
    message: String?,
    cause: Throwable?,
    public val event: Event,
) : Exception(message, cause)

internal class EventHandlerException(
    cause: Throwable?,
    event: Event,
) : EventException("An inner exception was thrown: $cause", cause, event)

internal class ErrorEventException(
    event: Event,
) : EventException("An error event was received.", cause = null, event)
internal class OpenBlockedException(
    public val name: String,
    event: Event,
) : EventException("Resource in use: $name.", cause = null, event)
internal class AbortTransactionException(
    event: Event,
) : EventException("Transaction aborted while waiting for completion.", cause = null, event)
