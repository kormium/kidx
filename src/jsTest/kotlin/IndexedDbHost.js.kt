package io.github.kidx

import kotlin.js.js

/**
 * In a browser there is a real IndexedDB and this does nothing. Under Node there is none, so
 * `fake-indexeddb` is installed on first use — which works only because the vendored driver reads
 * `globalThis.indexedDB` lazily (see VENDOR.md).
 */
internal actual fun installIndexedDb() {
    js("if (!globalThis.indexedDB) { require('fake-indexeddb/auto'); }")
}
