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

/**
 * A failure the engine reported. IndexedDB answers with a `DOMException` whose `name` is the whole
 * classification, so the useful ones get their own type and the rest arrive as this.
 *
 * These exist because the driver's own exception types are not part of kidx's public surface: a
 * consumer must be able to tell a duplicate key from a full disk without naming a vendored class.
 */
public open class EngineException internal constructor(
    /** The `DOMException.name` the engine gave, e.g. `ConstraintError`. */
    public val errorName: String,
    message: String,
    cause: Throwable?,
) : KidxException(message, cause)

/** A duplicate primary key, or a unique-index violation. The whole transaction is gone with it. */
public class ConstraintViolationException internal constructor(
    message: String,
    cause: Throwable?,
) : EngineException("ConstraintError", message, cause)

/** Out of storage. The browser decides the budget, and can evict without asking. */
public class QuotaExceededException internal constructor(
    message: String,
    cause: Throwable?,
) : EngineException("QuotaExceededError", message, cause)

/**
 * The stored database is at a **higher** version than this code declares: newer code ran here before.
 * IndexedDB will not downgrade, and it should not — the newer schema may hold data this code cannot
 * read.
 *
 * Its own type because the remedy is different from every other open failure: not "close other tabs",
 * not "retry", but "this build is behind — reload or update".
 */
public class DatabaseTooNewException internal constructor(
    public val databaseName: String,
    public val declaredVersion: Int,
    cause: Throwable?,
) : EngineException(
    "VersionError",
    "Database '$databaseName' on disk is newer than the schema this code declares (version " +
        "$declaredVersion). Newer code has already run here, and IndexedDB does not downgrade — reload " +
        "or update the application. Deleting the database would lose the data the newer schema wrote.",
    cause,
)
