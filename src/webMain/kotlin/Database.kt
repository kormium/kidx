package io.github.kidx

import com.juul.indexeddb.AutoIncrement
import com.juul.indexeddb.KeyPath
import com.juul.indexeddb.EventException
import com.juul.indexeddb.OpenBlockedException
import com.juul.indexeddb.external.IDBDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.js.JsAny
import com.juul.indexeddb.Database as Driver
import com.juul.indexeddb.deleteDatabase as driverDeleteDatabase
import com.juul.indexeddb.openDatabase as driverOpenDatabase

/**
 * Told that another context wants to upgrade the schema. Our connection is closing either way — the
 * engine's `versionchange` leaves no choice if the other context is not to hang — so this is a
 * notification, not a veto: show a banner, drop caches, reload.
 */
public class VersionChangeSignal internal constructor(public val databaseName: String)

/** IndexedDB is unavailable — Firefox private browsing being the usual reason. */
public class IndexedDbUnavailableException internal constructor(message: String) : KidxException(message)

/** Another context holds the database open at an older version, so an upgrade cannot proceed. */
public class DatabaseBlockedException internal constructor(
    public val databaseName: String,
    cause: Throwable?,
) : KidxException(
    "Database '$databaseName' is open in another tab, worker or window at an older version, so the " +
        "schema cannot be upgraded. Ask the user to close the others and try again — how long to wait, " +
        "and whether to give up, is the application's policy.",
    cause,
)

/** The connection is closed: explicitly, or because another context upgraded the schema. */
public class DatabaseClosedException internal constructor(databaseName: String) : KidxException(
    "Database '$databaseName' is closed. Either close() was called, or another context upgraded the " +
        "schema and this connection was closed so as not to block it — reopen, or reload the page.",
)

/**
 * Opens the database [schema] describes: replays every migration above the stored version, then
 * **verifies** that the database's actual shape matches what the schema declares (decision 13).
 *
 * Fails rather than waits when another context holds an older version open, which is the vendored
 * engine's behaviour and the honest one: waiting is unbounded and nothing here can influence it.
 */
public suspend fun openDatabase(
    schema: Schema,
    onVersionChange: (VersionChangeSignal) -> Unit = {},
): Database {
    val driver = try {
        driverOpenDatabase(schema.databaseName, schema.version) { database, oldVersion, _ ->
            for (migration in schema.migrations) {
                if (migration.version <= oldVersion) continue
                for (step in migration.steps) apply(step, database)
            }
        }
    } catch (e: OpenBlockedException) {
        throw DatabaseBlockedException(schema.databaseName, e)
    } catch (e: EventException) {
        // The one open failure with its own remedy; everything else the engine says stays generic.
        if (jsErrorName(e.event) == "VersionError") {
            throw DatabaseTooNewException(schema.databaseName, schema.version, e)
        }
        throw EngineException(
            jsErrorName(e.event).ifEmpty { "OpenError" },
            "IndexedDB refused to open database '${schema.databaseName}' at version ${schema.version}: " +
                "${e.message}",
            e,
        )
    } catch (e: IllegalStateException) {
        // The driver's own check for a missing global; worth a typed failure of our own, since an
        // application that must work in private browsing has to branch on it.
        throw IndexedDbUnavailableException(
            "IndexedDB is not available in this context (${e.message}). Private browsing and some " +
                "embedded webviews have none; an application that must work there needs a fallback.",
        )
    }
    val database = Database(schema, driver, onVersionChange)
    database.verifyShape()
    return database
}

/** Deletes a database outright. Open connections block a delete exactly as they block an upgrade. */
public suspend fun deleteDatabase(name: String) {
    try {
        driverDeleteDatabase(name)
    } catch (e: IllegalStateException) {
        throw IndexedDbUnavailableException("IndexedDB is not available in this context (${e.message})")
    }
}

private fun com.juul.indexeddb.VersionChangeTransaction.apply(step: SchemaStep, database: Driver) {
    when (step) {
        is SchemaStep.CreateStore<*> -> {
            val store = step.store
            val keyPath = KeyPath(store.keyPath().first(), *store.keyPath().drop(1).toTypedArray())
            with(database) {
                if (store.autoIncrement) {
                    createObjectStore(store.storeName, keyPath, AutoIncrement)
                } else {
                    createObjectStore(store.storeName, keyPath)
                }
            }
        }
        is SchemaStep.AddIndex<*> -> {
            val fields = step.index.fields.map { it.name }
            with(objectStore(step.store.storeName)) {
                createIndex(
                    step.index.indexName,
                    KeyPath(fields.first(), *fields.drop(1).toTypedArray()),
                    unique = step.index.unique,
                )
            }
        }
        is SchemaStep.DropIndex -> with(objectStore(step.storeName)) { deleteIndex(step.indexName) }
        is SchemaStep.DropStore -> with(database) { deleteObjectStore(step.storeName) }
    }
}

/**
 * An open connection to one IndexedDB database.
 *
 * Operations do not live here: they live on [ReadScope] and [WriteScope], so that every one of them is
 * inside a transaction whose lifetime is visible at the call site (decision 5).
 */
public class Database internal constructor(
    internal val schema: Schema,
    private val driver: Driver,
    onVersionChange: (VersionChangeSignal) -> Unit,
) {
    /** The registry every write reports to, and the seam [observe] is built on (decision 15). */
    public val writeListeners: WriteListeners = WriteListeners()

    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** The schema version this connection is open at. */
    public val version: Int get() = schema.version

    public val isOpen: Boolean get() = driver.isOpen

    init {
        raw().addEventListener("versionchange") { _ ->
            onVersionChange(VersionChangeSignal(schema.databaseName))
        }
    }

    private fun raw(): IDBDatabase = driver.ensureDatabase()

    /** A read-only transaction over [stores]. Several reads inside it see one consistent snapshot. */
    public suspend fun <T> read(vararg stores: Store<*>, block: suspend ReadScope.() -> T): T =
        read(stores.map { it.storeName }.toSet(), block)

    internal suspend fun <T> read(stores: Set<String>, block: suspend ReadScope.() -> T): T {
        checkOpen()
        require(stores)
        refuseNesting(stores)
        return withContext(InTransaction(stores)) {
            translatingEngineFailures {
                driver.transaction(*stores.toTypedArray()) { ReadScope(this, stores).block() }
            }
        }
    }

    /**
     * A read-write transaction over [stores]. If [block] throws, the transaction is aborted before the
     * exception propagates and nothing it wrote survives; on success the stores it named are reported
     * to [writeListeners] after the commit (decisions 6 and 15).
     */
    public suspend fun <T> write(vararg stores: Store<*>, block: suspend WriteScope.() -> T): T {
        checkOpen()
        val names = stores.map { it.storeName }.toSet()
        require(names)
        refuseNesting(names)
        val result = withContext(InTransaction(names)) {
            translatingEngineFailures {
                driver.writeTransaction(*names.toTypedArray()) { WriteScope(this, names).block() }
            }
        }
        // After the commit, never before: the vendored writeTransaction awaits completion before it
        // returns, so reaching this line means the data is durable.
        writeListeners.fire(names)
        writeListeners.publishCommit(names)
        return result
    }

    public fun close() {
        scope.cancel()
        driver.close()
    }

    private fun checkOpen() {
        if (!driver.isOpen) throw DatabaseClosedException(schema.databaseName)
    }

    private fun require(stores: Set<String>) {
        if (stores.isEmpty()) throw KidxException("A transaction must name at least one store")
        for (name in stores) {
            if (name !in schema.shape) {
                throw SchemaException(
                    "Store '$name' is not part of schema '${schema.databaseName}' " +
                        "(${schema.storeNames.joinToString()})",
                )
            }
        }
    }

    /**
     * Compares the declared schema with what the engine reports it actually has — store names, key
     * paths, generated keys, index names, index key paths, uniqueness.
     *
     * One pass over metadata, no data read, no bookkeeping of kidx's own. It catches the most common
     * real divergence — a migration that forgot an `AddIndex`, a store created with a different key
     * path — on any existing database, because it remembers nothing and only compares (decision 13).
     */
    internal fun verifyShape() {
        val actual = raw()
        val actualStores = actual.objectStoreNames.toList()
        for (name in schema.shape.keys) {
            if (name !in actualStores) {
                throw mismatch("store '$name' is declared but the database does not have it")
            }
        }
        // Extra objects are drift too, and in the same direction: the version number already stops older
        // code from opening a newer database, so anything the schema does not declare means the
        // migration list and the disk have diverged.
        for (name in actualStores) {
            if (name !in schema.shape) {
                throw mismatch(
                    "the database has store '$name', which the schema does not declare — a migration " +
                        "was removed from the list, or this database belongs to another schema"
                )
            }
        }
        // Reading store metadata needs a transaction, and a read-only one is enough.
        val transaction = actual.transaction(
            schema.storeNames.toJsStringArray(),
            "readonly",
            emptyTransactionOptions(),
        )
        for ((name, expected) in schema.shape) {
            val store = transaction.objectStore(name)
            val keyPath = keyPathToList(store.keyPath)
            if (keyPath != expected.keyPath) {
                throw mismatch(
                    "store '$name' declares key path ${expected.keyPath} but the database has $keyPath" +
                        " — a key path is fixed when the store is created, so this needs a migration " +
                        "that recreates it",
                )
            }
            if (store.autoIncrement != expected.autoIncrement) {
                throw mismatch(
                    "store '$name' declares autoIncrement=${expected.autoIncrement} but the database " +
                        "has ${store.autoIncrement}",
                )
            }
            val actualIndexes = store.indexNames.toList()
            for (indexName in actualIndexes) {
                if (indexName !in expected.indexes) {
                    throw mismatch(
                        "the database has index '$indexName' on store '$name', which the schema does " +
                            "not declare"
                    )
                }
            }
            for ((indexName, indexShape) in expected.indexes) {
                if (indexName !in actualIndexes) {
                    throw mismatch(
                        "index '$indexName' of store '$name' is declared but the database does not " +
                            "have it — the migration that should have added it is missing",
                    )
                }
                val index = store.index(indexName)
                val indexKeyPath = keyPathToList(index.keyPath)
                if (indexKeyPath != indexShape.keyPath) {
                    throw mismatch(
                        "index '$indexName' of store '$name' declares ${indexShape.keyPath} but the " +
                            "database has $indexKeyPath",
                    )
                }
                if (index.unique != indexShape.unique) {
                    throw mismatch(
                        "index '$indexName' of store '$name' declares unique=${indexShape.unique} but " +
                            "the database has ${index.unique}",
                    )
                }
            }
        }
    }

    /**
     * Opening a transaction inside another one **deadlocks** as soon as the store sets overlap: the inner
     * one waits for a store the outer one holds, and the outer cannot finish while suspended inside the
     * inner. Never hang — say what happened and what to do (SPEC.md decision 6).
     *
     * This is the runtime half of the suspend discipline. It cannot catch a stray `delay` or a network
     * call inside a scope, which would let the event loop drain and the transaction auto-commit; only
     * `@RestrictsSuspension` could, and it does not fit here (decision 5). It does catch the failure mode
     * that is otherwise indistinguishable from a hang.
     */
    private suspend fun refuseNesting(wanted: Set<String>) {
        val outer = currentCoroutineContext()[InTransaction] ?: return
        throw KidxException(
            "A transaction is already open on ${outer.stores.joinToString()} in this coroutine, and " +
                "opening another one over ${wanted.joinToString()} would deadlock: the inner waits for a " +
                "store the outer holds. Name every store the outer read()/write() needs instead — one " +
                "scope can span as many as you like.",
        )
    }

    /**
     * Turns the driver's event-carrying exceptions into kidx's own, so a consumer can catch a duplicate
     * key by type without naming a vendored class. An exception thrown by the caller's own block passes
     * through untouched — it is not an engine failure, and the driver has already aborted for it.
     */
    private suspend fun <T> translatingEngineFailures(block: suspend () -> T): T = try {
        block()
    } catch (e: EventException) {
        val name = jsErrorName(e.event)
        val where = "database '${schema.databaseName}'"
        throw when (name) {
            "ConstraintError" -> ConstraintViolationException(
                "A key or unique index was violated in $where. The whole transaction was discarded — " +
                    "IndexedDB cannot ignore a single failed write.",
                e,
            )
            "QuotaExceededError" -> QuotaExceededException(
                "Storage quota exceeded writing to $where. The browser sets the budget and may evict " +
                    "data on its own; see navigator.storage.estimate().",
                e,
            )
            else -> EngineException(
                name.ifEmpty { "TransactionError" },
                "IndexedDB reported ${name.ifEmpty { "a transaction failure" }} on $where: ${e.message}",
                e,
            )
        }
    }

    private fun mismatch(detail: String): SchemaMismatchException = SchemaMismatchException(
        "Schema '${schema.databaseName}' does not match the database at version ${schema.version}: " +
            "$detail.",
    )
}

/** Marks a coroutine as being inside a kidx transaction. See `Database.refuseNesting`. */
private class InTransaction(val stores: Set<String>) : AbstractCoroutineContextElement(InTransaction) {
    companion object Key : CoroutineContext.Key<InTransaction>
}

/** Turns a set of store names into the stores themselves, for [observe]'s generic form. */
internal fun Database.storesOf(names: Set<String>): Array<Store<*>> =
    names.map { schema.storesByName.getValue(it) }.toTypedArray()

private fun emptyTransactionOptions(): com.juul.indexeddb.external.IDBTransactionOptions = jsEmptyObject()

private fun jsEmptyObject(): com.juul.indexeddb.external.IDBTransactionOptions =
    newRecord().unsafeCastToTransactionOptions()

@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
private fun JsAny.unsafeCastToTransactionOptions(): com.juul.indexeddb.external.IDBTransactionOptions =
    this as com.juul.indexeddb.external.IDBTransactionOptions
