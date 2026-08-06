package io.github.kidx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The Row <-> stored-record boundary: decision 9. */
class RecordCodecTest {

    private val createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun user(note: String? = null, withNote: Boolean = true) = User().apply {
        id = uuidA
        name = "Ada"
        email = "ada@example.com"
        this.createdAt = this@RecordCodecTest.createdAt
        verified = true
        if (withNote) this.note = note
    }

    @Test
    fun aRowRoundTripsThroughARecord() {
        val decoded = Users.decode(Users.encode(user(note = "vip")))
        assertEquals(uuidA, decoded.id)
        assertEquals("Ada", decoded.name)
        assertEquals("ada@example.com", decoded.email)
        assertEquals(createdAt, decoded.createdAt)
        assertEquals("vip", decoded.note)
        assertTrue(decoded.verified)
    }

    @Test
    fun aNullableFieldWithNoValueIsStoredAsAnExplicitNull() {
        // So that "absent" on the read path means exactly one thing: the record predates the field.
        val record = Users.encode(user(withNote = false))
        assertTrue(recordHas(record, "note"))
        assertNull(recordGet(record, "note"))

        val decoded = Users.decode(record)
        assertTrue(decoded.isSet(Users.note))
        assertNull(decoded.note)
    }

    @Test
    fun aFieldMissingFromTheRecordDecodesAsAbsent() {
        val record = Users.encode(user())
        recordDelete(record, "note")

        val decoded = Users.decode(record)
        assertFalse(decoded.isSet(Users.note))
        assertNull(decoded.note)
    }

    @Test
    fun aNonNullFieldMissingFromTheRecordFailsWithTheLikelyCause() {
        val record = Users.encode(user())
        recordDelete(record, "email")

        val failure = assertFailsWith<RowMappingException> { Users.decode(record) }
        assertTrue("users" in failure.message!!, failure.message!!)
        assertTrue("email" in failure.message!!, failure.message!!)
        assertTrue("Text" in failure.message!!, failure.message!!)
    }

    @Test
    fun aNonNullFieldStoredAsNullFailsDifferently() {
        val record = Users.encode(user())
        recordSet(record, "email", null)

        val failure = assertFailsWith<RowMappingException> { Users.decode(record) }
        assertTrue("email" in failure.message!!, failure.message!!)
        assertTrue("null" in failure.message!!, failure.message!!)
    }

    @Test
    fun aStoredValueOfTheWrongTypeFails() {
        val record = Users.encode(user())
        recordSet(record, "name", 42.toJsNumber())

        val failure = assertFailsWith<RowMappingException> { Users.decode(record) }
        assertTrue("name" in failure.message!!, failure.message!!)
        assertTrue("Text" in failure.message!!, failure.message!!)
    }

    @Test
    fun undeclaredPropertiesAreDroppedOnRewrite() {
        val record = Users.encode(user())
        recordSet(record, "somethingElse", "kept by another writer".toJsString())

        val rewritten = Users.encode(Users.decode(record))
        assertFalse(recordHas(rewritten, "somethingElse"))
    }

    @Test
    fun encodingARowWithAnAbsentNonNullFieldIsRejectedBeforeItReachesTheEngine() {
        val incomplete = User().apply { id = uuidA; name = "Ada" }
        val failure = assertFailsWith<RowMappingException> { Users.encode(incomplete) }
        assertTrue("email" in failure.message!!, failure.message!!)
    }

    @Test
    fun aGeneratedKeyMayBeAbsentOnTheWayIn() {
        // The one legitimate absent value on the write path: decision 12.
        val event = Event().apply { kind = "opened" }
        val record = Events.encode(event)
        assertFalse(recordHas(record, "seq"))
        assertTrue(recordHas(record, "kind"))
    }

    @Test
    fun aCompositeKeyIsStoredAsItsOwnFields() {
        val membership = Membership().apply { groupId = uuidA; userId = uuidB; role = "Owner" }
        val record = Memberships.encode(membership)
        assertEquals(uuidA.toString(), TextFieldType.decode(recordGet(record, "groupId")!!))
        assertEquals(uuidB.toString(), TextFieldType.decode(recordGet(record, "userId")!!))
    }
}
