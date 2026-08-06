package io.github.kidx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private enum class Role { Owner, Member }

class FieldTypeTest {

    private fun <T> roundTrip(type: FieldType<T>, value: T): T = type.decode(type.encode(value))

    @Test
    fun textRoundTrips() {
        assertEquals("Ada", roundTrip(TextFieldType, "Ada"))
        assertEquals("", roundTrip(TextFieldType, ""))
    }

    @Test
    fun intRoundTrips() {
        assertEquals(0, roundTrip(IntFieldType, 0))
        assertEquals(-42, roundTrip(IntFieldType, -42))
        assertEquals(Int.MAX_VALUE, roundTrip(IntFieldType, Int.MAX_VALUE))
    }

    @Test
    fun doubleRoundTrips() {
        assertEquals(1.5, roundTrip(DoubleFieldType, 1.5))
    }

    @Test
    fun booleanRoundTrips() {
        assertEquals(true, roundTrip(BooleanFieldType, true))
        assertEquals(false, roundTrip(BooleanFieldType, false))
    }

    @Test
    fun uuidRoundTripsAsItsCanonicalString() {
        assertEquals(uuidA, roundTrip(UuidFieldType, uuidA))
        // What is on disk is what is in the logs: decision 10.
        assertEquals(uuidA.toString(), TextFieldType.decode(UuidFieldType.encode(uuidA)))
    }

    @Test
    fun instantRoundTripsAtMillisecondPrecision() {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_123)
        assertEquals(now, roundTrip(InstantFieldType, now))
    }

    @Test
    fun instantTruncatesBelowMilliseconds() {
        // A stated property of the type, not a silent surprise: decision 10.
        val precise = Instant.fromEpochSeconds(1_700_000_000, nanosecondAdjustment = 123_456_789)
        assertEquals(Instant.fromEpochMilliseconds(1_700_000_000_123), roundTrip(InstantFieldType, precise))
    }

    @Test
    fun convertDerivesANewType() {
        val role = TextFieldType.convert<Role, String>(
            toStored = { it.name },
            fromStored = { Role.valueOf(it) },
        )
        assertEquals(Role.Member, role.decode(role.encode(Role.Member)))
        assertEquals("Member", TextFieldType.decode(role.encode(Role.Member)))
    }

    @Test
    fun convertOverAKeyTypeStaysAKeyType() {
        val role: KeyFieldType<Role> = TextFieldType.convert(
            toStored = { it.name },
            fromStored = { Role.valueOf(it) },
        )
        assertEquals(Role.Owner, role.decode(role.encode(Role.Owner)))
    }

    @Test
    fun descriptionsAreReadable() {
        assertEquals("Text", TextFieldType.description)
        assertEquals("Instant", InstantFieldType.description)
        assertTrue("Text" in TextFieldType.convert<Role, String>({ it.name }, { Role.valueOf(it) }).description)
    }
}
