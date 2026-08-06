package com.juul.indexeddb.external

/** https://developer.mozilla.org/en-US/docs/Web/API/IDBIndex */
internal external interface IDBIndex : IDBQueryable {
    public val name: String
    public val objectStore: IDBObjectStore

    // kidx modification (see VENDOR.md): the introspection properties the platform already has, needed
    // to verify a declared schema against the database on open.
    public val keyPath: IDBKeyPath
    public val unique: Boolean
    public val multiEntry: Boolean
}
