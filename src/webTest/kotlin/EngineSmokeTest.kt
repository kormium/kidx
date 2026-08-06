package io.github.kidx

import com.juul.indexeddb.KeyPath
import com.juul.indexeddb.deleteDatabase
import com.juul.indexeddb.openDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Proves the engine test path works at all before anything is built on it. */
class EngineSmokeTest {

    @Test
    fun theVendoredEngineOpensADatabaseUnderNode() = runTest {
        installIndexedDb()
        deleteDatabase("kidx-smoke")
        val db = openDatabase("kidx-smoke", 1) { database, _, _ ->
            database.createObjectStore("things", KeyPath("id"))
        }
        // `internal` is reachable: the vendored code compiles into this module, which is one of the
        // quieter benefits of vendoring rather than depending.
        assertTrue(db.ensureDatabase().objectStoreNames.toList().contains("things"))
        db.close()
    }
}
