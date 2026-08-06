package com.juul.indexeddb.external

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.Promise
import kotlin.js.js

/** https://developer.mozilla.org/en-US/docs/Web/API/IDBFactory */
internal external class IDBFactory : JsAny {
    public fun open(name: String, version: Int): IDBOpenDBRequest
    public fun databases(): Promise<JsArray<IDBAvailableDatabase>>
    public fun deleteDatabase(name: String): IDBOpenDBRequest
}

// kidx modification (see VENDOR.md): `globalThis` instead of `self`, and a getter instead of an
// eagerly-initialized val. `self` does not exist outside a browser/worker, so the original threw a
// ReferenceError merely by being loaded under a Node test runner; and reading it lazily lets a test
// install an IndexedDB implementation before the first use rather than before module load.
internal val indexedDB: IDBFactory?
    get() = currentIndexedDB()

// A top-level function, because Kotlin/Wasm only allows `js(...)` as a whole top-level function body or
// a property initializer — not in a getter.
private fun currentIndexedDB(): IDBFactory? = js("globalThis.indexedDB || globalThis.webkitIndexedDB")
