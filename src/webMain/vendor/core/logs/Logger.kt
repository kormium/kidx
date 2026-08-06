package com.juul.indexeddb.logs

import com.juul.indexeddb.external.Event

internal interface Logger {
    public fun log(type: Type, event: Event? = null, message: () -> String)
}
