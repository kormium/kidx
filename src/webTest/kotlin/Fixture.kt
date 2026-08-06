package io.github.kidx

import kotlin.uuid.Uuid

/**
 * The schema every test declares against. Deliberately the one from SPEC.md's worked example, so the
 * document and the tests describe the same thing.
 */
class User : Row() {
    var id by Users.id
    var name by Users.name
    var email by Users.email
    var createdAt by Users.createdAt
    var note by Users.note
    var verified by Users.verified
}

object Users : Store<User>("users", ::User) {
    val id by Field.UUID().primaryKey()
    val name by Field.Text()
    val email by Field.Text()
    val createdAt by Field.Instant()
    val note by Field.Text().nullable()

    /** Not key-valid: `index(verified)` must not compile. */
    val verified by Field.Boolean()

    val byEmail by index(email, unique = true)
    val byName by index(name)
}

class Order : Row() {
    var id by Orders.id
    var userId by Orders.userId
    var total by Orders.total
    var createdAt by Orders.createdAt
}

object Orders : Store<Order>("orders", ::Order) {
    val id by Field.UUID().primaryKey()
    val userId by Field.UUID()
    val total by Field.Int()
    val createdAt by Field.Instant()

    val byUser by index(userId, createdAt)
}

/** A store whose primary key is composite, for the array-keyPath paths. */
class Membership : Row() {
    var groupId by Memberships.groupId
    var userId by Memberships.userId
    var role by Memberships.role
}

object Memberships : Store<Membership>("memberships", ::Membership) {
    val groupId by Field.UUID().primaryKey()
    val userId by Field.UUID().primaryKey()
    val role by Field.Text()
}

/** A store with a generated key, for the autoIncrement paths. */
class Event : Row() {
    var seq by Events.seq
    var kind by Events.kind
}

object Events : Store<Event>("events", ::Event) {
    val seq by Field.Int().primaryKey(autoIncrement = true)
    val kind by Field.Text()
}

internal val uuidA = Uuid.parse("00000000-0000-4000-8000-000000000001")
internal val uuidB = Uuid.parse("00000000-0000-4000-8000-000000000002")
