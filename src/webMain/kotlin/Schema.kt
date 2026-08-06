package io.github.kidx

/**
 * One schema change. kidx owns DDL, unlike kormium: object stores and indexes can only be created by
 * code inside IndexedDB's version-change transaction, so the steps are typed rather than raw
 * statements (decision 13).
 */
public sealed interface SchemaStep {
    public class CreateStore<R : Row>(public val store: Store<R>) : SchemaStep
    public class AddIndex<R : Row>(public val store: Store<R>, public val index: Index<R>) : SchemaStep
    public class DropIndex(public val storeName: String, public val indexName: String) : SchemaStep
    public class DropStore(public val storeName: String) : SchemaStep
}

/** The steps that take the database to [version]. Replayed for every version above the stored one. */
public class Migration(public val version: Int, public val steps: List<SchemaStep>)

/** What a store looks like in the database. Compared against the engine's own report on open. */
internal class StoreShape(
    val keyPath: List<String>,
    val autoIncrement: Boolean,
    val indexes: MutableMap<String, IndexShape> = mutableMapOf(),
)

internal class IndexShape(val keyPath: List<String>, val unique: Boolean)

/**
 * The declared schema of one IndexedDB database: its name and its ordered migrations.
 *
 * Folding the migrations gives the shape the database is expected to have, which is what makes the
 * open-time verification of decision 13 possible — and folding them at construction time is also what
 * catches an incoherent migration list (indexing a store that was never created, creating one twice)
 * before any of it reaches a user's disk.
 */
public class Schema(
    public val databaseName: String,
    public val migrations: List<Migration>,
) {
    internal val shape: Map<String, StoreShape>
    internal val storesByName: Map<String, Store<*>>

    /** The stores the schema ends up with, in creation order. */
    public val storeNames: List<String> get() = shape.keys.toList()

    /** The IndexedDB version this schema describes: its last migration's. */
    public val version: Int

    init {
        if (migrations.isEmpty()) {
            throw SchemaException("Schema '$databaseName' has no migrations; it must have at least one")
        }
        var previous = 0
        for (migration in migrations) {
            if (migration.version <= 0) {
                throw SchemaException(
                    "Migration version must be positive, was ${migration.version} in schema " +
                        "'$databaseName' (IndexedDB versions start at 1)",
                )
            }
            if (migration.version <= previous) {
                throw SchemaException(
                    "Migrations of schema '$databaseName' must be in ascending version order: " +
                        "${migration.version} follows $previous",
                )
            }
            previous = migration.version
        }
        version = previous

        val shape = LinkedHashMap<String, StoreShape>()
        val stores = LinkedHashMap<String, Store<*>>()
        for (migration in migrations) {
            for (step in migration.steps) {
                applyToShape(step, migration.version, shape, stores)
            }
        }
        this.shape = shape
        this.storesByName = stores
    }

    private fun applyToShape(
        step: SchemaStep,
        version: Int,
        shape: MutableMap<String, StoreShape>,
        stores: MutableMap<String, Store<*>>,
    ) {
        when (step) {
            is SchemaStep.CreateStore<*> -> {
                val store = step.store
                if (shape.containsKey(store.storeName)) {
                    throw SchemaException(
                        "Migration $version of schema '$databaseName' creates store " +
                            "'${store.storeName}', which already exists",
                    )
                }
                shape[store.storeName] = StoreShape(store.keyPath(), store.autoIncrement)
                stores[store.storeName] = store
            }
            is SchemaStep.AddIndex<*> -> {
                val storeName = step.store.storeName
                val existing = shape[storeName] ?: throw SchemaException(
                    "Migration $version of schema '$databaseName' adds index " +
                        "'${step.index.indexName}' to store '$storeName', which the schema never creates",
                )
                if (existing.indexes.containsKey(step.index.indexName)) {
                    throw SchemaException(
                        "Migration $version of schema '$databaseName' adds index " +
                            "'${step.index.indexName}' to store '$storeName' twice",
                    )
                }
                existing.indexes[step.index.indexName] =
                    IndexShape(step.index.fields.map { it.name }, step.index.unique)
            }
            is SchemaStep.DropIndex -> {
                val existing = shape[step.storeName] ?: throw SchemaException(
                    "Migration $version of schema '$databaseName' drops an index of store " +
                        "'${step.storeName}', which the schema never creates",
                )
                existing.indexes.remove(step.indexName) ?: throw SchemaException(
                    "Migration $version of schema '$databaseName' drops index '${step.indexName}' of " +
                        "store '${step.storeName}', which the schema never adds",
                )
            }
            is SchemaStep.DropStore -> {
                shape.remove(step.storeName) ?: throw SchemaException(
                    "Migration $version of schema '$databaseName' drops store '${step.storeName}', " +
                        "which the schema never creates",
                )
                stores.remove(step.storeName)
            }
        }
    }
}
