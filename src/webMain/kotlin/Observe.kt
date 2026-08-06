package io.github.kidx

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Notified after a write transaction commits, with the names of the stores it wrote. Never called for
 * a transaction that aborted, and never before the commit — a listener that ran earlier could read
 * state that is about to vanish (decision 15).
 *
 * This is the non-reactive seam the rest is built on: [observe] turns it into a `Flow`, but it is
 * equally useful for invalidating a cache or counting writes. Keep the callback cheap; it runs on the
 * coroutine that committed.
 */
public fun interface WriteListener {
    public fun onCommit(stores: Set<String>)
}

/** Handle returned by [WriteListeners.add]. */
public fun interface Registration {
    public fun remove()
}

/**
 * Per-database registry of [WriteListener]s.
 *
 * IndexedDB has no change events at all, so this registry is the *only* source of them — nothing here
 * mirrors an engine feature (decision 15).
 */
public class WriteListeners internal constructor() {
    private var listeners: List<WriteListener> = emptyList()
    private var publish: ((Set<String>) -> Unit)? = null

    public fun add(listener: WriteListener): Registration {
        listeners = listeners + listener
        return Registration { listeners = listeners.filterNot { it === listener } }
    }

    /** True when at least one listener is registered, so a write scope can skip its bookkeeping. */
    public val isActive: Boolean get() = listeners.isNotEmpty()

    /**
     * Delivers [stores] locally. Used both by the commit path and by an inbound remote signal — which
     * is exactly why publishing is a separate method: a signal delivered from elsewhere must not be
     * sent back out, or two contexts echo forever.
     */
    internal fun fire(stores: Set<String>) {
        if (stores.isEmpty()) return
        for (listener in listeners) listener.onCommit(stores)
    }

    /** Publishes a **local** commit outward, if a transport is connected. Called only after [fire]. */
    internal fun publishCommit(stores: Set<String>) {
        if (stores.isEmpty()) return
        publish?.invoke(stores)
    }

    internal fun setPublish(hook: ((Set<String>) -> Unit)?) {
        publish = hook
    }
}

/**
 * Carries "these stores were written" between execution contexts — tabs, workers, an extension's
 * service worker — so that a query observing a store re-runs when *another* context commits.
 *
 * The signal carries names, never data: each context re-runs its own queries against its own
 * connection.
 */
public interface NotificationTransport {
    /** Publishes the stores a local commit wrote. Best-effort; failures must not break the commit. */
    public suspend fun publish(stores: Set<String>)

    /** A cold flow of signals from *other* contexts. */
    public fun subscribe(): Flow<Set<String>>
}

/**
 * Connects [transport] so notifications cross context boundaries. Inbound signals are delivered into
 * this database's listeners exactly as a local commit would be, and are deliberately not re-published.
 *
 * Names this database's schema does not know are ignored rather than raising: the other context may be
 * running a different version of the code, and a newer tab must not break an older one.
 */
public fun Database.connectNotifications(transport: NotificationTransport): Registration {
    val known = schema.storeNames.toSet()
    val job = scope.launch {
        transport.subscribe().collect { stores ->
            writeListeners.fire(stores.filterTo(mutableSetOf()) { it in known })
        }
    }
    writeListeners.setPublish { stores -> scope.launch { transport.publish(stores) } }
    return Registration {
        writeListeners.setPublish(null)
        job.cancel()
    }
}

/**
 * Re-runs [fetch] after every commit touching one of [stores], emitting its result — once immediately,
 * then again per change.
 *
 * Emissions are **conflated**: while a re-fetch is in flight, a burst of commits collapses into one
 * pending refresh rather than one per commit. Invalidation is per store, not per key, so writing one
 * row re-runs every query watching that store — the cost decision 15 documents rather than hides.
 */
public fun <T> Database.observe(
    stores: Set<String>,
    fetch: suspend ReadScope.() -> T,
): Flow<T> = channelFlow {
    val signals = Channel<Unit>(Channel.CONFLATED)
    val registration = writeListeners.add { changed ->
        if (changed.any { it in stores }) signals.trySend(Unit)
    }
    try {
        send(read(stores = storesOf(stores), block = fetch))
        for (signal in signals) {
            send(read(stores = storesOf(stores), block = fetch))
        }
    } finally {
        registration.remove()
        signals.close()
    }
}

/** Observes every row of this store. */
public fun <R : Row> Store<R>.observe(db: Database): Flow<List<R>> =
    db.observe(setOf(storeName)) { all() }

/** Observes the rows a query over [index] returns. */
public fun <R : Row> Store<R>.observe(
    db: Database,
    index: Index<R>,
    block: QueryBuilder<R>.() -> Unit,
): Flow<List<R>> = db.observe(setOf(storeName)) { find(index, block) }

/**
 * The shipped transport: `BroadcastChannel` reaches windows, workers and extension service workers,
 * which is exactly the set of contexts that can share an IndexedDB database. The wire format is
 * kormium's — the store names, comma-joined.
 */
public class BroadcastChannelTransport(private val channelName: String) : NotificationTransport {

    override suspend fun publish(stores: Set<String>) {
        broadcast(channelName, stores.joinToString(","))
    }

    override fun subscribe(): Flow<Set<String>> = channelFlow {
        val channel = openBroadcast(channelName)
        onBroadcast(channel) { message ->
            trySend(message.split(",").filterTo(mutableSetOf()) { it.isNotEmpty() })
        }
        try {
            awaitCancellation()
        } finally {
            closeBroadcast(channel)
        }
    }
}
