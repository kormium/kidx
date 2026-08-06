package com.juul.indexeddb.external

import kotlin.js.JsArray
import kotlin.js.JsString

/** https://developer.mozilla.org/en-US/docs/Web/API/IDBObjectStore */
public external interface IDBObjectStore : IDBQueryable {
    public val name: String

    // kidx modification (see VENDOR.md): the introspection properties the platform already has, needed
    // to verify a declared schema against the database on open.
    public val keyPath: IDBKeyPath?
    public val autoIncrement: Boolean
    public val indexNames: JsArray<JsString>

    public fun add(value: IDBValue): IDBRequest<IDBKey>
    public fun add(value: IDBValue, key: IDBKey): IDBRequest<IDBKey>

    public fun put(item: IDBValue): IDBRequest<IDBKey>
    public fun put(item: IDBValue, key: IDBKey): IDBRequest<IDBKey>

    public fun delete(key: IDBKey): IDBRequest<Nothing?>

    public fun clear(): IDBRequest<Nothing?>

    public fun index(name: String): IDBIndex
    public fun deleteIndex(name: String)
    public fun createIndex(name: String, keyPath: IDBKeyPath): IDBIndex
    public fun createIndex(name: String, keyPath: IDBKeyPath, options: IDBCreateIndexOptions): IDBIndex
}
