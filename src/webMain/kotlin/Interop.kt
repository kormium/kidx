package io.github.kidx

import kotlin.js.JsAny
import kotlin.js.js

/**
 * The whole JavaScript boundary, in one file.
 *
 * Everything here is a `js(...)` intrinsic rather than an `external` declaration, for two reasons.
 * It compiles identically for Kotlin/JS and Kotlin/Wasm from the one `webMain` source set — no
 * `expect`/`actual` pair for something this small — and it stays a direct property access or call
 * with nothing wrapped around it.
 *
 * A stored record is a plain JS object. kidx never hands a Kotlin object to the engine (structured
 * clone would mangle it) and never reads one back: [Store.encode] and [Store.decode] are the only
 * places records are built and taken apart.
 */

internal fun newRecord(): JsAny = js("({})")

/**
 * Property *presence*, which is the absent-vs-null distinction decision 3 rests on. It cannot be a
 * null check: Kotlin/Wasm maps both `null` and `undefined` onto Kotlin `null`, so a missing property
 * and a stored null are indistinguishable by value. `in` tells them apart.
 */
internal fun recordHas(record: JsAny, name: String): Boolean = js("name in record")

internal fun recordGet(record: JsAny, name: String): JsAny? = js("record[name]")

internal fun recordSet(record: JsAny, name: String, value: JsAny?) {
    js("record[name] = value")
}

internal fun recordDelete(record: JsAny, name: String) {
    js("delete record[name]")
}

/**
 * `typeof`, used by the field types to reject a stored value of the wrong shape. A Kotlin `as?` will
 * not do it: on Wasm the JS types are external, so a cast to one is unchecked and succeeds on
 * anything — which would turn "a number is stored where Text was declared" into a corrupt value
 * instead of an error.
 */
internal fun jsTypeOf(value: JsAny): String = js("typeof value")

internal fun jsIsDate(value: JsAny): Boolean = js("value instanceof Date")

internal fun jsDate(millis: Double): JsAny = js("new Date(millis)")

internal fun jsDateMillis(value: JsAny): Double = js("value.getTime()")

/** For diagnostics only: what was actually found, when a decode fails. */
internal fun jsDescribe(value: JsAny?): String =
    js("value === null ? 'null' : value === undefined ? 'undefined' : value instanceof Date ? 'Date' : typeof value")

/** `null`/`undefined` from the engine: a `get` that found nothing returns `undefined`. */
internal fun jsIsNullish(value: JsAny?): Boolean = js("value === null || value === undefined")

/** The upper-bound sentinel for a prefix scan: arrays sort above every other key type. */
internal fun jsEmptyArray(): JsAny = js("[]")

// ---- BroadcastChannel, for the cross-context transport (decision 15) ----

internal fun broadcast(channelName: String, message: String) {
    js("{ const c = new BroadcastChannel(channelName); c.postMessage(message); c.close(); }")
}

internal fun openBroadcast(channelName: String): JsAny = js("new BroadcastChannel(channelName)")

internal fun closeBroadcast(channel: JsAny) {
    js("channel.close()")
}

internal fun onBroadcast(channel: JsAny, handler: (String) -> Unit) {
    js("channel.onmessage = (event) => handler(String(event.data))")
}

/**
 * The `DOMException.name` behind an error event — `ConstraintError`, `QuotaExceededError`, and so on.
 * It hangs off the failing request rather than the event, and is absent on a transaction-level abort.
 */
internal fun jsErrorName(event: JsAny): String =
    js("(event.target && event.target.error && event.target.error.name) || (event.error && event.error.name) || ''")
