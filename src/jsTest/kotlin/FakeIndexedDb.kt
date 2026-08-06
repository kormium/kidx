package io.github.kidx

import kotlin.js.js

/**
 * Installs `fake-indexeddb` as the global IndexedDB implementation. Node has none of its own, and no
 * browser is required to exercise kidx's own logic against a real spec-conformant engine.
 *
 * It validates kidx, not the browsers: `fake-indexeddb` is a JS reimplementation with its own bugs, so
 * anything genuinely engine-specific still belongs in a browser test (SPEC.md, "Testing").
 */
internal fun installFakeIndexedDb() {
    js("if (!globalThis.indexedDB) { require('fake-indexeddb/auto'); }")
}
