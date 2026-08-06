package com.juul.indexeddb

import com.juul.indexeddb.external.IDBRequest
import kotlin.js.JsAny

internal class Request<T : JsAny?> internal constructor(
    internal val request: IDBRequest<T>,
)
