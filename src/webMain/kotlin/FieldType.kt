package io.github.kidx

import kotlin.js.JsAny
import kotlin.js.JsBoolean
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.toBoolean
import kotlin.js.toDouble
import kotlin.js.toJsBoolean
import kotlin.js.toJsNumber
import kotlin.js.toJsString
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * How a Kotlin value of type [T] is stored and read back. Value conversion only — no DDL, no keys, no
 * nullability: null never reaches a field type, because [Store.encode] stores it directly and
 * [Store.decode] never asks for it.
 *
 * The set of types is open. Add one by [convert]ing an existing type (the common case) or by
 * implementing this interface; either way the stored representation is yours to choose knowingly,
 * which is the whole reason kidx ships fewer built-ins than kormium (decision 10).
 */
public interface FieldType<T> {
    /** Encodes [value] into what the engine stores. Never returns null. */
    public fun encode(value: T): JsAny

    /** Decodes a stored value. Throws [FieldTypeException] if it is not the expected shape. */
    public fun decode(stored: JsAny): T

    /** A short name for diagnostics: the text in "expected Text, found number". */
    public val description: String
}

/**
 * A [FieldType] whose stored form is a **valid IndexedDB key** — a number, a string, a `Date`, or an
 * array of those. Only these can be indexed or be a primary key, and that is enforced by the type
 * system rather than by a runtime check: `index()` and `primaryKey()` accept only a
 * [Field.KeyField], which only a [KeyFieldType] can produce (decision 10).
 */
public interface KeyFieldType<T> : FieldType<T>

/**
 * Derives a type for [Domain] from this one for [Stored] by mapping values both ways — kormium's
 * `convert`, unchanged. Storage and diagnostics are inherited; you translate the value.
 */
public fun <Domain, Stored> FieldType<Stored>.convert(
    toStored: (Domain) -> Stored,
    fromStored: (Stored) -> Domain,
): FieldType<Domain> {
    val base = this
    return object : FieldType<Domain> {
        override fun encode(value: Domain): JsAny = base.encode(toStored(value))
        override fun decode(stored: JsAny): Domain = fromStored(base.decode(stored))
        override val description: String get() = "${base.description} (converted)"
    }
}

/** Key-ness survives conversion, so an enum stored as text stays indexable. */
public fun <Domain, Stored> KeyFieldType<Stored>.convert(
    toStored: (Domain) -> Stored,
    fromStored: (Stored) -> Domain,
): KeyFieldType<Domain> {
    val base = this
    return object : KeyFieldType<Domain> {
        override fun encode(value: Domain): JsAny = base.encode(toStored(value))
        override fun decode(stored: JsAny): Domain = fromStored(base.decode(stored))
        override val description: String get() = "${base.description} (converted)"
    }
}

// ---- the built-in types (decision 10) ----

public object TextFieldType : KeyFieldType<String> {
    override fun encode(value: String): JsAny = value.toJsString()
    override fun decode(stored: JsAny): String = stored.asString(description)
    override val description: String get() = "Text"
}

public object IntFieldType : KeyFieldType<Int> {
    override fun encode(value: Int): JsAny = value.toJsNumber()
    override fun decode(stored: JsAny): Int = stored.asDouble(description).toInt()
    override val description: String get() = "Int"
}

public object DoubleFieldType : KeyFieldType<Double> {
    override fun encode(value: Double): JsAny = value.toJsNumber()
    override fun decode(stored: JsAny): Double = stored.asDouble(description)
    override val description: String get() = "Double"
}

public object BooleanFieldType : FieldType<Boolean> {
    override fun encode(value: Boolean): JsAny = value.toJsBoolean()
    override fun decode(stored: JsAny): Boolean {
        if (jsTypeOf(stored) != "boolean") throw wrongShape(description, stored)
        @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
        return (stored as JsBoolean).toBoolean()
    }
    override val description: String get() = "Boolean"
}

/**
 * A UUID, stored as its canonical 36-character string: what is on disk is what is in the logs and in
 * the code, and for UUIDv7 the lexicographic order is the chronological one (decision 10).
 */
public object UuidFieldType : KeyFieldType<Uuid> {
    override fun encode(value: Uuid): JsAny = value.toString().toJsString()
    override fun decode(stored: JsAny): Uuid {
        val text = stored.asString(description)
        return try {
            Uuid.parse(text)
        } catch (e: IllegalArgumentException) {
            throw FieldTypeException("expected $description, found the string \"$text\", which is not a UUID")
        }
    }
    override val description: String get() = "UUID"
}

/**
 * An instant, stored as a native `Date`: a valid key, chronologically ordered, and readable as a date
 * in devtools. Precision is milliseconds — a declared property of this type, so sub-millisecond
 * timestamps are truncated rather than silently mis-ordered (decision 10).
 */
public object InstantFieldType : KeyFieldType<Instant> {
    override fun encode(value: Instant): JsAny = jsDate(value.toEpochMilliseconds().toDouble())
    override fun decode(stored: JsAny): Instant {
        if (!jsIsDate(stored)) throw wrongShape(description, stored)
        return Instant.fromEpochMilliseconds(jsDateMillis(stored).toLong())
    }
    override val description: String get() = "Instant"
}

/**
 * An opaque handle to a platform `Blob`. kidx stores it and hands it back; it does not read, slice or
 * build one — which is exactly why it is worth having, since the engine keeps a blob out of memory
 * until something asks for its contents (decision 10). Not a valid key.
 */
public external interface Blob : JsAny

public object BlobFieldType : FieldType<Blob> {
    override fun encode(value: Blob): JsAny = value
    override fun decode(stored: JsAny): Blob {
        if (jsTypeOf(stored) != "object") throw wrongShape(description, stored)
        @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
        return stored as Blob
    }
    override val description: String get() = "Blob"
}

/** An enum stored by name, as text — the smallest useful demonstration of [convert]. */
public inline fun <reified E : Enum<E>> enumFieldType(): KeyFieldType<E> {
    val byName = enumValues<E>().associateBy { it.name }
    val name = E::class.simpleName ?: "enum"
    return TextFieldType.convert(
        toStored = { value: E -> value.name },
        fromStored = { stored: String ->
            byName[stored] ?: throw FieldTypeException("expected a $name value, found \"$stored\"")
        },
    )
}

// ---- shape checks, shared by the built-ins ----

private fun wrongShape(expected: String, stored: JsAny): FieldTypeException =
    FieldTypeException("expected $expected, found ${jsDescribe(stored)}")

private fun JsAny.asString(expected: String): String {
    if (jsTypeOf(this) != "string") throw wrongShape(expected, this)
    // Safe after the typeof check; on Wasm the cast itself cannot verify an external type.
    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    return (this as JsString).toString()
}

private fun JsAny.asDouble(expected: String): Double {
    if (jsTypeOf(this) != "number") throw wrongShape(expected, this)
    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    return (this as JsNumber).toDouble()
}
