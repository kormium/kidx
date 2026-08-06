package com.juul.indexeddb.logs

import com.juul.indexeddb.external.Event

internal object NoOpLogger : Logger {

    override fun log(type: Type, event: Event?, message: () -> String) {}
}
