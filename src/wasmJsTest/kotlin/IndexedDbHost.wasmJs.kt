package io.github.kidx

/**
 * wasmJs runs in a browser only, which always has IndexedDB. There is no Node target here — a CommonJS
 * `require` of `fake-indexeddb` is not reachable from Wasm — so this is a no-op by construction.
 */
internal actual fun installIndexedDb() = Unit
