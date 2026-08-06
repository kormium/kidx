package com.juul.indexeddb.external

import kotlin.js.JsAny

internal external interface IDBTransactionOptions : JsAny {
    public var durability: IDBDurability?
}
