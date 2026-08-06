package io.github.kidx

/**
 * Marks a slot that was never assigned, as opposed to one holding an explicit null. A private
 * sentinel: it never escapes this module, so no user value can be mistaken for it.
 */
internal val ABSENT: Any = Any()

private val NO_VALUES: Array<Any?> = emptyArray()

/**
 * Base class for rows. A row is declared with a no-argument constructor and typed property delegates,
 * exactly as a kormium `Entity` is:
 *
 * ```kotlin
 * class User : Row() {
 *     var id by Users.id
 *     var name by Users.name
 *     var note by Users.note      // String?
 * }
 * ```
 *
 * A row is **not** a DTO. It wraps slot storage that distinguishes three states per field — absent,
 * explicit null, and a value — and that storage is an implementation detail: rows are not
 * serializable and must not be sent through `postMessage`. Map to your own type at the boundary.
 *
 * Values live in [values], indexed by [Field.ordinal], which is a field's position in its store's
 * declaration order. Ordinals are unique within one store, so the array belongs to exactly one store:
 * the first assignment pins [owner], and a field of any other store is an error rather than a second,
 * invisible storage path (decision 3).
 */
public abstract class Row {
    internal var values: Array<Any?> = NO_VALUES
    internal var owner: Store<*>? = null
}

/** Reads [field]'s slot, returning [ABSENT] when it was never assigned. */
internal fun Row.slotGet(field: Field<*, *, *>): Any? {
    if (field.store !== owner) return ABSENT
    val slots = values
    val ordinal = field.ordinal
    return if (ordinal < slots.size) slots[ordinal] else ABSENT
}

/** Assigns [field]'s slot. The first assignment fixes which store owns [Row.values]. */
internal fun Row.slotSet(field: Field<*, *, *>, value: Any?) {
    val store = field.store
    val current = owner
    if (current == null) {
        owner = store
        values = Array(store.declaredFields.size) { ABSENT }
    } else if (current !== store) {
        throw RowMappingException(
            "Row of store '${current.storeName}' cannot hold field '${field.name}' of store " +
                "'${store.storeName}'. A row belongs to exactly one store — declare a separate row " +
                "class for '${store.storeName}'.",
        )
    }
    values[field.ordinal] = value
}

/** Returns [field]'s slot to the absent state. Used by [Store.decode] and by generated-key writes. */
internal fun Row.slotClear(field: Field<*, *, *>) {
    if (field.store === owner) {
        val slots = values
        if (field.ordinal < slots.size) slots[field.ordinal] = ABSENT
    }
}

/** Installs a decoded record's values. */
internal fun Row.adopt(values: Array<Any?>, owner: Store<*>) {
    this.values = values
    this.owner = owner
}

/**
 * True when [field] had a value on this row — including an explicit null. Use it to tell "stored as
 * null" from "the record predates this field" (decision 9); a nullable field reads back as `null` in
 * both cases.
 *
 * The field must belong to this row's store: `user.isSet(Orders.total)` does not compile.
 */
public fun <R : Row> R.isSet(field: Field<*, *, R>): Boolean = slotGet(field) !== ABSENT

/**
 * Assigns [field] without naming it as a property — the escape hatch for code that works over a
 * store's fields generically. Internal: the declared delegates are the API.
 */
internal operator fun <R : Row, Z> R.set(field: Field<Z, *, R>, value: Z?) {
    slotSet(field, value)
}
