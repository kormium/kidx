package io.github.kidx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A query is an `IDBKeyRange` plus a direction plus a count, and nothing else (decision 7).
 * `describe()` is what makes that assertable without a database — the analogue of kormium's
 * `renderSql { }`.
 */
class QueryTest {

    private val t1 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val t2 = Instant.fromEpochMilliseconds(1_800_000_000_000)

    @Test
    fun anEmptyQueryIsTheWholeIndexAscending() {
        val q = Orders.describe(Orders.byUser) {}
        assertEquals("byUser", q.indexName)
        assertNull(q.lower)
        assertNull(q.upper)
        assertEquals(Direction.ASC, q.direction)
        assertNull(q.limit)
    }

    @Test
    fun allFieldsPinnedIsAnExactKey() {
        val q = Orders.describe(Orders.byUser) {
            Orders.userId eq uuidA
            Orders.createdAt eq t1
        }
        assertEquals(listOf<Any?>(uuidA, t1), q.lower)
        assertEquals(listOf<Any?>(uuidA, t1), q.upper)
        assertFalse(q.lowerOpen)
        assertFalse(q.upperOpen)
        assertFalse(q.upperUnboundedTail)
    }

    @Test
    fun aPinnedPrefixIsAPrefixScan() {
        val q = Orders.describe(Orders.byUser) { Orders.userId eq uuidA }
        assertEquals(listOf<Any?>(uuidA), q.lower)
        assertEquals(listOf<Any?>(uuidA), q.upper)
        // Everything whose first component equals uuidA, whatever follows it.
        assertTrue(q.upperUnboundedTail)
    }

    @Test
    fun aRangeOnTheTrailingFieldKeepsThePinnedPrefix() {
        val q = Orders.describe(Orders.byUser) {
            Orders.userId eq uuidA
            Orders.createdAt gt t1
        }
        assertEquals(listOf<Any?>(uuidA, t1), q.lower)
        assertTrue(q.lowerOpen)
        assertEquals(listOf<Any?>(uuidA), q.upper)
        assertTrue(q.upperUnboundedTail)
    }

    @Test
    fun gtEqIsAClosedLowerBound() {
        val q = Orders.describe(Orders.byUser) {
            Orders.userId eq uuidA
            Orders.createdAt gtEq t1
        }
        assertEquals(listOf<Any?>(uuidA, t1), q.lower)
        assertFalse(q.lowerOpen)
    }

    @Test
    fun ltBoundsTheUpperEndOnly() {
        val q = Orders.describe(Orders.byUser) {
            Orders.userId eq uuidA
            Orders.createdAt lt t2
        }
        assertEquals(listOf<Any?>(uuidA), q.lower)
        assertFalse(q.lowerOpen)
        assertEquals(listOf<Any?>(uuidA, t2), q.upper)
        assertTrue(q.upperOpen)
        assertFalse(q.upperUnboundedTail)
    }

    @Test
    fun betweenIsInclusiveOnBothEnds() {
        val q = Orders.describe(Orders.byUser) {
            Orders.userId eq uuidA
            Orders.createdAt between t1..t2
        }
        assertEquals(listOf<Any?>(uuidA, t1), q.lower)
        assertEquals(listOf<Any?>(uuidA, t2), q.upper)
        assertFalse(q.lowerOpen)
        assertFalse(q.upperOpen)
    }

    @Test
    fun aRangeOnTheLeadingFieldIsAllowedWhenNothingFollowsIt() {
        // Legal against the engine: a range over the index prefix is still one range scan.
        val q = Orders.describe(Orders.byUser) { Orders.userId gt uuidA }
        assertEquals(listOf<Any?>(uuidA), q.lower)
        assertTrue(q.lowerOpen)
        assertNull(q.upper)
    }

    @Test
    fun aSingleFieldIndexUsesScalarKeysNotTuples() {
        val q = Users.describe(Users.byEmail) { Users.email eq "ada@example.com" }
        assertEquals(listOf<Any?>("ada@example.com"), q.lower)
        assertFalse(q.upperUnboundedTail)
    }

    @Test
    fun directionAndLimitAreAssignments() {
        val q = Orders.describe(Orders.byUser) {
            Orders.userId eq uuidA
            direction = Direction.DESC
            limit = 50
        }
        assertEquals(Direction.DESC, q.direction)
        assertEquals(50, q.limit)
    }

    @Test
    fun aFieldOutsideTheIndexIsRejected() {
        val failure = assertFailsWith<QueryException> {
            Orders.describe(Orders.byUser) { Orders.total eq 10 }
        }
        assertTrue("total" in failure.message!!, failure.message!!)
        assertTrue("byUser" in failure.message!!, failure.message!!)
    }

    @Test
    fun skippingTheLeadingFieldIsRejected() {
        val failure = assertFailsWith<QueryException> {
            Orders.describe(Orders.byUser) { Orders.createdAt gt t1 }
        }
        assertTrue("userId" in failure.message!!, failure.message!!)
        assertTrue("prefix" in failure.message!!.lowercase(), failure.message!!)
    }

    @Test
    fun constrainingAFieldAfterARangeIsRejected() {
        val failure = assertFailsWith<QueryException> {
            Orders.describe(Orders.byUser) {
                Orders.userId gt uuidA
                Orders.createdAt eq t1
            }
        }
        assertTrue("userId" in failure.message!!, failure.message!!)
        assertTrue("range" in failure.message!!.lowercase(), failure.message!!)
    }

    @Test
    fun twoRangesAreRejected() {
        assertFailsWith<QueryException> {
            Orders.describe(Orders.byUser) {
                Orders.userId eq uuidA
                Orders.createdAt gt t1
                Orders.createdAt lt t2
            }
        }
    }

    @Test
    fun constrainingTheSameFieldTwiceIsRejected() {
        assertFailsWith<QueryException> {
            Orders.describe(Orders.byUser) {
                Orders.userId eq uuidA
                Orders.userId eq uuidB
            }
        }
    }

    @Test
    fun anInvertedRangeIsRejectedBeforeTheEngineSeesIt() {
        // IndexedDB answers an inverted IDBKeyRange with DataError; naming both bounds is better.
        val failure = assertFailsWith<QueryException> {
            Orders.describe(Orders.byUser) {
                Orders.userId eq uuidA
                Orders.createdAt between t2..t1
            }
        }
        assertTrue("createdAt" in failure.message!!, failure.message!!)
    }

    @Test
    fun aNegativeLimitIsRejected() {
        assertFailsWith<QueryException> {
            Orders.describe(Orders.byUser) { limit = -1 }
        }
    }

    @Test
    fun anIndexOfAnotherStoreIsRejected() {
        val failure = assertFailsWith<QueryException> { Orders.describe(untypedUsersIndex()) {} }
        assertTrue("users" in failure.message!!, failure.message!!)
    }

    @Suppress("UNCHECKED_CAST")
    private fun untypedUsersIndex(): Index<Order> = Users.byEmail as Index<Order>

    @Test
    fun descriptionReadsBackAsText() {
        val q = Orders.describe(Orders.byUser) {
            Orders.userId eq uuidA
            Orders.createdAt gt t1
            direction = Direction.DESC
            limit = 20
        }
        val text = q.toString()
        assertTrue("byUser" in text, text)
        assertTrue("DESC" in text, text)
        assertTrue("20" in text, text)
    }
}
