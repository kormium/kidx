package io.github.kidx

/**
 * Base of every failure kidx raises itself. Engine failures that kidx does not interpret propagate as
 * they come out of the driver.
 *
 * Message quality is a feature here, not a nicety: every one of these names the store, the field or
 * the index involved, what was expected, and what to do about it — the standard kormium sets with
 * `ResultMappingException`.
 */
public open class KidxException internal constructor(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A schema declaration that cannot describe a real IndexedDB database. */
public class SchemaException internal constructor(message: String) : KidxException(message)

/** The declared schema and the database on disk disagree (decision 13). */
public class SchemaMismatchException internal constructor(message: String) : KidxException(message)

/** A row could not be built from a stored record, or a record from a row (decision 9). */
public class RowMappingException internal constructor(
    message: String,
    cause: Throwable? = null,
) : KidxException(message, cause)

/** A query that IndexedDB cannot execute against the index it names (decision 7). */
public class QueryException internal constructor(message: String) : KidxException(message)

/**
 * Raised by a [FieldType] when a stored value has the wrong shape. The store adds the context — which
 * store, which field — so a type only has to say what it expected and what it found. Its constructor is
 * public because a custom [FieldType] needs to raise it too.
 */
public class FieldTypeException(message: String) : KidxException(message)
