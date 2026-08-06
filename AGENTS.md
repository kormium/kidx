# kidx for AI agents

Typed Kotlin storage over IndexedDB. This file is the canonical, copy-ready reference: prefer the forms
shown here over any you might infer. Package is `io.github.kidx`. The design and the reasoning behind
every decision are in [SPEC.md](SPEC.md) — read it before changing behaviour, not before using the API.

## Mental model (read this first)

- A `Store<R>` is a pure schema descriptor; `R : Row` holds one row's values. A `Field` **is** the
  property delegate on the row.
- A `Schema` names one IndexedDB database and lists its `Migration`s. kidx owns DDL: stores and indexes
  exist because a migration says so.
- **Every operation runs inside a scope**: `db.read(stores) { }` or `db.write(stores) { }`. Read and
  write are different types, so writing in a read scope does not compile, and IndexedDB requires the
  stores to be **named up front**.
- **Every operation is one engine call.** Which one is in the table below, and it is worth knowing —
  `find` with `direction = DESC` is a cursor walk while the same call ascending is a single `getAll`.
- No query planner, no boolean predicates, no joins. A query is a key range over one declared index.
- Change notification is kidx's own machinery (IndexedDB has none), and it is built in.

## Declare a schema

```kotlin
import io.github.kidx.*

class User : Row() {
    var id by Users.id
    var email by Users.email
    var createdAt by Users.createdAt
    var note by Users.note              // String? — nullable field, nullable property
}

object Users : Store<User>("users", ::User) {
    val id by Field.UUID().primaryKey()
    val email by Field.Text()
    val createdAt by Field.Instant()
    val note by Field.Text().nullable()

    val byEmail by index(email, unique = true)
    val byCreated by index(createdAt)
}

val schema = Schema("app", listOf(
    Migration(1, listOf(
        SchemaStep.CreateStore(Users),
        SchemaStep.AddIndex(Users, Users.byEmail),
        SchemaStep.AddIndex(Users, Users.byCreated),
    )),
))
```

Field types: `Field.UUID()`, `Field.Text()`, `Field.Int()`, `Field.Double()`, `Field.Instant()`,
`Field.Boolean()`, `Field.Blob()`, `Field.enum<E>()`, and `Field.of(yourType)` for anything else.
Refine with `.nullable()` or `.primaryKey(autoIncrement = false)`.

Only a **key-valid, non-null** field can be indexed or be a primary key, and that is a compile-time
rule, not a runtime check: `index(Users.someBoolean)` and `Field.Boolean().primaryKey()` do not compile,
and neither does `Field.Text().nullable().primaryKey()` — those methods do not exist on those types.

## Open, close, delete

```kotlin
val db = openDatabase(schema)                       // migrates, then verifies the shape on disk
val db = openDatabase(schema) { signal -> ui.warn("Updated elsewhere; reload") }
db.close()
deleteDatabase("app")
```

`openDatabase` throws `DatabaseBlockedException` when another tab holds an older version open (it does
not wait — retry policy is yours), `IndexedDbUnavailableException` where there is no IndexedDB (Firefox
private browsing), and `SchemaMismatchException` when the database's actual shape and the schema
disagree in either direction.

## Read

| Task | Form | Engine call |
|------|------|-------------|
| One row by primary key | `Users.get(id)` | `IDBObjectStore.get` |
| First match of a range | `Users.first(Users.byEmail) { … }` | `IDBIndex.get` |
| Every row | `Users.all()` | `IDBObjectStore.getAll` |
| A range over an index | `Users.find(Users.byCreated) { … }` | `IDBIndex.getAll(range, count)` |
| A range, one row at a time | `Users.stream(Users.byCreated) { … }` | `IDBIndex.openCursor` |
| How many | `Users.count()` / `Users.count(index) { … }` | `IDBObjectStore.count` / `IDBIndex.count` |

```kotlin
val user: User? = db.read(Users) { Users.get(id) }

val recent: List<Order> = db.read(Orders) {
    Orders.find(Orders.byUser) {
        Orders.userId eq userId          // leading index fields: pinned with eq
        Orders.createdAt gt since        // the last one named may be a range
        direction = Direction.DESC       // cursor direction; the order is the index's own
        limit = 50
    }
}

db.read(Orders) {                        // constant memory over a large store
    Orders.stream(Orders.byUser) { Orders.userId eq userId }.collect { export(it) }
}
```

Range operators: `eq`, `gt`, `gtEq`, `lt`, `ltEq`, `between (lo..hi)`. The rule, checked when the query
is built: the fields you name must be a **prefix of the index, in order**, all pinned with `eq` except
the last, and at most one range. Violate it and you get a `QueryException` naming the field it expected
next — on the first call, deterministically.

`Store.describe(index) { … }` returns the key range, direction and limit **without a database** — the
analogue of kormium's `renderSql { }`. Use it in tests and to check a call site.

## Write

| Form | Engine call | Semantics |
|------|-------------|-----------|
| `Users.add(row)` | `IDBObjectStore.add` | fails if the key exists; returns the key |
| `Users.put(row)` | `IDBObjectStore.put` | overwrites; returns the key |
| `Users.delete(key)` | `IDBObjectStore.delete` | — |

```kotlin
db.write(Users, Orders) {
    Users.put(user)
    Orders.add(order)
    Orders.delete(staleId)
}
```

With a generated key (`primaryKey(autoIncrement = true)`), `add` returns the key the engine assigned and
writes it back onto the row.

## Observe

```kotlin
Users.observe(db).collect { users -> render(users) }
Users.observe(db, Users.byCreated) { Users.createdAt gt since }.collect { … }

db.observe(setOf("users", "orders")) { Users.count() to Orders.count() }.collect { … }

db.writeListeners.add { stores -> cache.invalidate(stores) }        // the non-reactive seam
db.connectNotifications(BroadcastChannelTransport("app"))          // other tabs and workers too
```

Emits once immediately, then after every commit touching the store. Emissions are conflated.

## Which form for what

| Task | Use |
|------|-----|
| Row by id | `get(key)` |
| Row by a unique index | `first(index) { col eq v }` |
| Filtered read | `find(index) { … }` |
| Large or unbounded read | `stream(index) { … }` inside the scope |
| Newest N | `find(index) { …; direction = DESC; limit = N }` |
| Pagination | keyset: `find(index) { key gt lastSeen; limit = size }` |
| Targeted change | read + check + `put`, all in one `db.write` |
| Reactive UI | `Store.observe(db)` |
| Assert a query without a database | `describe(index) { … }` |

## Recipes

**Compare-and-set.** There is no optimistic-locking primitive and `put` rewrites whole records, so two
contexts editing different fields of one row clobber each other. A read and a write in one `db.write`
are atomic against other contexts:

```kotlin
val applied = db.write(Users) {
    val current = Users.get(id) ?: return@write false
    if (current.updatedAt != expected) return@write false
    Users.put(current.also { it.note = newNote; it.updatedAt = now })
    true
}
```

**Human-readable ordering.** Index order is UTF-16 binary — `"Z"` before `"a"`, `"ё"` after `"я"` — and
IndexedDB has no collation setting. Store a normalized field and index that:

```kotlin
val name by Field.Text()
val nameSort by Field.Text()          // lowercase + NFC, written by the application
val byName by index(nameSort)
```

**Telling "never had a value" from "stored as null".** A nullable field reads back as `null` either way;
`isSet` tells them apart, and only a record written before the field existed is absent:

```kotlin
if (!user.isSet(Users.note)) { /* this record predates `note` */ }
```

## Gotchas

- Operations are scope member-extensions: outside `db.read { }` / `db.write { }` they do not resolve at
  all. Symptom of being outside one: `get` resolves to something in the stdlib, or `find` is unresolved.
- **Name every store the transaction touches.** `db.read(Users) { Orders.count() }` throws
  `SchemaException` naming `orders`; the compiler cannot catch it.
- **Do not suspend on anything else inside a scope.** No `delay`, no network call, no nested
  `db.write` — an IndexedDB transaction auto-commits as soon as the event loop drains without a pending
  request, so the transaction dies under the block. This is *not* currently enforced by the compiler
  (SPEC.md, decision 5); it is on you.
- A `Flow` from `stream()` must be collected **inside** the scope that produced it.
- **One failure discards the whole transaction.** A rejected `add`, a unique-index violation, an
  exception in the block — everything else written in that scope is gone too. That is the feature; size
  your scopes accordingly. A duplicate key or unique-index violation arrives as
  `ConstraintViolationException`, a full disk as `QuotaExceededException`, anything else the engine
  reports as `EngineException` carrying its `errorName`.
- `put` rewrites the **whole record**, blob included. Keep large binaries in their own store, keyed by
  the owning row's key.
- A `Row` belongs to exactly one `Store` and is **not a DTO**: not serializable, not for `postMessage`.
  Map to your own type at the boundary.
- No `findOne`, no `offset`, no `where { }` — see SPEC.md decision 7 for why each is absent.
- Reading a non-null field that was never assigned throws `RowMappingException`. That is deliberate: it
  names the store, the field and the likely cause instead of handing back a null.
- Not modeled: `or`, `not`, `like`, `inList`, joins, aggregates beyond `count`, grouping, subqueries,
  patch updates, insert-or-ignore, bulk delete, `clear()`. Several are in SPEC.md's Roadmap with the
  reason.

## Working on kidx itself

- `./gradlew compileKotlinJs compileKotlinWasmJs` — both targets must compile. Wasm is stricter: a
  `js(...)` call must be a whole top-level function body, and an interface extending `JsAny` must be
  `external`.
- `./gradlew jsNodeTest` — the suite. `src/webTest` needs no database and runs on both targets;
  `src/jsTest` is engine-level, on `fake-indexeddb` under Node.
- An async `@BeforeTest` **does not run** under the kotlin-test JS runner. Open the database inside the
  test.
- `./gradlew apiDump` after any deliberate public-API change; the `.api` diff is the review artifact.
- The vendored engine lives in `src/webMain/vendor`. Every change to it goes in
  [`VENDOR.md`](src/webMain/vendor/VENDOR.md), and its transaction-scoping discipline is not to be
  touched.
- House comment style: every non-obvious decision carries its "why" inline, next to the code that
  depends on it.
