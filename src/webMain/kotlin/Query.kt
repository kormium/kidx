package io.github.kidx

/** Which way the cursor walks. The order itself is the index's own (decision 7). */
public enum class Direction { ASC, DESC }

/**
 * What a query actually asks the engine for: a key range, a direction and a count. Nothing more.
 *
 * Obtainable without a database, through [Store.describe] — which is how the prefix rule is tested and
 * how a call site can be checked before it runs, the way kormium's `renderSql { }` is used.
 *
 * [lower] and [upper] hold **domain** values, in index-field order; `null` means unbounded.
 * [upperUnboundedTail] marks a prefix scan: "these leading components, then anything", which the
 * engine gets as an upper bound one element longer, ending in a value that sorts above every key
 * (arrays sort last in IndexedDB, so an empty array is that value).
 */
public class QueryDescription internal constructor(
    public val indexName: String,
    public val lower: List<Any?>?,
    public val lowerOpen: Boolean,
    public val upper: List<Any?>?,
    public val upperOpen: Boolean,
    public val upperUnboundedTail: Boolean,
    public val direction: Direction,
    public val limit: Int?,
) {
    override fun toString(): String = buildString {
        append(indexName)
        append(' ')
        when {
            lower == null && upper == null -> append("all")
            lower != null && upper != null && !upperUnboundedTail && !lowerOpen && !upperOpen &&
                lower == upper -> append("= ${lower.render()}")
            else -> {
                append(if (lowerOpen) "(" else "[")
                append(lower?.render() ?: "-inf")
                append(" .. ")
                append(upper?.render() ?: "+inf")
                if (upperUnboundedTail) append(", *")
                append(if (upperOpen) ")" else "]")
            }
        }
        append(" $direction")
        if (limit != null) append(" limit $limit")
    }

    private fun List<Any?>.render(): String = joinToString(prefix = "(", postfix = ")")
}

private enum class Op { EQ, GT, GT_EQ, LT, LT_EQ, BETWEEN }

private class Bound(val field: Field.KeyField<*, *, *>, val op: Op, val from: Any?, val to: Any?)

/**
 * Builds a query against one declared index. The body is a list of positional constraints on the
 * index's fields, in index-field order — leading fields pinned with [eq], at most one range and only
 * on the last field named.
 *
 * There is deliberately no `where { }` wrapper. In kormium two `where` blocks are a boolean AND in any
 * order; here order is significant and there is no boolean logic at all, so borrowing the word would
 * promise something IndexedDB cannot do (decision 7).
 */
public class QueryBuilder<R : Row> internal constructor(private val index: Index<R>) {

    private val bounds = mutableListOf<Bound>()

    /** Which way the cursor walks. Defaults to [Direction.ASC]. */
    public var direction: Direction = Direction.ASC

    /** At most this many rows. `null` means all of them; `0` means the engine is never asked. */
    public var limit: Int? = null

    /** Pins a field to one value. Every field before the last one named must be pinned. */
    public infix fun <Z> Field.KeyField<Z, *, R>.eq(value: Z) {
        add(this, Op.EQ, value, value)
    }

    public infix fun <Z : Comparable<Z>> Field.KeyField<Z, *, R>.gt(value: Z) {
        add(this, Op.GT, value, null)
    }

    public infix fun <Z : Comparable<Z>> Field.KeyField<Z, *, R>.gtEq(value: Z) {
        add(this, Op.GT_EQ, value, null)
    }

    public infix fun <Z : Comparable<Z>> Field.KeyField<Z, *, R>.lt(value: Z) {
        add(this, Op.LT, null, value)
    }

    public infix fun <Z : Comparable<Z>> Field.KeyField<Z, *, R>.ltEq(value: Z) {
        add(this, Op.LT_EQ, null, value)
    }

    /** An inclusive range. An inverted one is refused here rather than by the engine's `DataError`. */
    public infix fun <Z : Comparable<Z>> Field.KeyField<Z, *, R>.between(range: ClosedRange<Z>) {
        if (range.start > range.endInclusive) {
            throw QueryException(
                "Range on '$name' is inverted: ${range.start} > ${range.endInclusive}. IndexedDB " +
                    "rejects an inverted key range; give the bounds in ascending order.",
            )
        }
        add(this, Op.BETWEEN, range.start, range.endInclusive)
    }

    private fun add(field: Field.KeyField<*, *, R>, op: Op, from: Any?, to: Any?) {
        val position = index.fields.indexOfFirst { it === field }
        if (position < 0) {
            throw QueryException(
                "Field '${field.name}' is not part of index '${index.indexName}' " +
                    "(${index.fields.joinToString { it.name }}). Query an index that covers it, or " +
                    "declare one.",
            )
        }
        if (bounds.any { it.field === field }) {
            throw QueryException(
                "Field '${field.name}' is constrained twice in one query on index " +
                    "'${index.indexName}'. Use between() for a two-sided range.",
            )
        }
        val previous = bounds.lastOrNull()
        if (previous != null && previous.op != Op.EQ) {
            throw QueryException(
                "Nothing may follow a range in a query on index '${index.indexName}': " +
                    "'${previous.field.name}' is already a range, so '${field.name}' cannot be " +
                    "constrained too. IndexedDB scans one contiguous key range — pin " +
                    "'${previous.field.name}' with eq, or query on a different index.",
            )
        }
        if (position != bounds.size) {
            val expected = index.fields[bounds.size].name
            throw QueryException(
                "Query on index '${index.indexName}' must constrain its fields as a prefix, in order: " +
                    "expected '$expected' next, got '${field.name}'. Pin '$expected' with eq first.",
            )
        }
        bounds += Bound(field, op, from, to)
    }

    internal fun build(): QueryDescription {
        val limit = limit
        if (limit != null && limit < 0) {
            throw QueryException("Limit on index '${index.indexName}' must not be negative, was $limit")
        }

        // The pinned prefix, then at most one range on the field after it.
        val pinned = bounds.takeWhile { it.op == Op.EQ }.map { it.from }
        val range = bounds.getOrNull(pinned.size)
        val exhaustive = pinned.size == index.fields.size

        var lower: List<Any?>? = pinned.takeIf { it.isNotEmpty() }
        var upper: List<Any?>? = pinned.takeIf { it.isNotEmpty() }
        var lowerOpen = false
        var upperOpen = false
        // Only when the pinned prefix stops short of the whole key does "and anything after" apply.
        var upperTail = !exhaustive && pinned.isNotEmpty()

        if (range != null) {
            when (range.op) {
                Op.GT, Op.GT_EQ -> {
                    lower = pinned + range.from
                    lowerOpen = range.op == Op.GT
                    // Upper stays the pinned prefix with an open tail; with nothing pinned it is
                    // unbounded, which is what a range on the leading field means.
                    upper = pinned.takeIf { it.isNotEmpty() }
                    upperTail = upper != null
                }
                Op.LT, Op.LT_EQ -> {
                    upper = pinned + range.to
                    upperOpen = range.op == Op.LT
                    upperTail = false
                    lower = pinned.takeIf { it.isNotEmpty() }
                }
                Op.BETWEEN -> {
                    lower = pinned + range.from
                    upper = pinned + range.to
                    upperTail = false
                }
                Op.EQ -> error("unreachable: an EQ bound is part of the pinned prefix")
            }
        }

        return QueryDescription(
            indexName = index.indexName,
            lower = lower,
            lowerOpen = lowerOpen,
            upper = upper,
            upperOpen = upperOpen,
            upperUnboundedTail = upperTail,
            direction = direction,
            limit = limit,
        )
    }
}

/**
 * The key range, direction and limit a query would ask for — with no database involved. Use it in
 * tests, and to check a call site before running it; the analogue of kormium's `renderSql { }`.
 */
public fun <R : Row> Store<R>.describe(
    index: Index<R>,
    block: QueryBuilder<R>.() -> Unit,
): QueryDescription {
    if (index.store !== this) {
        throw QueryException(
            "Index '${index.indexName}' belongs to store '${index.store.storeName}', not '$storeName'.",
        )
    }
    return QueryBuilder(index).apply(block).build()
}
