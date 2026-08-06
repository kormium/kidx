package com.juul.indexeddb.external

import kotlin.js.JsString

/** Must be one of `"next"`, `"nextunique"`, `"prev"`, or `"prevunique"`. */
internal typealias IDBCursorDirection = JsString
