package io.github.kidx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreDeclarationTest {

    @Test
    fun fieldNameIsThePropertyName() {
        assertEquals("id", Users.id.name)
        assertEquals("createdAt", Users.createdAt.name)
    }

    @Test
    fun fieldsKeepDeclarationOrder() {
        assertEquals(
            listOf("id", "name", "email", "createdAt", "note", "verified"),
            Users.declaredFields.map { it.name },
        )
    }

    @Test
    fun ordinalIsThePositionInDeclarationOrder() {
        Users.declaredFields.forEachIndexed { index, field -> assertEquals(index, field.ordinal) }
    }

    @Test
    fun primaryKeyIsWhatWasDeclared() {
        assertEquals(listOf("id"), Users.primaryKey.map { it.name })
        assertFalse(Users.autoIncrement)
    }

    @Test
    fun primaryKeyCanBeComposite() {
        assertEquals(listOf("groupId", "userId"), Memberships.primaryKey.map { it.name })
    }

    @Test
    fun generatedKeyIsMarked() {
        assertEquals(listOf("seq"), Events.primaryKey.map { it.name })
        assertTrue(Events.autoIncrement)
    }

    @Test
    fun nullabilityIsRecorded() {
        assertFalse(Users.name.nullable)
        assertTrue(Users.note.nullable)
    }

    @Test
    fun indexKnowsItsNameFieldsAndUniqueness() {
        assertEquals("byEmail", Users.byEmail.indexName)
        assertEquals(listOf("email"), Users.byEmail.fields.map { it.name })
        assertTrue(Users.byEmail.unique)
        assertFalse(Users.byName.unique)
    }

    @Test
    fun indexesKeepDeclarationOrder() {
        assertEquals(listOf("byEmail", "byName"), Users.declaredIndexes.map { it.indexName })
        assertEquals(listOf("userId", "createdAt"), Orders.byUser.fields.map { it.name })
    }

    @Test
    fun aStoreWithoutAPrimaryKeyIsRejected() {
        class Broken : Row()
        val store = object : Store<Broken>("broken", ::Broken) {
            @Suppress("unused")
            val name by Field.Text()
        }
        val failure = assertFailsWith<SchemaException> { store.primaryKey }
        assertTrue("broken" in failure.message!!, failure.message!!)
        assertTrue("primaryKey()" in failure.message!!, failure.message!!)
    }

    @Test
    fun anIndexOverAFieldOfAnotherStoreIsRejected() {
        // The `R` type parameter blocks the common case at compile time; two stores over the same Row
        // type are the hole it cannot close, so the runtime check has to exist.
        val other = object : Store<User>("other", ::User) {
            @Suppress("unused")
            val id by Field.UUID().primaryKey()
        }
        val failure = assertFailsWith<SchemaException> { other.index(Users.email) }
        assertTrue("users" in failure.message!!, failure.message!!)
        assertTrue("other" in failure.message!!, failure.message!!)
    }
}
