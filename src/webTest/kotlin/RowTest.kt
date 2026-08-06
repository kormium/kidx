package io.github.kidx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class RowTest {

    @Test
    fun assignedFieldsReadBack() {
        val user = User().apply {
            id = uuidA
            name = "Ada"
            email = "ada@example.com"
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
            verified = true
        }
        assertEquals(uuidA, user.id)
        assertEquals("Ada", user.name)
        assertEquals(Instant.fromEpochMilliseconds(1_700_000_000_000), user.createdAt)
        assertTrue(user.verified)
    }

    @Test
    fun readingAnUnassignedNonNullFieldFailsAndSaysWhich() {
        val failure = assertFailsWith<RowMappingException> { User().name }
        assertTrue("users" in failure.message!!, failure.message!!)
        assertTrue("name" in failure.message!!, failure.message!!)
    }

    @Test
    fun anUnassignedNullableFieldReadsAsNull() {
        assertNull(User().note)
    }

    @Test
    fun explicitNullIsDistinguishableFromAbsent() {
        val user = User()
        assertFalse(user.isSet(Users.note))

        user.note = null
        assertTrue(user.isSet(Users.note))
        assertNull(user.note)

        user.note = "vip"
        assertTrue(user.isSet(Users.note))
        assertEquals("vip", user.note)
    }

    @Test
    fun aRowBelongsToExactlyOneStore() {
        // Two stores over the same Row type: the compiler allows it, so the first assignment has to
        // pin the owner and the second has to fail loudly rather than spilling into a side map.
        val other = object : Store<User>("other", ::User) {
            val email by Field.Text()
        }
        val user = User().apply { name = "Ada" }
        val failure = assertFailsWith<RowMappingException> { user[other.email] = "x" }
        assertTrue("users" in failure.message!!, failure.message!!)
        assertTrue("other" in failure.message!!, failure.message!!)
    }

    @Test
    fun aFreshRowHasNoOwnerYet() {
        assertFalse(User().isSet(Users.name))
    }
}
