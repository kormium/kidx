package io.github.kidx

/**
 * Makes an IndexedDB implementation available to the tests in this source set.
 *
 * The engine tests live here, shared, rather than in a js-only source set — so the same suite runs
 * under Node (on `fake-indexeddb`), in a browser on the js target, and in a browser on wasmJs. That
 * matters because `fake-indexeddb` passing is evidence about kidx, not about a browser: only a real
 * engine can confirm abort behaviour, key ordering or quota (SPEC.md, "Testing").
 */
internal expect fun installIndexedDb()
