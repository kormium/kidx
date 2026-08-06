package com.juul.indexeddb

import com.juul.indexeddb.external.IDBCreateObjectStoreOptions
import com.juul.indexeddb.external.IDBKeyPath
import kotlin.js.toJsArray
import kotlin.js.toJsString

internal class KeyPath(
    private val path: String,
    private vararg val morePaths: String,
) {

    internal fun toOptions(): IDBCreateObjectStoreOptions = jso { keyPath = toJs() }

    // kidx modification (see VENDOR.md): an in-line key path *and* a key generator, which IndexedDB
    // allows and which is the combination kidx needs — the generated key has to land inside the stored
    // record. Upstream exposes keyPath and autoIncrement as mutually exclusive overloads.
    internal fun toOptions(autoIncrement: Boolean): IDBCreateObjectStoreOptions = jso {
        keyPath = toJs()
        this.autoIncrement = autoIncrement
    }
    internal fun toJs(): IDBKeyPath = when (morePaths.isEmpty()) {
        true -> path.toJsString()
        false -> arrayOf(path, *morePaths).map { it.toJsString() }.toJsArray()
    }
}
