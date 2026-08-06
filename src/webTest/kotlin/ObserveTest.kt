package io.github.kidx

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Change notification: decision 15. */
class ObserveTest {

    private val schema = Schema(
        "kidx-observe-test",
        listOf(
            Migration(
                1,
                listOf(
                    SchemaStep.CreateStore(Users),
                    SchemaStep.AddIndex(Users, Users.byEmail),
                    SchemaStep.AddIndex(Users, Users.byName),
                    SchemaStep.CreateStore(Orders),
                    SchemaStep.AddIndex(Orders, Orders.byUser),
                ),
            ),
        ),
    )

    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun user(id: kotlin.uuid.Uuid, name: String) = User().apply {
        this.id = id
        this.name = name
        email = "$name@example.com"
        createdAt = t0
        verified = false
    }

    /** See DatabaseTest.dbTest: an async `@BeforeTest` is not awaited by the JS test runner. */
    private fun dbTest(block: suspend kotlinx.coroutines.test.TestScope.(Database) -> Unit) = runTest {
        installIndexedDb()
        deleteDatabase(schema.databaseName)
        val db = openDatabase(schema)
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun aListenerSeesTheStoresACommitWrote() = dbTest { db ->
        val seen = mutableListOf<Set<String>>()
        val registration = db.writeListeners.add { seen += it }

        db.write(Users) { Users.put(user(uuidA, "Ada")) }
        assertEquals(listOf(setOf("users")), seen)

        db.write(Users, Orders) { Users.put(user(uuidB, "Grace")) }
        // Over-collection is safe and deliberate: the declared stores are the dirty set.
        assertEquals(setOf("users", "orders"), seen[1])

        registration.remove()
        db.write(Users) { Users.delete(uuidA) }
        assertEquals(2, seen.size)
    }

    @Test
    fun anAbortedTransactionFiresNothing() = dbTest { db ->
        val seen = mutableListOf<Set<String>>()
        db.writeListeners.add { seen += it }

        runCatching {
            db.write(Users) {
                Users.put(user(uuidA, "Ada"))
                error("no")
            }
        }
        assertTrue(seen.isEmpty(), "a rolled-back transaction must not notify: $seen")
    }

    @Test
    fun aReadOnlyScopeFiresNothing() = dbTest { db ->
        val seen = mutableListOf<Set<String>>()
        db.writeListeners.add { seen += it }
        db.read(Users) { Users.count() }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun observeEmitsTheCurrentValueImmediately() = dbTest { db ->
        db.write(Users) { Users.put(user(uuidA, "Ada")) }
        val rows = Users.observe(db).first()
        assertEquals(listOf("Ada"), rows.map { it.name })
    }

    @Test
    fun observeReEmitsAfterACommit() = dbTest { db ->
        db.write(Users) { Users.put(user(uuidA, "Ada")) }

        // Awaiting each emission rather than yielding: the first read goes through several suspensions
        // in the driver, so a bare yield() can let the write land before it and collapse the two.
        val emissions = Channel<List<String>>(Channel.UNLIMITED)
        val job = launch {
            Users.observe(db).take(2).collect { emissions.send(it.map { row -> row.name }) }
        }
        assertEquals(listOf("Ada"), emissions.receive())

        db.write(Users) { Users.put(user(uuidB, "Grace")) }
        assertEquals(setOf("Ada", "Grace"), emissions.receive().toSet())
        job.join()
    }

    @Test
    fun observeIgnoresCommitsToOtherStores() = dbTest { db ->
        val emissions = Channel<Int>(Channel.UNLIMITED)
        val job = launch { Users.observe(db).take(1).collect { emissions.send(it.size) } }
        assertEquals(0, emissions.receive())
        db.write(Orders) { }
        job.join()
    }

    @Test
    fun observeAcceptsAnIndexedQuery() = dbTest { db ->
        db.write(Users) {
            Users.put(user(uuidA, "Ada"))
            Users.put(user(uuidB, "Grace"))
        }
        val rows = Users.observe(db, Users.byName) { Users.name eq "Grace" }.first()
        assertEquals(listOf("Grace"), rows.map { it.name })
    }

    @Test
    fun theGenericFormSpansSeveralStores() = dbTest { db ->
        val counts = db.observe(setOf("users", "orders")) { Users.count() to Orders.count() }.first()
        assertEquals(0L to 0L, counts)
    }

    @Test
    fun aRemoteSignalRefreshesWithoutALocalWrite() = dbTest { db ->
        val transport = RecordingTransport()
        val registration = db.connectNotifications(transport)

        val emissions = Channel<Int>(Channel.UNLIMITED)
        val job = launch { Users.observe(db).take(2).collect { emissions.send(it.size) } }
        assertEquals(0, emissions.receive())

        db.write(Users) { Users.put(user(uuidA, "Ada")) }
        assertEquals(1, emissions.receive())
        job.join()

        // A local commit is published outward exactly once, and inbound signals are not echoed.
        assertEquals(listOf(setOf("users")), transport.published)
        transport.deliver(setOf("users"))
        assertEquals(listOf(setOf("users")), transport.published)
        registration.remove()
    }

    @Test
    fun anUnknownStoreNameInASignalIsIgnored() = dbTest { db ->
        val transport = RecordingTransport()
        db.connectNotifications(transport)
        transport.deliver(setOf("a-store-from-another-code-version"))
        // Nothing to assert but the absence of a failure: a newer tab must not break an older one.
        assertEquals(0L, db.read(Users) { Users.count() })
    }

    @Test
    fun observingAClosedDatabaseFails() = dbTest { db ->
        val flow = Users.observe(db)
        db.close()
        val failure = runCatching { flow.toList() }.exceptionOrNull()
        assertTrue(failure is DatabaseClosedException, "expected a typed failure, got $failure")
    }
}
