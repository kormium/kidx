package io.github.kidx

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private val appSchema = Schema(
    "kidx-test",
    listOf(
        Migration(
            1,
            listOf(
                SchemaStep.CreateStore(Users),
                SchemaStep.AddIndex(Users, Users.byEmail),
                SchemaStep.AddIndex(Users, Users.byName),
                SchemaStep.CreateStore(Orders),
                SchemaStep.AddIndex(Orders, Orders.byUser),
                SchemaStep.CreateStore(Memberships),
                SchemaStep.CreateStore(Events),
            ),
        ),
    ),
)

private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)

private fun user(id: kotlin.uuid.Uuid, name: String, email: String, at: Instant = t0) = User().apply {
    this.id = id
    this.name = name
    this.email = email
    createdAt = at
    verified = false
}

class DatabaseTest {

    /**
     * Opens a fresh database per test. Not `@BeforeTest`: the kotlin-test JS runner awaits the promise a
     * `@Test` returns but not one from a fixture method, so an async setup there silently does not run.
     */
    private fun dbTest(block: suspend (Database) -> Unit) = runTest {
        installFakeIndexedDb()
        deleteDatabase(appSchema.databaseName)
        val db = openDatabase(appSchema)
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun aWrittenRowReadsBackByPrimaryKey() = dbTest { db ->
        db.write(Users) { Users.put(user(uuidA, "Ada", "ada@example.com")) }
        val found = db.read(Users) { Users.get(uuidA) }
        assertEquals("Ada", found?.name)
        assertEquals(t0, found?.createdAt)
    }

    @Test
    fun anAbsentKeyReadsBackAsNull() = dbTest { db ->
        assertNull(db.read(Users) { Users.get(uuidB) })
    }

    @Test
    fun addRefusesADuplicateKeyAndTakesTheWholeTransactionWithIt() = dbTest { db ->
        db.write(Users) { Users.add(user(uuidA, "Ada", "ada@example.com")) }

        // Decision 6: one rejected write discards every other write in the same scope.
        val failure = assertFailsWith<ConstraintViolationException> {
            db.write(Users) {
                Users.put(user(uuidB, "Grace", "grace@example.com"))
                Users.add(user(uuidA, "Ada again", "ada2@example.com"))
            }
        }
        assertTrue("kidx-test" in failure.message!!, failure.message!!)
        assertEquals("ConstraintError", failure.errorName)
        assertNull(db.read(Users) { Users.get(uuidB) })
    }

    @Test
    fun putOverwrites() = dbTest { db ->
        db.write(Users) { Users.put(user(uuidA, "Ada", "ada@example.com")) }
        db.write(Users) { Users.put(user(uuidA, "Ada Lovelace", "ada@example.com")) }
        assertEquals("Ada Lovelace", db.read(Users) { Users.get(uuidA) }?.name)
        assertEquals(1, db.read(Users) { Users.count() })
    }

    @Test
    fun anExceptionInsideAWriteScopeRollsEverythingBack() = dbTest { db ->
        assertFailsWith<IllegalStateException> {
            db.write(Users) {
                Users.put(user(uuidA, "Ada", "ada@example.com"))
                error("changed my mind")
            }
        }
        assertEquals(0, db.read(Users) { Users.count() })
    }

    @Test
    fun deleteRemovesByKey() = dbTest { db ->
        db.write(Users) { Users.put(user(uuidA, "Ada", "ada@example.com")) }
        db.write(Users) { Users.delete(uuidA) }
        assertNull(db.read(Users) { Users.get(uuidA) })
    }

    @Test
    fun aUniqueIndexFindsOneRow() = dbTest { db ->
        db.write(Users) {
            Users.put(user(uuidA, "Ada", "ada@example.com"))
            Users.put(user(uuidB, "Grace", "grace@example.com"))
        }
        val found = db.read(Users) { Users.first(Users.byEmail) { Users.email eq "grace@example.com" } }
        assertEquals("Grace", found?.name)
        assertNull(db.read(Users) { Users.first(Users.byEmail) { Users.email eq "nobody@example.com" } })
    }

    @Test
    fun aRangeOverACompoundIndexIsAPrefixScan() = dbTest { db ->
        db.write(Orders) {
            repeat(5) { i ->
                Orders.add(
                    Order().apply {
                        id = kotlin.uuid.Uuid.parse("00000000-0000-4000-8000-00000000001$i")
                        userId = uuidA
                        total = i
                        createdAt = Instant.fromEpochMilliseconds(t0.toEpochMilliseconds() + i * 1000)
                    },
                )
            }
            Orders.add(
                Order().apply {
                    id = uuidB
                    userId = uuidB
                    total = 99
                    createdAt = t0
                },
            )
        }

        val mine = db.read(Orders) { Orders.find(Orders.byUser) { Orders.userId eq uuidA } }
        assertEquals(5, mine.size)
        assertTrue(mine.all { it.userId == uuidA })
        // The index order is (userId, createdAt), so a prefix scan comes back chronologically.
        assertEquals(listOf(0, 1, 2, 3, 4), mine.map { it.total })

        val later = db.read(Orders) {
            Orders.find(Orders.byUser) {
                Orders.userId eq uuidA
                Orders.createdAt gt Instant.fromEpochMilliseconds(t0.toEpochMilliseconds() + 1500)
            }
        }
        assertEquals(listOf(2, 3, 4), later.map { it.total })
    }

    @Test
    fun descendingAndLimitedReadsWalkTheCursorBackwards() = dbTest { db ->
        db.write(Orders) {
            repeat(4) { i ->
                Orders.add(
                    Order().apply {
                        id = kotlin.uuid.Uuid.parse("00000000-0000-4000-8000-00000000002$i")
                        userId = uuidA
                        total = i
                        createdAt = Instant.fromEpochMilliseconds(t0.toEpochMilliseconds() + i * 1000)
                    },
                )
            }
        }
        val newest = db.read(Orders) {
            Orders.find(Orders.byUser) {
                Orders.userId eq uuidA
                direction = Direction.DESC
                limit = 2
            }
        }
        assertEquals(listOf(3, 2), newest.map { it.total })
    }

    @Test
    fun limitZeroNeverReachesTheEngine() = dbTest { db ->
        db.write(Users) { Users.put(user(uuidA, "Ada", "ada@example.com")) }
        val rows = db.read(Users) { Users.find(Users.byName) { limit = 0 } }
        assertTrue(rows.isEmpty())
    }

    @Test
    fun countOverAnIndexRangeIsTheEnginesOwnCount() = dbTest { db ->
        db.write(Users) {
            Users.put(user(uuidA, "Ada", "ada@example.com"))
            Users.put(user(uuidB, "Grace", "grace@example.com"))
        }
        assertEquals(2, db.read(Users) { Users.count() })
        assertEquals(1, db.read(Users) { Users.count(Users.byName) { Users.name eq "Ada" } })
    }

    @Test
    fun streamingReadsTheWholeRangeWithoutMaterializingIt() = dbTest { db ->
        db.write(Orders) {
            repeat(10) { i ->
                Orders.add(
                    Order().apply {
                        id = kotlin.uuid.Uuid.parse("00000000-0000-4000-8000-00000000003$i")
                        userId = uuidA
                        total = i
                        createdAt = Instant.fromEpochMilliseconds(t0.toEpochMilliseconds() + i * 1000)
                    },
                )
            }
        }
        var sum = 0
        db.read(Orders) {
            Orders.stream(Orders.byUser) { Orders.userId eq uuidA }.collect { sum += it.total }
        }
        assertEquals(45, sum)
    }

    @Test
    fun aGeneratedKeyComesBackAndLandsOnTheRow() = dbTest { db ->
        val first = Event().apply { kind = "opened" }
        val second = Event().apply { kind = "closed" }
        val keys = db.write(Events) { listOf(Events.add(first), Events.add(second)) }

        assertEquals(listOf(1, 2), keys)
        assertEquals(1, first.seq)
        assertEquals(2, second.seq)
        assertEquals("opened", db.read(Events) { Events.get(1) }?.kind)
    }

    @Test
    fun aCompositeKeyIsReadBackAsItsComponents() = dbTest { db ->
        db.write(Memberships) {
            Memberships.put(Membership().apply { groupId = uuidA; userId = uuidB; role = "Owner" })
        }
        val found = db.read(Memberships) { Memberships.get(listOf(uuidA, uuidB)) }
        assertEquals("Owner", found?.role)
    }

    @Test
    fun severalStoresShareOneTransaction() = dbTest { db ->
        db.write(Users, Orders) {
            Users.put(user(uuidA, "Ada", "ada@example.com"))
            Orders.put(
                Order().apply {
                    id = uuidB
                    userId = uuidA
                    total = 7
                    createdAt = t0
                },
            )
        }
        assertEquals(1, db.read(Users) { Users.count() })
        assertEquals(1, db.read(Orders) { Orders.count() })
    }

    @Test
    fun aStoreOutsideTheTransactionIsNamedInTheFailure() = dbTest { db ->
        val failure = assertFailsWith<KidxException> {
            db.read(Users) { Orders.count() }
        }
        assertTrue("orders" in failure.message!!, failure.message!!)
    }

    @Test
    fun operationsAfterCloseFailClearly() = dbTest { db ->
        db.close()
        val failure = assertFailsWith<DatabaseClosedException> { db.read(Users) { Users.count() } }
        assertTrue("kidx-test" in failure.message!!, failure.message!!)
    }

    @Test
    fun aSchemaThatDoesNotMatchTheDatabaseIsRefusedOnOpen() = dbTest { db ->
        // The database on disk was created by `appSchema`; this one declares fewer indexes than the
        // database has, and more importantly the verification compares what the schema *claims*.
        // Same stores, one index short: exactly the shape a migration that forgot an AddIndex leaves.
        val drifted = Schema(
            appSchema.databaseName,
            listOf(
                Migration(
                    1,
                    listOf(
                        SchemaStep.CreateStore(Users),
                        SchemaStep.AddIndex(Users, Users.byName),
                        SchemaStep.CreateStore(Orders),
                        SchemaStep.AddIndex(Orders, Orders.byUser),
                        SchemaStep.CreateStore(Memberships),
                        SchemaStep.CreateStore(Events),
                    ),
                ),
            ),
        )
        val failure = assertFailsWith<SchemaMismatchException> { openDatabase(drifted) }
        assertTrue("byEmail" in failure.message!!, failure.message!!)
    }

    @Test
    fun migrationsReplayOnlyWhatIsNew() = runTest {
        installFakeIndexedDb()
        deleteDatabase(appSchema.databaseName)
        openDatabase(appSchema).close()
        val v2 = Schema(
            appSchema.databaseName,
            appSchema.migrations + Migration(2, listOf(SchemaStep.DropIndex("users", "byName"))),
        )
        val upgraded = openDatabase(v2)
        assertEquals(2, upgraded.version)
        upgraded.close()

        // Reopening at the same version verifies the shape and runs no steps.
        val again = openDatabase(v2)
        assertEquals(2, again.version)
        again.close()
    }
}

class SchemaValidationTest {

    @Test
    fun versionsMustAscend() {
        val failure = assertFailsWith<SchemaException> {
            Schema("x", listOf(Migration(2, emptyList()), Migration(1, emptyList())))
        }
        assertTrue("order" in failure.message!!.lowercase(), failure.message!!)
    }

    @Test
    fun versionsMustBePositive() {
        assertFailsWith<SchemaException> { Schema("x", listOf(Migration(0, emptyList()))) }
    }

    @Test
    fun aSchemaNeedsAtLeastOneMigration() {
        assertFailsWith<SchemaException> { Schema("x", emptyList()) }
    }

    @Test
    fun theVersionIsTheLastMigrationsVersion() {
        val schema = Schema(
            "x",
            listOf(Migration(1, listOf(SchemaStep.CreateStore(Users))), Migration(7, emptyList())),
        )
        assertEquals(7, schema.version)
    }

    @Test
    fun indexingAStoreThatWasNeverCreatedIsRejected() {
        val failure = assertFailsWith<SchemaException> {
            Schema("x", listOf(Migration(1, listOf(SchemaStep.AddIndex(Users, Users.byEmail)))))
        }
        assertTrue("users" in failure.message!!, failure.message!!)
    }

    @Test
    fun creatingTheSameStoreTwiceIsRejected() {
        assertFailsWith<SchemaException> {
            Schema(
                "x",
                listOf(
                    Migration(1, listOf(SchemaStep.CreateStore(Users))),
                    Migration(2, listOf(SchemaStep.CreateStore(Users))),
                ),
            )
        }
    }

    @Test
    fun aDroppedStoreLeavesTheSchema() {
        val schema = Schema(
            "x",
            listOf(
                Migration(1, listOf(SchemaStep.CreateStore(Users), SchemaStep.CreateStore(Orders))),
                Migration(2, listOf(SchemaStep.DropStore("orders"))),
            ),
        )
        assertEquals(listOf("users"), schema.storeNames)
        assertFalse("orders" in schema.storeNames)
    }
}
