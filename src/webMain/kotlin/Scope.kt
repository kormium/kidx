package io.github.kidx

import com.juul.indexeddb.Cursor
import com.juul.indexeddb.Key
import com.juul.indexeddb.Queryable
import com.juul.indexeddb.Transaction
import com.juul.indexeddb.WriteTransaction
import com.juul.indexeddb.bound
import com.juul.indexeddb.lowerBound
import com.juul.indexeddb.only
import com.juul.indexeddb.upperBound
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.get
import kotlin.js.toJsArray

/**
 * The receiver inside [Database.read]. Every read operation lives here rather than on [Store], so that
 * none of them can be called outside a transaction (decision 5) — outside a scope, `get` does not
 * resolve at all.
 *
 * `@RestrictsSuspension` is what makes the engine's transaction-lifetime rule a compile error instead
 * of a documented hope: inside one of these blocks, only suspend functions declared on this receiver
 * may be called. An `await` on anything else — a network call, a `delay` — would let the event loop
 * drain and the transaction auto-commit under the block. The vendored driver states that rule in a
 * comment and detects the symptom afterwards; here the compiler refuses.
 */
public open class ReadScope internal constructor(
    internal val transaction: Transaction,
    private val stores: Set<String>,
) {
    /** One row by primary key. `IDBObjectStore.get`. */
    public suspend fun <R : Row> Store<R>.get(key: Any): R? {
        val raw = with(transaction) { declaredStore(storeName).get(encodeKey(key)) }
        return raw.decodeOrNull(this)
    }

    /** The first row of a range over [index], in ascending key order. `IDBIndex.get`. */
    public suspend fun <R : Row> Store<R>.first(
        index: Index<R>,
        block: QueryBuilder<R>.() -> Unit,
    ): R? {
        val query = describe(index, block)
        if (query.limit == 0) return null
        val key = query.toKey(index) ?: return firstOfIndex(index)
        val raw = with(transaction) { queryable(index).get(key) }
        return raw.decodeOrNull(this)
    }

    /** Every row, in primary-key order. `IDBObjectStore.getAll`. */
    public suspend fun <R : Row> Store<R>.all(): List<R> {
        val raw = with(transaction) { declaredStore(storeName).getAll() }
        return raw.decodeAll(this)
    }

    /**
     * The rows of a range over [index]. One `IDBIndex.getAll(range, count)` ascending — but there is no
     * native descending `getAll`, so `direction = DESC` walks a cursor instead. Same signature,
     * materially different cost (decision 7).
     */
    public suspend fun <R : Row> Store<R>.find(
        index: Index<R>,
        block: QueryBuilder<R>.() -> Unit,
    ): List<R> {
        val query = describe(index, block)
        if (query.limit == 0) return emptyList()
        if (query.direction == Direction.DESC) {
            val rows = mutableListOf<R>()
            streamOf(index, query).collect { rows += it }
            return rows
        }
        val raw = with(transaction) {
            queryable(index).getAll(query.toKey(index), query.limit?.toUInt())
        }
        return raw.decodeAll(this)
    }

    /** How many rows. `IDBObjectStore.count`. */
    public suspend fun <R : Row> Store<R>.count(): Long =
        with(transaction) { declaredStore(storeName).count() }.toLong()

    /** How many rows a range over [index] covers. `IDBIndex.count(range)`. */
    public suspend fun <R : Row> Store<R>.count(
        index: Index<R>,
        block: QueryBuilder<R>.() -> Unit,
    ): Long {
        val query = describe(index, block)
        if (query.limit == 0) return 0
        return with(transaction) { queryable(index).count(query.toKey(index)) }.toLong()
    }

    /**
     * The rows of a range over [index], one at a time over a cursor — constant memory over a large
     * store, and the engine's actual read model (`find` is the convenience over it).
     *
     * The flow must be collected inside this scope: it holds the transaction open, and the collector
     * must not suspend on anything else, which `@RestrictsSuspension` and [collect] together enforce.
     */
    public suspend fun <R : Row> Store<R>.stream(
        index: Index<R>,
        block: QueryBuilder<R>.() -> Unit,
    ): Flow<R> = streamOf(index, describe(index, block))

    /**
     * The one suspending consumer allowed inside a scope. `@RestrictsSuspension` rules out the stdlib
     * `Flow.collect`, which is the point: this one cannot be handed a suspending action, so a cursor
     * cannot be stalled by the collector.
     */
    public suspend fun <T> Flow<T>.collect(action: (T) -> Unit) {
        collect { value -> action(value) }
    }

    private suspend fun <R : Row> Store<R>.streamOf(index: Index<R>, query: QueryDescription): Flow<R> {
        val store = this
        val direction = when (query.direction) {
            Direction.ASC -> Cursor.Direction.Next
            Direction.DESC -> Cursor.Direction.Previous
        }
        val cursors = with(transaction) {
            queryable(index).openCursor(query.toKey(index), direction, null, autoContinue = true)
        }
        val rows = cursors.map { store.decode(it.value!!) }
        return if (query.limit == null) rows else rows.take(query.limit)
    }

    private suspend fun <R : Row> Store<R>.firstOfIndex(index: Index<R>): R? {
        var found: R? = null
        streamOf(index, describe(index) { limit = 1 }).collect { found = it }
        return found
    }

    internal fun Store<*>.queryable(index: Index<*>): Queryable = with(transaction) {
        declaredStore(storeName).index(index.indexName)
    }

    /**
     * The object store, with the up-front declaration IndexedDB requires actually checked. Forgetting a
     * store is otherwise a failure from deep inside the block; this names it and says what to do.
     */
    internal fun declaredStore(storeName: String): com.juul.indexeddb.ObjectStore {
        if (storeName !in stores) {
            throw SchemaException(
                "Store '$storeName' is not part of this transaction (${stores.joinToString()}). " +
                    "IndexedDB requires a transaction to declare the stores it touches up front — add " +
                    "it to the read()/write() call.",
            )
        }
        return transaction.objectStore(storeName)
    }

    /** A `get` that found nothing answers with `undefined`, which arrives here as a null [JsAny]. */
    private fun <R : Row> JsAny?.decodeOrNull(store: Store<R>): R? =
        if (this == null || jsIsNullish(this)) null else store.decode(this)

    private fun <R : Row> JsArray<JsAny?>.decodeAll(store: Store<R>): List<R> =
        List(length) { index -> store.decode(this[index]!!) }
}

/**
 * The receiver inside [Database.write]: everything a [ReadScope] can do, plus the engine's three write
 * operations. A separate type, so that writing inside a read transaction does not compile (decision 5).
 */
public class WriteScope internal constructor(
    private val writeTransaction: WriteTransaction,
    stores: Set<String>,
) : ReadScope(writeTransaction, stores) {

    /**
     * Stores [row], failing if its key already exists. `IDBObjectStore.add`.
     *
     * Returns the stored key — which for a generated key is the one the engine assigned, and is also
     * written back onto [row] so the caller's object is usable afterwards (decision 12).
     *
     * A rejected key aborts the whole transaction; nothing else written in this scope survives
     * (decision 6).
     */
    public suspend fun <R : Row> Store<R>.add(row: R): Any {
        val record = encode(row)
        val key = with(writeTransaction) { declaredStore(storeName).add(record) }
        return adoptKey(row, key)
    }

    /** Stores [row], overwriting any row with the same key. `IDBObjectStore.put`. */
    public suspend fun <R : Row> Store<R>.put(row: R): Any {
        val record = encode(row)
        val key = with(writeTransaction) { declaredStore(storeName).put(record) }
        return adoptKey(row, key)
    }

    /** Removes the row with this primary key. `IDBObjectStore.delete`. */
    public suspend fun <R : Row> Store<R>.delete(key: Any) {
        with(writeTransaction) { declaredStore(storeName).delete(encodeKey(key)) }
    }

    private fun <R : Row> Store<R>.adoptKey(row: R, rawKey: JsAny): Any {
        val decoded = decodeKey(rawKey)
        if (autoIncrement) {
            val field = primaryKey.single()
            row.slotSet(field, decoded)
        }
        return decoded
    }
}

// ---- keys ----

/**
 * Encodes a primary key. A single-field key is the value itself; a composite one is an array, in
 * declaration order, which is exactly how IndexedDB reads an array `keyPath` (decision 12).
 */
internal fun Store<*>.encodeKey(key: Any): Key {
    val fields = primaryKey
    if (fields.size == 1) return Key(fields.single().encodeAny(key))
    val parts = key as? List<*> ?: throw KidxException(
        "Store '$storeName' has a composite primary key (${fields.joinToString { it.name }}), so a key " +
            "must be a List of ${fields.size} values, in that order — got ${key::class.simpleName}",
    )
    if (parts.size != fields.size) {
        throw KidxException(
            "Store '$storeName' has ${fields.size} primary-key fields " +
                "(${fields.joinToString { it.name }}) but ${parts.size} values were given",
        )
    }
    val encoded = fields.mapIndexed { index, field -> field.encodeAny(parts[index]!!) }
    return Key(encoded.first(), *encoded.drop(1).toTypedArray())
}

internal fun Store<*>.decodeKey(raw: JsAny): Any {
    val fields = primaryKey
    if (fields.size == 1) return fields.single().type.decodeAny(raw)
    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    val array = raw as JsArray<JsAny>
    return List(fields.size) { index -> fields[index].type.decodeAny(array[index]!!) }
}

/**
 * Compiles a description into the engine's key range. `null` means "the whole index".
 *
 * A prefix scan's upper bound gets one extra element: an empty array, which sorts above every other
 * key type in IndexedDB and so means "these leading components, then anything" (decision 11).
 */
internal fun QueryDescription.toKey(index: Index<*>): Key? {
    val lower = lower?.let { encodeTuple(index, it, tail = false) }
    val upper = upper?.let { encodeTuple(index, it, tail = upperUnboundedTail) }
    return when {
        lower == null && upper == null -> null
        lower != null && upper != null ->
            if (!lowerOpen && !upperOpen && !upperUnboundedTail && this.lower == this.upper) {
                only(lower)
            } else {
                bound(lower, upper, lowerOpen, upperOpen)
            }
        lower != null -> lowerBound(lower, lowerOpen)
        else -> upperBound(upper!!, upperOpen)
    }
}

private fun encodeTuple(index: Index<*>, values: List<Any?>, tail: Boolean): JsAny {
    val encoded = ArrayList<JsAny>(values.size + 1)
    values.forEachIndexed { position, value ->
        encoded += index.fields[position].encodeAny(value!!)
    }
    if (tail) encoded += jsEmptyArray()
    return if (encoded.size == 1) encoded.single() else encoded.toJsArray()
}
