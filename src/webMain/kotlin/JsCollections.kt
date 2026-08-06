package io.github.kidx

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsString
import kotlin.js.get
import kotlin.js.js
import kotlin.js.toJsArray
import kotlin.js.toJsString

/** `JsArray<JsString>` -> `List<String>`, for the engine's introspection properties. */
internal fun JsArray<JsString>.toList(): List<String> =
    List(length) { index -> this[index]!!.toString() }

internal fun List<String>.toJsStringArray(): JsArray<JsString> =
    map { it.toJsString() }.toJsArray()

/**
 * An IndexedDB `keyPath` is either a string or an array of strings; both read back as a list here so a
 * declared key path and a stored one can be compared without caring which form the engine chose.
 */
internal fun keyPathToList(keyPath: JsAny?): List<String> {
    if (keyPath == null) return emptyList()
    return when (jsTypeOf(keyPath)) {
        "string" -> listOf(keyPath.toString())
        else -> keyPathParts(keyPath).split(",").filter { it.isNotEmpty() }
    }
}

private fun keyPathParts(keyPath: JsAny): String = js("Array.prototype.join.call(keyPath, ',')")
