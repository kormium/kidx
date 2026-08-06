package io.github.kidx

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A typed field of store [S], carrying values of type [Z] on row [R]. A field *is* the property
 * delegate on the row, which is what makes `var name by Users.name` work with no reflection and no
 * per-row bookkeeping beyond one array slot.
 *
 * Two properties of a field are encoded in its **type**, not in a flag, so that the compiler enforces
 * what IndexedDB requires (decision 10):
 *
 * - nullability — [NullableField] hands back `Z?`, [NotNullField] hands back `Z`;
 * - key-validity — only a [KeyField] can be indexed or be a primary key, and only a [KeyFieldType]
 *   can produce one. `index(Users.verified)` does not compile, and neither does
 *   `Field.Boolean().primaryKey()`.
 *
 * A field's stored name is its Kotlin property name. There is no override: in IndexedDB a name is a
 * key in a JS object, not a quoted SQL identifier, and renaming a stored property is a data migration
 * rather than a cosmetic change (decision 12).
 */
public sealed class Field<Z, S : Store<R>, R : Row>(
    internal val store: S,
    public val name: String,
    public val nullable: kotlin.Boolean,
    public val type: FieldType<Z>,
) {
    /** This field's position in its store's declaration order, and its slot in [Row.values]. */
    public var ordinal: kotlin.Int = -1
        internal set

    internal var isPrimaryKey: kotlin.Boolean = false

    override fun toString(): String = "${store.storeName}.$name"

    /**
     * A non-null field. Reading one that was never assigned, or that came back as a stored null,
     * throws — a schema mismatch surfaced where it happened rather than as a null much later.
     *
     * The accessors deliberately do not log or allocate: they are the per-field hot path.
     */
    public open class NotNullField<Z, S : Store<R>, R : Row> internal constructor(
        store: S,
        name: String,
        type: FieldType<Z>,
    ) : Field<Z, S, R>(store, name, nullable = false, type) {

        public operator fun getValue(row: R, property: KProperty<*>): Z {
            val value = row.slotGet(this)
            if (value === ABSENT) {
                throw RowMappingException(
                    "Field '$name' of store '${store.storeName}' is not set (expected " +
                        "${type.description}). Either the row was never given a value for it, or the " +
                        "stored record predates the field — see isSet().",
                )
            }
            if (value == null) {
                throw RowMappingException(
                    "Field '$name' of store '${store.storeName}' is non-null, but the stored value is " +
                        "null. The record does not match the schema.",
                )
            }
            @Suppress("UNCHECKED_CAST")
            return value as Z
        }

        public operator fun setValue(row: R, property: KProperty<*>, value: Z) {
            row.slotSet(this, value)
        }
    }

    /** A nullable field. Its row property is `Z?`; an absent field reads back as `null` too. */
    public class NullableField<Z, S : Store<R>, R : Row> internal constructor(
        store: S,
        name: String,
        type: FieldType<Z>,
    ) : Field<Z, S, R>(store, name, nullable = true, type) {

        public operator fun getValue(row: R, property: KProperty<*>): Z? {
            val value = row.slotGet(this)
            @Suppress("UNCHECKED_CAST")
            return if (value === ABSENT) null else value as Z?
        }

        public operator fun setValue(row: R, property: KProperty<*>, value: Z?) {
            row.slotSet(this, value)
        }
    }

    /**
     * A non-null field whose [FieldType] is a [KeyFieldType]: the only kind `index()` and
     * `primaryKey()` accept, because it is the only kind IndexedDB can use as a key.
     */
    public class KeyField<Z, S : Store<R>, R : Row> internal constructor(
        store: S,
        name: String,
        public val keyType: KeyFieldType<Z>,
    ) : NotNullField<Z, S, R>(store, name, keyType)

    // ---- declaration builders ----
    //
    // Four separate classes rather than one hierarchy, so each carries its own provideDelegate return
    // type — and so the illegal combinations simply have no method to call. `nullable().primaryKey()`
    // and `Field.Boolean().primaryKey()` are not errors to diagnose; they do not exist.

    /** Entry point for a field whose type cannot be a key. Refine with [nullable]. */
    public open class Spec<Z> internal constructor(private val type: FieldType<Z>) {
        public operator fun <S : Store<R>, R : Row> provideDelegate(
            store: S,
            property: KProperty<*>,
        ): ReadOnlyProperty<S, NotNullField<Z, S, R>> {
            val field = NotNullField<Z, S, R>(store, property.name, type)
            store.addField(field)
            return ReadOnlyProperty { _, _ -> field }
        }

        public fun nullable(): NullableSpec<Z> = NullableSpec(type)
    }

    /** Entry point for a key-valid type. Refine with [nullable] or [primaryKey]. */
    public open class KeySpec<Z> internal constructor(private val type: KeyFieldType<Z>) {
        public operator fun <S : Store<R>, R : Row> provideDelegate(
            store: S,
            property: KProperty<*>,
        ): ReadOnlyProperty<S, KeyField<Z, S, R>> {
            val field = KeyField<Z, S, R>(store, property.name, type)
            store.addField(field)
            return ReadOnlyProperty { _, _ -> field }
        }

        /** A nullable field is not indexable anyway, so this drops the key-ness with it. */
        public fun nullable(): NullableSpec<Z> = NullableSpec(type)

        /**
         * Marks the field as the primary key, or as one component of a composite one (declaration
         * order is key order). With [autoIncrement] the engine generates the key when it is left
         * absent — the one database-generated value IndexedDB has (decision 12).
         */
        public fun primaryKey(autoIncrement: kotlin.Boolean = false): PrimaryKeySpec<Z> =
            PrimaryKeySpec(type, autoIncrement)
    }

    /** A nullable field: no [primaryKey], because a nullable key cannot be expressed. */
    public class NullableSpec<Z> internal constructor(private val type: FieldType<Z>) {
        public operator fun <S : Store<R>, R : Row> provideDelegate(
            store: S,
            property: KProperty<*>,
        ): ReadOnlyProperty<S, NullableField<Z, S, R>> {
            val field = NullableField<Z, S, R>(store, property.name, type)
            store.addField(field)
            return ReadOnlyProperty { _, _ -> field }
        }
    }

    /** A primary-key field: no [nullable], for the same reason. */
    public class PrimaryKeySpec<Z> internal constructor(
        private val type: KeyFieldType<Z>,
        private val autoIncrement: kotlin.Boolean,
    ) {
        public operator fun <S : Store<R>, R : Row> provideDelegate(
            store: S,
            property: KProperty<*>,
        ): ReadOnlyProperty<S, KeyField<Z, S, R>> {
            val field = KeyField<Z, S, R>(store, property.name, type)
            field.isPrimaryKey = true
            store.addField(field)
            if (autoIncrement) store.markAutoIncrement(field)
            return ReadOnlyProperty { _, _ -> field }
        }
    }

    // ---- the built-in field declarations (decision 10) ----

    public class UUID : KeySpec<Uuid>(UuidFieldType)
    public class Text : KeySpec<String>(TextFieldType)
    public class Int : KeySpec<kotlin.Int>(IntFieldType)
    public class Double : KeySpec<kotlin.Double>(DoubleFieldType)
    public class Instant : KeySpec<kotlin.time.Instant>(InstantFieldType)
    public class Boolean : Spec<kotlin.Boolean>(BooleanFieldType)
    public class Blob : Spec<io.github.kidx.Blob>(BlobFieldType)

    public companion object {
        /** Declares a field of any [FieldType] — the open extension point. */
        public fun <Z> of(type: FieldType<Z>): Spec<Z> = Spec(type)

        /** Declares a field of a key-valid type, so it can be indexed or be a primary key. */
        public fun <Z> of(type: KeyFieldType<Z>): KeySpec<Z> = KeySpec(type)

        /** An enum stored by name, as text. */
        public inline fun <reified E : Enum<E>> enum(): KeySpec<E> = of(enumFieldType<E>())
    }
}

/**
 * An index over one or more key-valid fields of a store. Its name is the property name it is declared
 * on, and its field order is the key order — which is what makes a query against it a prefix scan
 * (decision 11).
 */
public class Index<R : Row> internal constructor(
    public val store: Store<R>,
    public val indexName: String,
    public val fields: List<Field.KeyField<*, *, R>>,
    public val unique: Boolean,
) {
    override fun toString(): String =
        "${store.storeName}.$indexName(${fields.joinToString { it.name }})${if (unique) " unique" else ""}"
}

/**
 * Declares an index: `val byUser by index(userId, createdAt)`.
 *
 * Only [Field.KeyField]s are accepted, so a nullable field and a field whose type cannot be a key are
 * both compile errors rather than records silently missing from the index (decision 10).
 */
public fun <R : Row> Store<R>.index(
    vararg fields: Field.KeyField<*, *, R>,
    unique: Boolean = false,
): PropertyDelegateProvider<Store<R>, ReadOnlyProperty<Store<R>, Index<R>>> {
    if (fields.isEmpty()) throw SchemaException("Index on store '$storeName' needs at least one field")
    // `R` rejects a field of a store over a different row type at compile time. Two stores sharing one
    // row type is the hole it cannot close, so the check exists at runtime as well.
    for (field in fields) {
        if (field.store !== this) {
            throw SchemaException(
                "Cannot index field '${field.name}' of store '${field.store.storeName}' on store " +
                    "'$storeName' — an index covers fields of its own store only.",
            )
        }
    }
    val declared = fields.toList()
    return PropertyDelegateProvider { store, property ->
        val index = Index(store, property.name, declared, unique)
        store.addIndex(index)
        ReadOnlyProperty { _, _ -> index }
    }
}
