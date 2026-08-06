package com.juul.indexeddb.external

import kotlin.js.JsAny

internal external class IDBAvailableDatabase : JsAny {
    public var name: String
    public var version: Int
}
