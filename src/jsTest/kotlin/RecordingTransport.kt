package io.github.kidx

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * A transport standing in for another tab: it records what this context published, and `deliver` plays
 * the part of a signal arriving from elsewhere.
 */
internal class RecordingTransport : NotificationTransport {

    val published: MutableList<Set<String>> = mutableListOf()
    private val inbound = Channel<Set<String>>(Channel.UNLIMITED)

    override suspend fun publish(stores: Set<String>) {
        published += stores
    }

    override fun subscribe(): Flow<Set<String>> = inbound.consumeAsFlow()

    fun deliver(stores: Set<String>) {
        inbound.trySend(stores)
    }
}
