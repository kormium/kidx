package com.juul.indexeddb.logs

import com.juul.indexeddb.external.Event

internal fun Logger.filterTypes(vararg whitelist: Type): Logger =
    filterTypes(whitelist.toSet())

internal fun Logger.filterTypes(whitelist: Set<Type>): Logger =
    FilteringLogger(whitelist, this)

private class FilteringLogger(
    val whitelist: Set<Type>,
    val delegate: Logger,
) : Logger {

    override fun log(type: Type, event: Event?, message: () -> String) {
        if (type in whitelist) {
            delegate.log(type, event, message)
        }
    }
}
