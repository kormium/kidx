package com.juul.indexeddb

import com.juul.indexeddb.external.IDBCreateObjectStoreOptions

internal object AutoIncrement {
    internal fun toOptions(): IDBCreateObjectStoreOptions = jso { autoIncrement = true }
}
