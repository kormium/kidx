# kidx — spec

Status: **the build skeleton exists and the vendored engine compiles for js and wasmJs; none of kidx's
own code is written.** This document is the full record of a design conversation; it exists so a next
agent (human or Claude) can pick up the work without re-deriving any of it. Read it end to end before
writing code — several approaches below were considered and explicitly rejected, and re-proposing them
wastes a round trip.

What exists on disk: a single-project Gradle build (the root *is* the library), the wrapper, the
binary-compatibility validator, and the vendored JuulLabs sources under `src/webMain/vendor/` with
their provenance and the changes they still need recorded in `VENDOR.md`. Reading that code answered
three open questions and contradicted one decision; both are folded into the text below.

## What this is

A typed, Kotlin-native storage layer over IndexedDB: schema declaration, typed rows, indexed
queries and migrations. An independent, publishable library (its own Maven coordinates, the same
relationship [kormium](../kormium) and [kromus](../kromus) have to each other), not an internal
module of any one application.

Its declaration syntax deliberately imitates Kormium's `Table`/`Column`/`Entity` DSL — same author,
same taste, and in several places the same machinery (see decision 3) — but it is **not** built on
Kormium's SQL execution. It is a new typed layer over a vetted low-level IndexedDB engine.

Targets Kotlin/JS and Kotlin/Wasm (the vendored engine core supports both; see decision 4).
Package: `io.github.kidx`.

## Why this exists

Two reasons to want a Kotlin-native IndexedDB layer instead of SQLite-compiled-to-WASM:

- **No `wasm-unsafe-eval`.** A WASM SQLite module needs that CSP directive. Dropping WASM removes it
  from the page's security posture entirely — which matters wherever the CSP is reviewed or locked
  down (browser extensions, embedded contexts, strict-CSP sites).
- **Bundle size.** WASM SQLite builds run 1-2MB; a plain Kotlin/JS IndexedDB layer is nowhere near
  that.

And one reason to want a *typed* layer rather than a JS library through interop: in a Kotlin codebase
every call across an `external`/`dynamic` boundary is a permanent ongoing cost, not a one-time one.

## The three principles, in priority order

**1. Take the maximum IndexedDB actually offers, and not one gram more.** IndexedDB is a
single-index range-scan engine with no query planner. It is not SQL, and pretending otherwise
(porting a general SQL-shaped query DSL onto it) either leaks or hides full scans behind a
friendly-looking `where{}`.

**2. Explicit beats implicit.** Where a limitation of the engine can be expressed in the type system,
it is — the compiler refuses rather than the library silently working around it. Where it cannot, it
fails loudly at the call site. Nothing is quietly substituted on the way to storage.

**3. Nothing pretends to be an engine capability.** Every operation on the data-access surface — read,
write, query, migrate — is one engine call, or a cursor, which is the engine's other read primitive.
kidx will not offer an operation that *looks* like one engine call and is secretly several: that is how
a cost becomes invisible. `update(patch)` looks like one write and is a read plus a write;
`insertOrIgnore` looks like one operation and cannot work at all (decision 6). Both are out, and the
Roadmap says why.

What this principle does **not** forbid is a layer that is openly a kidx layer. Change notification
(decision 15) is entirely kidx's own machinery — IndexedDB has no change events whatsoever — but it
does not misrepresent anything, because nobody mistakes it for a database feature. It is named as a
layer, its costs are documented, and it sits above the engine rather than inside the illusion of it.
That distinction, not "emulation" as a word, is the line.

This principle is the newest and cut several things designed in earlier rounds of this conversation.
The Roadmap records each one with what it costs, so nothing is lost — only postponed.

## Architecture at a glance

```
┌────────────────────────────────────────────────────────────────────┐
│  kidx  —  one project, one `webMain` source set, js + wasmJs          │
│                                                                      │
│  src/webMain/kotlin — the DSL                                        │
│    Row, Field/FieldType, Store, Index, Schema/Migration, Query,      │
│    ReadScope/WriteScope                                              │
│    WriteListeners, NotificationTransport, observe  (decision 15)     │
│                                                                      │
│  src/webMain/vendor — vendored JuulLabs/indexeddb (core + external)  │
│    transaction lifetime discipline; see VENDOR.md                     │
└────────────────────────────────────────────────────────────────────┘
```

**One project, and not even a `kidx-core` inside it.** An earlier draft split a platform-free
`kidx-core` from a `kidx-indexeddb` engine. That split had exactly one justification — keeping the DSL
free of platform types — and it does not survive contact with the code: `FieldType` converts values to
and from `kotlin.js.JsAny`, so every source set here is a web source set regardless. With the
justification gone and exactly one engine to hide, a second module would be structure for its own sake;
and once there is only one module, naming it `-core` promises a sibling that does not exist. So the
root project is the library, published as `io.github.kidx:kidx`.

Change notification is **built in**, not a `kidx-observe` module. Kormium splits its observe module off
for one reason — keeping coroutines and `Flow` out of `kormium-core`; the seam itself (`WriteListener`,
`WriteListeners`, `NotificationTransport`) lives in core there too. That reason does not exist here:
every operation is `suspend` and `Flow` is already in the public API through `stream()`, so there is
nothing to keep out. And the seam has to be inside regardless, because only `WriteScope` can know which
stores a transaction wrote — an "optional" module would have forced a public hook into the library
anyway. See decision 15.

So there is currently nothing that wants to be a second module. If something ever does, this project's
sources move into a subproject with `artifactId = "kidx"` pinned so the coordinate does not change — a
mechanical move, and a cheaper price than carrying an empty multi-module scaffold until then.

The vendored code lives in its own source directory rather than its own module, in the upstream module
layout (`vendor/core`, `vendor/external`), so re-checking it against a fresh clone is a `diff -r`. It
keeps its `com.juul.indexeddb` package names — which makes both the diffing and the attribution
obvious in every file.

Full-text search is not part of kidx: it is an in-memory concern, solved independently by
[kromus](../kromus)'s `TextIndex` (BM25). The join between them is `Store.observe()` (decision 15),
which is the `Flow` an incremental index-sync consumes — the same role `Table.observe(db){}` plays for
`kromus-sync` in the SQL world.

## Decisions made, and why (read this before changing any of it)

### 1. No `Catalog` phantom type

Kormium parameterizes `Table<G: Catalog, T: Entity>` so tables belonging to different logical
databases cannot be mixed up inside one process, and `Database<out G>` is covariant so backend
factories stay catalog-agnostic while user code pins the catalog by assignment.

kidx does not carry it. A `Schema` names exactly one IndexedDB database, and `Store<R>` takes no
catalog parameter. If an application opens two kidx databases, passing a store of one into a scope of
the other is not caught by the compiler — it fails at runtime, because IndexedDB rejects a
transaction naming a store the database does not have. That failure is immediate and deterministic,
not data-dependent.

A deliberate simplification, and the one Kormium concept kidx drops on purpose. Reintroducing it is a
breaking change to every signature, so if multi-database support is ever wanted, decide it before the
first stable release.

### 2. No `init { col1; col2; ... }` boilerplate

Some Kormium schema code force-references every declared column inside `init{}`, on the assumption
that an unreferenced property inside an otherwise-used `object` could be stripped by dead-code
elimination in a production Kotlin/JS build.

**This was tested against a real minified production Kotlin/JS build, not assumed.** A throwaway
column was added to a live table, once referenced in `init{}` and once not, and the production
browser distribution was built both times. In **both** cases the column's live registration code
(not just its name as a string) survived in the minified bundle — confirmed by inspecting the
surrounding compiled code, not by grepping for the identifier. Kotlin's object initialization runs
all of an object's property initializers together; this toolchain does not strip individual unused
properties out of an otherwise-live object.

The more drastic scenario — a store **never referenced by name anywhere outside its own
declaration**, so the whole `object` is never touched — was not reproduced. In kidx it is
structurally impossible anyway: a store only exists in the database because a `Migration` lists
`SchemaStep.CreateStore(store)`, so the migration list *is* the force-reference for every store.

Conclusion: **do not carry the `init{}` pattern into kidx.** It also matches Kormium's own canonical
documented example, which does not use it either.

### 3. Reuse Kormium's `Entity` slot model, for reading; do not reuse `Column` / `ColumnType`

**Do not reuse `Column` / `ColumnType`.** Both are SQL-executor-coupled down to the wire level:
`Column` implements `Expression`/`Operand` and renders `toSql(builder: ParamBuilder)` — it is a
query-AST node, not schema metadata; `ColumnType<T>.read(rs: ResultSet, index: Int)` reads from a
JDBC-shaped result set by column index and `toParam` binds SQL parameters. Reusing them would mean
faking a `ResultSet` over IndexedDB to satisfy an interface shape that does not fit — exactly the
force-fitting this project exists to avoid.

**Do reuse the `Entity` slot model.** Kormium's `Entity` stores values in an `Array<Any?>` indexed by
`Column.ordinal` (position in declaration order), with a private `ABSENT` sentinel distinguishing
"never assigned" from "explicitly null", and the column itself *is* the property delegate on the
entity (`var name by Users.name`). No reflection, no serialization, no per-row allocation beyond the
value array.

Why the sentinel is worth keeping here, precisely:

- **On the read path it is the point.** IndexedDB has no `ALTER TABLE`. When a migration adds a
  field, records already on disk simply do not have that property — and JavaScript hands us the
  distinction natively: a missing property reads back `undefined`, an explicit null reads back
  `null`. Kormium's hydrate-time diagnostic (a non-null field came back absent → name the store, the
  field, the expected type, and what to do) turns the single most likely IndexedDB schema bug into a
  loud, actionable error instead of an `undefined` surfacing much later. `isSet(field)` exposes the
  same distinction to application code.
- **On the write path it is nearly vestigial, and that is worth saying out loud.** In Kormium an
  absent field is *omitted* from the `INSERT` so the database can apply its default. IndexedDB has no
  defaults: omitting a property means storing a record without it, i.e. manufacturing by hand the
  exact problem the read path exists to catch. So `add`/`put` with an absent non-null field is a loud
  error, and the only legitimate absent value on write is an unfilled `autoIncrement` primary key
  (decision 12).

Two things Kormium has that kidx does not:

- **No `overflow` map.** A `Row` belongs to exactly one `Store` — the first assignment fixes the
  owner, and touching a field of another store throws, naming both. A second, invisible access path
  into row storage is not worth a rare convenience.
- **No `unset()`.** Its only use is assembling patch entities, and patch updates are not in v1.

A `Row` is not a DTO, exactly as Kormium says of `Entity`: its slot storage is an implementation
detail, it is not serializable, and it must not be sent through `postMessage` or a `structuredClone`
to another context. Map it to your own type at the boundary.

And one inherited implementation whose *justification* is different here, so nobody re-derives the
wrong one: Kormium's value array is indexed by ordinal because SELECT results are positional, which
saves a name→index map per row (their comment measures ~47ns vs ~0.5ns). IndexedDB records are JS
objects accessed by name, so that reason does not apply at all. The array is still right — as compact
slot storage able to hold the `ABSENT` sentinel, versus a `Map<String, Any?>` per row — but that is
the reason to write down.

Field type *vocabulary* stays Kormium's (`UUID`, `Text`, `Int`, `Instant`, `Boolean`, `Bytes`, …), as
a fresh implementation with zero dependency on kormium-core, and `convert` — Kormium's extension
point for custom types — is copied outright.

### 4. Engine: vendor JuulLabs/indexeddb's low-level primitives

[`JuulLabs/indexeddb`](https://github.com/JuulLabs/indexeddb) (Apache 2.0, Kotlin/JS + Kotlin/Wasm,
actively maintained) already solves the one genuinely hard, risky part of this project: **IndexedDB
transaction lifetime**. An `IDBTransaction` auto-commits when the JS event loop returns without a
pending `IDBRequest`; suspend across the wrong kind of await and the transaction closes under you.
JuulLabs solves this with `Dispatchers.Unconfined` plus a hard rule enforced by only exposing
transactional operations as extension functions scoped to `Transaction`/`WriteTransaction`
receivers: *"you must not call any suspend function except those provided by this library and scoped
on Transaction."* The API shape makes it close to impossible to violate by accident.

Primitives to build on (real signatures, from `core/src/webMain/kotlin/`):

```kotlin
suspend fun openDatabase(
    name: String, version: Int,
    initialize: suspend VersionChangeTransaction.(database: Database, oldVersion: Int, newVersion: Int) -> Unit,
): Database

class Database {
    suspend fun <T> transaction(vararg store: String, action: suspend Transaction.() -> T): T
    suspend fun <T> writeTransaction(vararg store: String, action: suspend WriteTransaction.() -> T): T
}

// on Transaction: ObjectStore.get/getAll/openCursor(Flow)/count, objectStore(name), ObjectStore.index(name)
// on WriteTransaction (extends Transaction): ObjectStore.add/put/delete/clear
// on VersionChangeTransaction (extends WriteTransaction):
//   Database.createObjectStore/deleteObjectStore, ObjectStore.createIndex/deleteIndex
```

Values are raw `IDBValue`/`JsAny` — the library is explicit that passing Kotlin objects straight
through is "probably wrong". **That gap is what kidx fills**: JuulLabs gives safe transactions and
raw storage, kidx gives typed schema, typed rows, typed queries and migrations on top.

**Decision: vendor the `core` module into kidx** rather than depend on it, for full control over the
code kidx's own guarantees rest on and zero third-party surface for consumers. The honest cost,
recorded so nobody rediscovers it: upstream fixes — including fixes to the transaction-lifetime
discipline, the riskiest part — must be ported by hand, and Apache-2.0 attribution obligations (a
`NOTICE` file, retained license headers, a clear statement of what was modified) are now kidx's to
carry. **Do not modify its transaction-scoping discipline**; that is the part worth not touching.

### 5. Two scope types, and the suspend discipline inside them

Kormium's first rule is that all queries run inside a scope: `db.transaction { }` /
`db.autocommit { }`, with table operations existing **only** as member-extensions on `Scope<G>` — a
`Table`'s own runners are `internal`. Outside a scope, `find` does not resolve.

kidx keeps that exactly, because it is also what IndexedDB needs: JuulLabs' safety comes from scoping
operations to a transaction receiver, and Kormium's scope-extension pattern lines up with it
one-to-one. Two differences, both forced by the engine:

- The distinction is **readonly vs readwrite**, not "no BEGIN vs BEGIN" — every IndexedDB operation
  is inside a transaction, always. So the two entry points are `db.read { }` (→ `ReadScope`) and
  `db.write { }` (→ `WriteScope : ReadScope`). Two distinct types, so writing inside a read scope
  does not compile. `autocommit` was rejected as a name: in IndexedDB it would mean something other
  than what it means in SQL.
- IndexedDB requires the stores a transaction touches to be **declared up front**:
  `db.write(Users, Orders) { … }`. Kormium has no equivalent. Forgetting one is a runtime error from
  inside the block, so this is a gotcha to document, not a compile-time guarantee.

Everything is suspend-only; there is no blocking mirror (this is the browser). Several reads inside
one scope see one consistent snapshot.

**The discipline has to be re-established, not just inherited.** Decision 4 credits JuulLabs with
making the transaction-lifetime rule hard to violate — but that protection comes from *their*
receiver type, and kidx's scope is its own class. Nothing in the sketch stops a user writing
`delay(100)` or an HTTP call inside `db.write { }`, after which the transaction silently commits or
closes and every later operation in the block fails. Describing the hazard is not inheriting the
protection.

Kotlin has exactly one tool for this: **`@RestrictsSuspension`** on the scope classes. Inside a
suspend lambda whose receiver is annotated, only suspend functions that are members or extensions of
that receiver may be called — which is precisely the JuulLabs rule, checked by the compiler instead
of trusted to a doc comment. It also makes a nested `db.write { }` inside another scope a compile
error, which is the failure mode described in decision 6.

Its cost, which must be weighed before committing: the restriction also forbids `Flow.collect`, which
`stream()` needs. The way out is for kidx to declare its own `collect` as a scope extension, so the
one legitimate suspending consumer is inside the allowed set.

**Verified against the vendored code:** upstream does not use the annotation anywhere. The rule lives
in a doc comment on `openDatabase` and on `Transaction.openCursor`, and nothing enforces it — the
cursor implementation only detects the symptom after the fact, closing the flow with
`IllegalStateException("Send failed. Did you suspend illegally?")` when a `trySend` fails because the
collector suspended. So this is a place where kidx can be genuinely stricter than upstream rather than
merely as strict. Upstream's cursor contract also requires the collector not to suspend *at all* under
`autoContinue = true`, which is why kidx's scope-scoped `collect` takes a non-suspending action.

### 6. Failure: the whole transaction, no savepoints, no nesting

All of this is observable behaviour, so it is specified rather than left to the implementation. The
first two points are **already exactly what the vendored code does** — verified, not assumed — so kidx
inherits them rather than building them:

- **An exception thrown inside the block aborts the transaction, then propagates.** It cannot be left
  to unwinding: an IndexedDB transaction commits by itself once the event loop drains without a pending
  request, so an exception could otherwise escape *after* the writes were already committed. Upstream's
  `writeTransaction` does `try { action() } catch (e) { abort(); awaitFailure(); throw e }` and only
  calls `commit()` on the success path, which is precisely the required behaviour.
- **An engine-initiated abort discards everything in the transaction**, not just the failing
  operation. A loop of `add`s inside one `db.write` is all-or-nothing — a feature, and the reason a
  batch belongs in one scope, but it also means one `ConstraintError` on record 900 undoes 899 good
  writes. Confirmed in the vendored code: a request's `error` event is turned into an exception but
  never `preventDefault()`ed, so per the IndexedDB specification it bubbles to the transaction and
  aborts it. There is no per-operation "ignore this failure" and there cannot be one without changing
  that.
- **There are no savepoints and no partial rollback.** Kormium's `savepoint { }` has no analogue and
  will not get one; IndexedDB has no such concept.
- **Scopes do not nest.** Opening `db.write` (or `db.read`) inside another scope's block deadlocks
  when the store sets overlap: the inner transaction waits for a store the outer one holds, and the
  outer cannot finish while suspended inside the inner. It is also a violation of the suspend
  discipline regardless of overlap. `@RestrictsSuspension` (decision 5) makes it a compile error; if
  that annotation turns out to be impractical, kidx must detect it via a coroutine-context marker and
  throw immediately, naming the enclosing scope — never hang.
- **Concurrent scopes are safe but serialized.** Two `db.write` calls touching the same store are
  ordered, one waiting for the other. This is where isolation comes from, and it is also why
  long-running work does not belong inside a write scope. The vendored code makes the ordering explicit
  rather than relying on the engine's queueing: before running the block it opens and immediately closes
  a key cursor on the first declared store, which forces an overlapping transaction to wait for its
  predecessor to finish instead of interleaving.

### 7. Reads are the engine's read operations, and nothing else

IndexedDB can read in exactly these ways, and each has one name in kidx:

| kidx | engine call |
|---|---|
| `Store.get(key)` | `IDBObjectStore.get(key)` |
| `Store.first(index) { … }` | `IDBIndex.get(range)` — first match in ascending order |
| `Store.all()` | `IDBObjectStore.getAll()` |
| `Store.find(index) { … }` | `IDBIndex.getAll(range, count)` |
| `Store.stream(index) { … }` | `IDBIndex.openCursor(range, direction)` → `Flow<R>` |
| `Store.count()` / `Store.count(index) { … }` | `IDBObjectStore.count()` / `IDBIndex.count(range)` |

Consequences of that mapping, which the KDoc must state because they are not guessable:

- **`first()` has no direction.** `IDBIndex.get` always returns the first match in ascending key
  order. "The newest one" is `find`/`stream` with `direction = DESC` and `limit = 1`, which is a
  cursor, not a `get`.
- **`find` takes one of two paths.** Without a direction it is a native `getAll(range, count)` — one
  call. With `direction = DESC` there is no native equivalent (`getAll` takes no direction), so it
  becomes a cursor walk counting results. Same signature, materially different cost.
- **`stream` is the primitive, `find` is the convenience.** A cursor is how IndexedDB reads; `find`
  materializing the whole result is the shortcut. `stream` gives constant memory over a large store,
  and its `Flow` must be collected inside the scope that produced it.
- **No `findOne`.** It would be `find` with `limit = 1`, i.e. a second name for something that is
  either `get`, `first`, or a one-element cursor walk depending on the query — the kind of uniform
  surface that hides which engine call actually runs.

A query is built **against a specific declared `Index<R>`** and is nothing but an `IDBKeyRange` plus
a direction plus a count: leading index fields pinned with `eq`, at most one range (`gt`/`gtEq`/`lt`/
`ltEq`/`between`) and only on the last field named, in index-field order. If what you want is not
expressible against a declared index, declare the index you need.

```kotlin
Orders.find(Orders.byUser) {
    Orders.userId eq userId          // leading field: pinned
    Orders.createdAt gt since        // trailing field: the range
    direction = DESC
    limit = 50
}
```

There is deliberately **no `where { }` wrapper**, even though Kormium has one. In Kormium, two
`where` blocks are a boolean AND and may be given in any order; here the statements are positional
constraints on successive index fields, order is significant, and no boolean logic exists. Borrowing
the word would promise a semantics kidx does not have — the exact leak principle 1 exists to
prevent. The fields are still named at the call site, which is the readability that mattered.

`direction` is an assignment, not Kormium's `orderBy DESC column` infix: there is no column to give
it. The order is the index's own, and the only choice is which way the cursor walks.

**No `offset`.** Skipping N records in IndexedDB means stepping a cursor N times, i.e. actually
reading N records; an expensive query would look exactly like a cheap one. Keyset pagination
(continue from the last key) is the only form — which is what Kormium's own docs recommend anyway.
kidx makes the recommended pattern the only pattern.

Two edge cases resolved at query-build time rather than by the engine: an **inverted range**
(`lower > upper`) makes IndexedDB throw `DataError`, so kidx rejects it when the query is built, with
a message naming both bounds; **`limit = 0`** returns an empty list without touching the engine.

Not modeled at all (document this like Kormium's gotchas list): `or`, `not`, `like`, `inList`, joins,
aggregates other than `count`, subqueries, grouping.

### 8. Writes are the engine's three write operations, and nothing else

| kidx | engine call | semantics |
|---|---|---|
| `Store.add(row)` | `IDBObjectStore.add` | fails if the key exists; returns the key |
| `Store.put(row)` | `IDBObjectStore.put` | overwrites; returns the key |
| `Store.delete(key)` | `IDBObjectStore.delete(key)` | — |

The names are the engine's, not Kormium's `insert`/`upsert`. `add`/`insert` and `put`/`upsert` do
line up semantically, but `upsert` in Kormium carries a conflict target and a separate update entity,
neither of which exists here — and a reader who expects `onConflict` and does not find it has been
misled by the name. Both return the stored key, which is also how an `autoIncrement` key comes back
(decision 12); there is no `returning: Boolean` flag, because IndexedDB has no defaults, triggers or
computed values for a re-read to discover — the generated key is the only thing it can tell you.

**`put` rewrites the whole record.** There is no partial write in IndexedDB. So a row carrying a
large `Blob` re-stores that blob on every `put`, even if only a small text field changed. The design
consequence, worth stating rather than leaving as folklore: **keep large binaries in their own
store**, keyed by the owning row's key.

Missing on purpose, both in the Roadmap: patch updates (`update(patch)` would be
get + merge + put, i.e. kidx simulating an `UPDATE … SET`) and insert-or-ignore (`add` plus catching
a `ConstraintError`, i.e. exceptions as control flow — and see the Roadmap for why it may not even be
implementable that way).

### 9. What a stored record actually is

- **Keys are in-line.** A store is created with a `keyPath`, so the primary key lives inside the
  record and is always visible on the `Row`. Out-of-line keys (a key passed to `add`/`put` beside the
  value) are not used. With `autoIncrement`, an in-line key path means the engine writes the generated
  key into the stored record itself — which is why `add` can hand it back and why the row's key field
  is populated on a re-read.
- **The record holds exactly the declared fields.** A nullable field with no value assigned is stored
  as an explicit `null`, not omitted — so on the read path "absent" means one thing only: *this record
  predates the field*. That keeps `isSet` a precise answer to a precise question.
- **Undeclared properties are dropped.** kidx reads only declared fields, and `put` writes only
  declared fields, so a record carrying properties kidx does not know about loses them the moment it
  is rewritten. Relevant if a database is shared with non-kidx code.
- **A stored value of the wrong type is a named failure**, distinct from absent and from null: a
  number where `Field.Text()` was declared names the store, the field, the expected type and the
  value found, in the style of Kormium's `ResultMappingException`. This is a real case for a database
  a user can edit by hand in devtools.

### 10. Physical representation of values, and compiler-enforced indexability

Each `FieldType` picks how a Kotlin value is stored. The constraint that shapes all of it: a **valid
IndexedDB key** is only a number, a string, a `Date`, an `ArrayBuffer` (or a view), or an array of
those. `null`, `undefined`, `boolean` and `BigInt` are not valid keys — and IndexedDB does not raise
an error when a record's key would be invalid, it **silently omits that record from the index**.

| Kotlin type | Stored as | Key-valid |
|---|---|---|
| `Uuid` | canonical 36-char string | yes |
| `String` | string | yes |
| `Int` | number | yes |
| `Double` | number | yes |
| `Instant` | native `Date` | yes |
| `ByteArray` | `ArrayBuffer` | yes |
| `Boolean` | `true`/`false` | **no** |
| `Blob` | native `Blob` | **no** |

That set is deliberately shorter than Kormium's thirteen. `Long`, `Short`, `Float`, `LocalDate`,
`LocalDateTime`, `Decimal` and JSON are not built in: each would need a representation decision that
is the application's to make, and `convert` is the supported way to make it.

- **`Long` is not in the built-in set.** In Kotlin/JS it is not a JS number but an object with
  low/high halves; structured-cloning it would store garbage. The alternatives were a number (silent
  precision loss above 2^53) or `BigInt` (exact, but not a valid key, so no index and no primary key
  over it) — both bad enough to leave out.
- **`Instant` is a native `Date`**, so a timestamp reads as a timestamp in devtools and sorts
  chronologically as a key. Precision is milliseconds — a stated property of the type, not a silent
  truncation.
- **`Uuid` is the canonical string**, not a 16-byte buffer: what is on disk is what is in the logs
  and in the code, at the cost of 36 bytes instead of 16. For UUIDv7 the lexicographic order is also
  the chronological one.
- **`Blob` is stored natively.** IndexedDB is the one engine here that can: a `Blob` is not pulled
  into memory when its record is read, and it feeds `URL.createObjectURL` directly. A capability SQL
  backends do not have at all, so taking it is principle 1 rather than an exception — accepting that
  a web-platform type appears in a domain `Row`. `Field.Bytes()` → `ByteArray` is there too, for data
  the application actually wants in memory.

Two rules follow, and both are checked **at compile time**:

- A field in an index or a primary key must be **non-null**. A nullable component makes the whole
  compound key invalid, and IndexedDB responds by dropping the record from the index — a store with
  ten records and an index containing three, no error anywhere. Sentinel-substituting `null` in the
  codec (`""` in, `null` out) was considered and rejected: it hides state. An application that needs
  such a field indexed gives it an explicit domain value for "none", visible in the code and in
  devtools.
- A field in an index must have a **key-valid `FieldType`**. `Field.Boolean()` therefore cannot be
  indexed and cannot be a primary key; a flag that must be indexed is declared `Field.Int()` with 0/1
  in the domain, explicitly.

Mechanically, nullability *and* key-validity live in the field's class, the way Kormium puts
nullability in `NotNullColumn`/`NullableColumn`: `NullableField`, `NotNullField`, and
`KeyField : NotNullField`. `index()` and `primaryKey()` accept only `KeyField`. As a free
consequence, `Field.Boolean().primaryKey()` does not compile either — the same way a nullable primary
key cannot be expressed in Kormium, because `NullableSpec` simply has no `primaryKey()`.

### 11. Key order and comparison — one collation, binary, unchangeable

Sort order is not a kidx decision at all: the engine defines it, and every `find`, `stream` and range
follows it. Written down because it surprises people:

- **Across types**, keys order as: number < `Date` < `String` < binary < array. Mixed-type keys in one
  index are legal and compare this way.
- **Strings compare by UTF-16 code unit.** Not by locale, not case-insensitively, and IndexedDB has
  no collation setting to change it. So `"Z"` sorts before `"a"`, `"ё"` sorts after `"я"`, and accents
  land wherever their code points do. An ascending scan over a `Field.Text()` index is not
  alphabetical in any human sense.
- **Arrays compare element by element**, and a shorter array that is a prefix of a longer one comes
  first. This is exactly the property that makes a compound index behave like a prefix scan, so it is
  load-bearing rather than trivia.

The consequence for applications, which belongs in the docs as a recipe: if you need
locale-aware or case-insensitive ordering, store a **separate normalized field** (lower-cased, NFC,
whatever the language needs) and index that one. kidx will not normalize behind your back — same
stance Kormium takes on SQL collation, where its answer is to lower both sides explicitly. Matching
text (as opposed to ordering it) is not an ordering problem at all: that is kromus.

### 12. Keys: explicit, optionally composite, optionally generated

- **The primary key is always declared.** Kormium falls back to "the column named `id`" when no
  column is marked (`Table.primaryKey`). kidx has no such fallback: in IndexedDB the `keyPath` is
  fixed when the store is created, and an implicit key inferred from a property name is exactly the
  hidden behaviour principle 2 rejects. No `primaryKey()` in a store declaration is an error at
  schema-open time, not a silent guess.
- **Composite keys are supported** — IndexedDB's array `keyPath`, declaration order being key order,
  under the same non-null and key-valid constraints as indexes.
- **`autoIncrement` is supported.** The key generator is the only database-generated value IndexedDB
  has. A primary key left absent is filled by the generator, and `add`/`put` return the key it
  assigned. This is the one legitimate use of the absent state on the write path (decision 3).
  **This needs a change to the vendored code**, the first one: upstream exposes
  `createObjectStore(name, keyPath)` and `createObjectStore(name, autoIncrement)` as separate
  overloads, with no way to ask for both, while kidx needs the combination (an in-line key path *and* a
  generator, so the generated key lands inside the record — decision 9). IndexedDB allows it and
  `IDBCreateObjectStoreOptions` already declares both fields, so the change is additive. Recorded in
  `VENDOR.md`.
- **A field's stored name is its property name.** Kormium's `name = "…"` override exists because SQL
  identifiers differ from Kotlin property names and need dialect quoting; in IndexedDB a name is just
  a key in a JS object, there is no dialect, and renaming a stored property is a data migration
  rather than a cosmetic change. The override is not carried over.

### 13. Migrations, and structural verification when the database opens

IndexedDB forces a monotonic integer `version` and gives schema changes exactly one place to happen —
inside the version-change transaction. So a `Migration(version, steps)` is replayed for every
`version > oldVersion`, each `SchemaStep` becoming a `createObjectStore` / `createIndex` / … call.
That is the whole mechanism, and it is entirely native.

(An earlier version of this spec claimed kidx's numbered migrations matched `kormium-migrate`'s shape
"for consistency". They do not — `kormium-migrate` has no version numbers at all; it uses named
string ids and a checksummed journal table. The statement was simply wrong. That journal is a good
idea and is in the Roadmap, but it is a layer above the engine, not part of it.)

The deliberate divergence from Kormium: **kidx owns DDL, Kormium does not.** Kormium is explicit that
it does not own schema (`CREATE TABLE` is raw SQL; a migration is whatever SQL your backend needs).
kidx has no choice — object stores and indexes can only be created by code inside the version-change
transaction — so `SchemaStep` exists and is typed. This is the one place kidx is a superset rather
than a copy; do not go looking for the Kormium analogue.

**And then the schema is verified against the database, on every open.** IndexedDB reports its actual
shape natively — `objectStoreNames`, a store's `keyPath` and `autoIncrement`, `indexNames`, an index's
`keyPath` and `unique` — so kidx compares that with what the `Schema` declares and fails with a
message naming the difference and the migration that should have produced it. One pass over metadata,
no data read, no bookkeeping of its own.

This is worth having in v1 for a reason beyond tidiness: it catches the most common real divergence —
a migration that forgot an `AddIndex`, or a store created with a different `keyPath` — on any existing
database, without remembering anything. The checksummed journal (Roadmap) answers a different
question: *was an already-applied migration edited afterwards?* Structural verification cannot detect
that, but it also does not need a history to work, which is why it goes first.

### 14. Database lifecycle

- **`openDatabase(schema)`** suspends until the database is open at the schema's version, running any
  needed migrations and then the structural verification of decision 13.
- **Being blocked fails fast; it does not wait.** If we are the one upgrading and another context still
  holds the database open at an older version, IndexedDB fires `blocked` and keeps the request pending —
  `success` would still arrive if the other connection closed. The vendored code deliberately does not
  wait for that: it throws on `blocked`, for `openDatabase` and `deleteDatabase` alike. kidx keeps that
  behaviour and surfaces it as a typed failure naming the database, because the alternative is a call
  that hangs for an unbounded time on something the library cannot influence. Retry policy — show
  "close other tabs to finish updating", then try again — belongs to the application, exactly as
  Kormium ships `ConcurrencyConflictException` and no retry loop.

  (An earlier draft of this decision described an `onBlocked` callback invoked while the open waited.
  That was written before the vendored code was read; it does not wait, and making it wait would mean
  changing the one part of that code kidx vendored it for. If waiting is ever wanted, it is a
  `VENDOR.md` change, not a kidx-side one.)
- **`onVersionChange`** is the mirror case: *we* hold an older version and another context wants to
  upgrade. Default behaviour is to close our connection, so the other context is never blocked
  indefinitely, after which our operations fail with an error saying the schema was upgraded elsewhere
  and the page should reload. An application can install its own handler and take over — save a
  draft, show a banner, close when ready — and the KDoc must state plainly that a handler which never
  closes leaves the other context blocked forever. That is then a decision, deliberately made. This
  is a hair away from Kormium's stance of shipping the seam and leaving policy to the caller (it ships
  `ConcurrencyConflictException`, not a retry loop); the difference is that the do-nothing default
  here hangs *another* execution context, so the safe behaviour is the default and the seam is the
  opt-in.
- **`close()`** is idempotent; afterwards every operation fails with a clear error rather than
  reopening implicitly.
- **`openDatabase` is not a singleton.** Two calls for the same schema give two independent
  connections. kidx does not cache or deduplicate them, so an application that wants one connection
  holds onto it itself.
- **`deleteDatabase(name)`** is native (`indexedDB.deleteDatabase`), exists in the vendored code
  already, and is in v1 because both "reset all data" and every test suite need it. Open connections
  block a delete exactly as they block an upgrade, and it fails the same way.

### 15. Change notification — the one layer above the engine, and it is built in

IndexedDB has **no change events**. None: not per store, not per record, not per database. Every
reactive query in every IndexedDB library is that library's own bookkeeping. kidx does it too, because
"the data changed, redraw" is the central use of a client-side database and leaving it out would push
every consumer into writing the same bookkeeping worse. Principle 3 permits this precisely because it
does not pretend otherwise: this is visibly a kidx service, not a database feature.

It is **built into kidx**, not a separate module. Kormium's split exists to keep coroutines out of
`kormium-core` — its seam (`WriteListener`, `WriteListeners`, `NotificationTransport`) lives in core,
and only the `Flow` layer is separate. Here `Flow` is already public API (`stream()`) and everything is
`suspend`, so there is nothing to keep out; and only `WriteScope` can know which stores a transaction
wrote, so the seam would have had to be public in the library either way.

The design is Kormium's, ported shape-for-shape:

- **`WriteScope` collects the names of the stores it wrote.** Over-collection is safe — it causes a
  spurious refresh, never a missed one.
- **They are fired after the transaction completes**, on the `complete` event, never before: a listener
  that ran pre-commit could read state that is about to vanish. The vendored `writeTransaction` already
  awaits completion after `commit()`, so the hook goes exactly there. A transaction that aborts fires
  nothing.
- **`WriteListeners`** is the registry — a `fun interface` plus `add()` returning a `Registration`.
  This is the non-reactive seam, useful for cache invalidation or metrics, not only for `Flow`.
- **`Store.observe(db) { }` → `Flow<List<R>>`** emits the query's result now, then re-runs and emits
  again after every commit touching that store. Emissions are **conflated**: a burst of writes collapses
  into one re-fetch rather than one per commit. There is also the generic
  `db.observe(stores) { … }` for a fetch spanning several stores, which is the building block the typed
  overload uses.
- **The re-fetch runs in its own `db.read`**, not in the writer's transaction — it must, since the write
  transaction is over by then. So an observed value is a fresh consistent snapshot, taken after the
  commit.

Two costs, documented rather than hidden:

- **Invalidation is per store, not per key.** Writing one row re-runs every query observing that store.
  Kormium has the same granularity (its dirty set is table names) and the same reason: knowing which
  queries a given key affects would mean modelling the queries, which is a query planner.
- **The re-fetch and the row hydration happen wherever the flow is collected** — typically the main
  thread. Kormium's equivalent cannot block a UI; this one can. An observed query over a large store is
  a real cost, and `stream()` plus manual invalidation is the escape hatch.

**Across contexts** — tabs, workers, an extension's service worker — the same signals travel over
`NotificationTransport`: `suspend publish(stores: Set<String>)` plus `subscribe(): Flow<Set<String>>`,
connected by `db.connectNotifications(transport)`. Inbound remote signals are delivered into the local
registry exactly as if a local commit had touched those stores, and are deliberately **not**
re-published, or two contexts would echo forever. Where Kormium ships a Postgres `LISTEN/NOTIFY`
transport, kidx ships **`BroadcastChannelTransport`**: `BroadcastChannel` is available in windows,
workers and extension service workers, which is exactly the set of places that share an IndexedDB
database. The wire format is Kormium's — the store names, comma-joined.

Three things a transport implementation has to get right, all of them consequences of kidx's own
decisions:

- A receiving context re-runs *its own* queries against *its own* connection; the signal carries names,
  never data.
- A signal may name a store the receiver's schema does not have, because the other context is running
  a different code version. Unknown names are ignored, not an error.
- A `Flow` observing a database that has been closed — `close()`, or the default `versionchange`
  handling of decision 14 — terminates with the same typed error the operations would give, rather than
  going quiet. A silently dead reactive query is worse than a failing one.

## The DSL (design sketch — not compiled, not tested)

```kotlin
abstract class Row   // slot storage + ABSENT sentinel, per decision 3

// ---- field types: value conversion only ----

interface FieldType<T> {
    /** Decodes a stored value; `null` for an explicit null. Absence is the engine's business, not this. */
    fun read(stored: JsAny?): T?
    fun toStored(value: T): JsAny?
    val description: String          // diagnostics only, as in Kormium
}

/** A [FieldType] whose stored form is a valid IndexedDB key. Only these can be indexed or a primary key. */
interface KeyFieldType<T> : FieldType<T>

fun <Domain, Stored> FieldType<Stored>.convert(
    toStored: (Domain) -> Stored,
    fromStored: (Stored) -> Domain,
): FieldType<Domain>

/** Key-ness survives conversion, so an enum stored as text stays indexable. */
fun <Domain, Stored> KeyFieldType<Stored>.convert(
    toStored: (Domain) -> Stored,
    fromStored: (Stored) -> Domain,
): KeyFieldType<Domain>

// ---- fields: nullability AND key-validity live in the type (decision 10) ----

sealed class Field<Z, S : Store<R>, R : Row>(
    private val store: S,
    val name: String,                // the Kotlin property name; also the stored property name
    val nullable: Boolean,
    val type: FieldType<Z>,
) {
    internal var ordinal: Int = 0             // index into Row's value array
    internal var isPrimaryKey: Boolean = false

    open fun init() { store.addField(this) }

    class NullableField<Z, S : Store<R>, R : Row>(/* … */) : Field<Z, S, R>(/* nullable = true */) {
        operator fun getValue(row: R, property: KProperty<*>): Z?     // absent and explicit null both read as null
        operator fun setValue(row: R, property: KProperty<*>, z: Z?)
    }

    open class NotNullField<Z, S : Store<R>, R : Row>(/* … */) : Field<Z, S, R>(/* nullable = false */) {
        operator fun getValue(row: R, property: KProperty<*>): Z      // throws on absent, and on stored null
        operator fun setValue(row: R, property: KProperty<*>, z: Z)
    }

    /** A non-null field whose type is a [KeyFieldType]: the only kind `index()` / `primaryKey()` accept. */
    class KeyField<Z, S : Store<R>, R : Row>(/* type: KeyFieldType<Z> */) : NotNullField<Z, S, R>(/* … */)

    // ---- specs: the declaration builders. Separate classes, not a hierarchy, so each carries its
    // own provideDelegate return type (the shape Kormium uses).
    open class Spec<Z>(private val type: FieldType<Z>) {
        operator fun <S : Store<R>, R : Row> provideDelegate(store: S, property: KProperty<*>):
            ReadOnlyProperty<S, NotNullField<Z, S, R>>
        fun nullable(): NullableSpec<Z>
    }

    open class KeySpec<Z>(private val type: KeyFieldType<Z>) {
        operator fun <S : Store<R>, R : Row> provideDelegate(store: S, property: KProperty<*>):
            ReadOnlyProperty<S, KeyField<Z, S, R>>
        fun nullable(): NullableSpec<Z>                          // a nullable field is not indexable anyway
        fun primaryKey(autoIncrement: Boolean = false): PrimaryKeySpec<Z>   // only here: no nullable, no non-key PKs
    }

    class NullableSpec<Z> internal constructor(/* … */)     // → NullableField
    class PrimaryKeySpec<Z> internal constructor(/* … */)   // → KeyField with isPrimaryKey = true

    // ---- the built-in types (decision 10), nested classes as in Kormium ----
    class UUID : KeySpec<kotlin.uuid.Uuid>(UuidFieldType)
    class Text : KeySpec<String>(TextFieldType)
    class Int : KeySpec<kotlin.Int>(IntFieldType)
    class Double : KeySpec<kotlin.Double>(DoubleFieldType)
    class Instant : KeySpec<kotlin.time.Instant>(InstantFieldType)
    class Bytes : KeySpec<ByteArray>(BytesFieldType)
    class Boolean : Spec<kotlin.Boolean>(BooleanFieldType)
    class Blob : Spec<org.w3c.files.Blob>(BlobFieldType)

    companion object {
        fun <Z> of(type: FieldType<Z>): Spec<Z>
        fun <Z> of(type: KeyFieldType<Z>): KeySpec<Z>
        inline fun <reified E : Enum<E>> enum(): KeySpec<E>     // convert() over text
    }
}

/** True when [field] had a value in the stored record, including an explicit null (decision 9). */
fun <R : Row> R.isSet(field: Field<*, *, R>): Boolean

// ---- stores and indexes ----

abstract class Store<R : Row>(val storeName: String, val factory: () -> R) {
    val primaryKey: List<Field.KeyField<*, *, R>>    // declaration order; array keyPath when > 1
    internal val fields: Array<Field<*, *, R>>
    internal fun hydrate(values: Array<Any?>): R     // factory() + adopt(values, this)
    internal fun addField(field: Field<*, *, R>)
}

class Index<R : Row> internal constructor(
    val name: String,
    val fields: List<Field.KeyField<*, *, R>>,
    val unique: Boolean,
)

fun <R : Row> Store<R>.index(
    vararg fields: Field.KeyField<*, *, R>,
    unique: Boolean = false,
): ReadOnlyProperty<Store<R>, Index<R>>

// ---- schema and migrations ----

sealed interface SchemaStep {
    class CreateStore<R : Row>(val store: Store<R>) : SchemaStep
    class AddIndex<R : Row>(val store: Store<R>, val index: Index<R>) : SchemaStep
    class DropIndex(val storeName: String, val indexName: String) : SchemaStep
    class DropStore(val storeName: String) : SchemaStep
}

class Migration(val version: Int, val steps: List<SchemaStep>)

class Schema(val databaseName: String, val migrations: List<Migration>) {
    val version: Int get() = migrations.maxOf { it.version }
}

// ---- lifecycle (decision 14) ----

/** Throws [DatabaseBlockedException] if another context holds an older version open (decision 14). */
suspend fun openDatabase(
    schema: Schema,
    onVersionChange: suspend (VersionChangeSignal) -> Unit = { it.close() },
): Database

suspend fun deleteDatabase(name: String)

class Database internal constructor(/* … */) {
    suspend fun <T> read(vararg stores: Store<*>, block: suspend ReadScope.() -> T): T
    suspend fun <T> write(vararg stores: Store<*>, block: suspend WriteScope.() -> T): T
    val writeListeners: WriteListeners
    fun close()
}

// ---- change notification (decision 15) ----

/** Called after a write transaction commits, with the names of the stores it wrote. Never on abort. */
fun interface WriteListener {
    fun onCommit(stores: Set<String>)
}

fun interface Registration {
    fun remove()
}

class WriteListeners internal constructor(/* … */) {
    fun add(listener: WriteListener): Registration
    val isActive: Boolean            // lets a write scope skip dirty-set bookkeeping entirely
}

/** Re-runs [fetch] after every commit touching one of [stores]. Emits once immediately; conflated. */
fun <T> Database.observe(stores: Set<String>, fetch: suspend ReadScope.() -> T): Flow<T>

/** The typed overload: `Users.observe(db) { … }`, or with no block, every row. */
fun <R : Row> Store<R>.observe(db: Database): Flow<List<R>>
fun <R : Row> Store<R>.observe(db: Database, index: Index<R>, block: QueryBuilder<R>.() -> Unit): Flow<List<R>>

/**
 * Carries "these stores were written" between execution contexts. Inbound signals are delivered into
 * the local [WriteListeners]; they are never re-published, or contexts would echo forever.
 */
interface NotificationTransport {
    suspend fun publish(stores: Set<String>)
    fun subscribe(): Flow<Set<String>>
}

fun Database.connectNotifications(transport: NotificationTransport): Registration

/** The shipped transport: windows, workers and extension service workers all speak BroadcastChannel. */
class BroadcastChannelTransport(channelName: String) : NotificationTransport

// ---- queries ----

/** A key range over one index, plus a direction and a count. Nothing more: see decision 7. */
class Query<R : Row> internal constructor(/* … */)

class QueryBuilder<R : Row> internal constructor(/* … */) {
    var direction: Direction    // ASC (cursor `next`) or DESC (cursor `prev`)
    var limit: Int?

    infix fun <Z> Field.KeyField<Z, *, R>.eq(value: Z)
    infix fun <Z : Comparable<Z>> Field.KeyField<Z, *, R>.gt(value: Z)
    infix fun <Z : Comparable<Z>> Field.KeyField<Z, *, R>.gtEq(value: Z)
    infix fun <Z : Comparable<Z>> Field.KeyField<Z, *, R>.lt(value: Z)
    infix fun <Z : Comparable<Z>> Field.KeyField<Z, *, R>.ltEq(value: Z)
    infix fun <Z : Comparable<Z>> Field.KeyField<Z, *, R>.between(range: ClosedRange<Z>)
    // Validated when the query is built: the named fields must be a prefix of the index, in order,
    // with at most one range and only on the last one named; bounds must not be inverted.
}

/** The key range, direction and limit a query builds — without a database. For tests and self-checks. */
fun <R : Row> Store<R>.describe(index: Index<R>, block: QueryBuilder<R>.() -> Unit): QueryDescription

// ---- scopes (decisions 5-8) ----

@RestrictsSuspension
open class ReadScope internal constructor(/* … */) {
    suspend fun <R : Row> Store<R>.get(key: Any): R?
    suspend fun <R : Row> Store<R>.first(index: Index<R>, block: QueryBuilder<R>.() -> Unit): R?
    suspend fun <R : Row> Store<R>.all(): List<R>
    suspend fun <R : Row> Store<R>.find(index: Index<R>, block: QueryBuilder<R>.() -> Unit): List<R>
    suspend fun <R : Row> Store<R>.count(): Long
    suspend fun <R : Row> Store<R>.count(index: Index<R>, block: QueryBuilder<R>.() -> Unit): Long

    /** Cursor-backed; must be collected inside this scope. */
    fun <R : Row> Store<R>.stream(index: Index<R>, block: QueryBuilder<R>.() -> Unit): Flow<R>

    /** The one allowed suspending consumer: @RestrictsSuspension forbids the stdlib `collect` here. */
    suspend fun <T> Flow<T>.collect(action: (T) -> Unit)
}

@RestrictsSuspension
class WriteScope internal constructor(/* … */) : ReadScope(/* … */) {
    suspend fun <R : Row> Store<R>.add(row: R): Any    // the stored key; generated one if autoIncrement
    suspend fun <R : Row> Store<R>.put(row: R): Any
    suspend fun <R : Row> Store<R>.delete(key: Any)
}
```

### Worked example

```kotlin
class User : Row() {
    var id by Users.id
    var name by Users.name
    var email by Users.email
    var createdAt by Users.createdAt
    var note by Users.note              // String?
}

object Users : Store<User>("users", ::User) {
    val id by Field.UUID().primaryKey()
    val name by Field.Text()
    val email by Field.Text()
    val createdAt by Field.Instant()
    val note by Field.Text().nullable()

    val byEmail by index(email, unique = true)
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

val schema = Schema("app", listOf(
    Migration(1, listOf(
        SchemaStep.CreateStore(Users),
        SchemaStep.AddIndex(Users, Users.byEmail),
        SchemaStep.CreateStore(Orders),
        SchemaStep.AddIndex(Orders, Orders.byUser),
    )),
))

val db = try {
    openDatabase(schema)
} catch (e: DatabaseBlockedException) {
    // Another context holds an older version open; retry policy is the application's (decision 14).
    ui.warn("Close other tabs to finish updating"); return
}
```

```kotlin
// by primary key — IDBObjectStore.get
val user: User? = db.read(Users) { Users.get(id) }

// by unique index — IDBIndex.get
val byEmail: User? = db.read(Users) { Users.first(Users.byEmail) { Users.email eq email } }

// range over a declared index — IDBIndex.getAll(range, count)
val recent: List<Order> = db.read(Orders) {
    Orders.find(Orders.byUser) {
        Orders.userId eq userId
        Orders.createdAt gt since
        limit = 50
    }
}

// newest first — no native getAll for this; a cursor walk
val newest: List<Order> = db.read(Orders) {
    Orders.find(Orders.byUser) {
        Orders.userId eq userId
        direction = DESC
        limit = 20
    }
}

// constant memory over a large store; collected inside the scope
db.read(Orders) {
    Orders.stream(Orders.byUser) { Orders.userId eq userId }.collect { export(it) }
}

// one transaction over two stores; the reads inside see one snapshot,
// and if anything throws, none of the writes happened
db.write(Users, Orders) {
    Users.put(user)
    Orders.add(order)
    Orders.delete(staleOrderId)
}
```

Observation (decision 15) — the write above is what makes these re-emit:

```kotlin
// emits now, then after every commit touching `orders`; bursts are conflated
Orders.observe(db, Orders.byUser) { Orders.userId eq userId }
    .collect { orders -> render(orders) }

// …including commits from another tab, worker or extension service worker
db.connectNotifications(BroadcastChannelTransport("app-db"))

// the non-reactive seam, for a cache or metrics rather than a Flow
val registration = db.writeListeners.add { stores -> cache.invalidate(stores) }
```

### Recipes

**Compare-and-set.** There is no optimistic-locking primitive: `put` writes whole records and returns
a key, not an affected-row count. But a read and a write inside one `db.write` are atomic against
other contexts, so check-then-write is a transaction, not a race:

```kotlin
val applied = db.write(Users) {
    val current = Users.get(id) ?: return@write false
    if (current.updatedAt != expectedUpdatedAt) return@write false   // someone else got there first
    Users.put(current.also { it.note = newNote; it.updatedAt = now })
    true
}
if (!applied) error("stale write — reload and retry")
```

Without this, two contexts editing *different fields of the same row* silently clobber each other,
because `put` rewrites everything (decision 8).

**Keyset pagination.** The only pagination there is (decision 7). The cursor is the last key seen:

```kotlin
suspend fun page(userId: Uuid, after: Instant?, size: Int = 50) = db.read(Orders) {
    Orders.find(Orders.byUser) {
        Orders.userId eq userId
        if (after != null) Orders.createdAt gt after
        limit = size
    }
}
```

**Human-readable ordering.** Index order is UTF-16 binary (decision 11), so store what you want to
sort by:

```kotlin
object Users : Store<User>("users", ::User) {
    val name by Field.Text()
    val nameSort by Field.Text()          // lowercase + NFC, written by the application
    val byName by index(nameSort)
}
```

## Explicitly rejected approaches (don't re-propose these without new information)

- **Dexie.js** — every call site crosses a JS-interop boundary (`external`/`dynamic`), a permanent
  ongoing cost in a Kotlin codebase, and the typed schema/row layer that is the whole point of kidx
  would have to be built on top of it anyway. Dexie's cross-browser workarounds are real value that
  kidx deliberately does not replicate — see "Browser support".
- **RxDB / PouchDB** — they bring their own replication and conflict-resolution machinery, which
  competes with rather than complements whatever sync an application already has. Heavier, and
  opinionated about a problem kidx deliberately does not solve.
- **Reusing Kormium's `Column` / `ColumnType`** — see decision 3 (the `Entity` slot model, by
  contrast, *is* reused).
- **A general Kormium-AST interpreter for IndexedDB** (walking Kormium's `Query`/`Expression`/`Join`
  commonMain AST and executing it against IndexedDB instead of rendering SQL) — considered seriously,
  rejected: IndexedDB's real capability cannot support arbitrary predicate trees without silently
  falling back to full scans. A purpose-built, index-scoped query surface was chosen instead.
- **A `where { }` block** — see decision 7: it would promise boolean logic over a positional key
  range.
- **`orderBy DESC column`** — Kormium's infix form needs a column operand; there is none here, the
  order is the index's own. `direction = DESC`.
- **Sentinel-substituting `null`** to keep nullable fields indexable — see decision 10. It works, and
  it hides state.
- **Storing `Long`** as a JS number or a `BigInt` — see decision 10.
- **`insert`/`upsert` as names**, and `returning: Boolean` — see decision 8.
- **A `name = "…"` override on a field**, and an implicit primary key inferred from a property named
  `id` — see decision 12.
- **Kormium's `overflow` map** (one `Row` class backing several stores) and **`unset()`** — see
  decision 3.
- **`offset`** and **`findOne`** — see decision 7.
- **Savepoints / nested transactions** — IndexedDB has no such concept; see decision 6.
- **Normalizing text for ordering** — see decision 11: kidx does not touch stored values, the
  application stores a normalized field.
- **Reimplementing IndexedDB's storage engine on JVM/Native** — rejected. IndexedDB's API shape is a
  browser-specific historical artifact, not something with independent merit to port. kidx is a
  browser library; an application that needs the same data on another platform wants a real database
  there (Kormium already runs on those platforms), not an IndexedDB clone.

## Browser support

The position, following principle 3: **kidx does not paper over engine bugs.** Libraries like Dexie
carry a real body of per-browser workarounds; replicating that would mean kidx behaving differently
from the engine it wraps, in ways nobody can see from the call site. What kidx does instead is fail
loudly and name what happened.

Consequences an application has to handle itself, and which the README must list rather than leave to
be discovered:

- **IndexedDB can be unavailable.** Firefox in private browsing is the standard case: opening the
  database throws. `openDatabase` surfacing that as a typed, named failure is kidx's whole
  contribution here — an application that must work in private mode needs a fallback (in-memory, or a
  degraded read-only mode), and that is an application decision.
- **Storage can be evicted.** Notably on iOS/Safari, unused site data can be cleared. `persist()` and
  quota reporting are in the Roadmap; the behaviour to design for is "the database may be empty next
  time".
- **Older engines have real bugs** — the ones Dexie works around. The supported set should be pinned
  explicitly (a floor version per browser) rather than implied, and anything below it is out of scope
  instead of silently half-working.

## Testing

Unresolved in shape, but the constraints are known and belong here so the next agent does not start
from zero:

- **IndexedDB exists only in a browser.** Real coverage therefore means browser tests (Karma, the way
  Kormium's own JS backend is tested), which is also the only place engine-specific behaviour —
  transaction lifetime, abort semantics, key ordering — can be verified at all.
- **`fake-indexeddb` in a Node test source set** buys fast unit tests without a browser, at the price
  of testing against a JS reimplementation with its own bugs. Useful for kidx's own logic (query
  building, hydration, migration replay), not for anything in the previous bullet. Which of the two is
  the default, and whether both exist, is open question 4.
- **`describe(index) { … }`** needs no database at all: it returns the key range, direction and limit
  a query builds, which is exactly what the prefix-rule validation should be tested against. It is
  the analogue of Kormium's `renderSql { }`, and for the same reason — a way to assert what would run
  without running it.
- **`deleteDatabase`** (decision 14) is in v1 partly because every test needs a clean slate.

## Roadmap

Everything cut by principle 3, plus what was deferred for its own reasons. Each entry says what it
costs, because the reason for cutting it is not that it is worthless.

- **Finer invalidation for `observe`.** Notification itself is in v1 (decision 15), but its granularity
  is a whole store: writing one row re-runs every query watching it. Narrowing that means knowing which
  keys a query covers and comparing them against the keys a transaction wrote — which is cheap for the
  common shape (a range over one index) and impossible in general, so it would be an optimization with a
  fallback, not a new guarantee. Worth doing only once a real UI shows it matters.
- **Patch updates.** `update(patch)` as get + merge-the-present-fields + put in one transaction,
  restoring Kormium's "an update writes only the fields you assigned". Correct under transaction
  isolation, and it needs `unset()` back. Watch the `Blob` case: a read-modify-write rewrites the
  whole record, blob included (decision 8). Until it exists, the compare-and-set recipe above is the
  supported way to do a targeted change.
- **Insert-or-ignore.** `add` plus catching a `ConstraintError` — **verified impossible as written**.
  The vendored request wrapper turns an `error` event into an exception without calling
  `preventDefault()` on it, so by the time the exception is catchable the transaction is already being
  aborted and every other write in it is lost. Two ways forward, both with a visible cost: a
  `preventDefault()`-ing request variant in the vendored code (a change to the code kidx was careful
  about touching), or `get`-then-`add`, which is two operations and should be named so that it looks
  like two. The same finding is why decision 6 states that one rejected `add` discards the whole scope.
- **Migration journal with checksums.** Kormium records each applied migration's id and checksum and
  fails fast if an already-applied one changed (`MigrationChecksumException`) — valuable in a browser,
  where a user's database at version N was built by whatever code shipped then and cannot be re-run.
  The catch that makes this urgent rather than optional: added later, it cannot know the checksum of
  anything applied before it existed, so every pre-existing database stays unverifiable forever. It is
  cheap only on day one. Structural verification (decision 13) covers the more common failure and
  needs no history, which is why it went first and this did not. Note honestly that principle 3 no
  longer argues against this one: like notification, a journal is openly a kidx layer and pretends to be
  nothing. What keeps it out of v1 is only sequencing — and the day-one argument says that sequencing
  has a real price, so this is the Roadmap entry most worth revisiting soonest.
- **Type-level index arity.** Today `Index<R>` carries no field types, so "leading fields pinned with
  `eq`, at most one range and only last" is validated when the query is built: immediate and
  deterministic (first call, first test), but runtime. The typed alternative is `Index1<R,A>` /
  `Index2<R,A,B>` / `Index3<R,A,B,C>` plus a stage-typed builder chain, where the prefix rule and
  every value's type are checked by the compiler. Costs: a class per arity plus one per chain stage; a
  ceiling on index width that the engine does not have; and the loss of named fields at the call site,
  since a stage-typed chain takes values positionally. **Breaking — decide before the first stable
  release.** `get(key: Any)` is the same problem in miniature (typing it needs the key type on `Store`,
  or the same per-arity treatment for composite keys).
- **Bulk delete.** Deleting a primary-key range is one engine call and is cheap to add. Deleting over
  an index range is a cursor walk removing records one at a time, so it needs a name and KDoc that do
  not make it look like the cheap one. `clear()` belongs with them. Deferred until there is real code
  to look at, so the API follows how people actually delete.
- **`addAll` / batching.** Kormium batches inserts into one statement; IndexedDB has no multi-record
  write, so a batch is a loop inside one transaction. Worth having for the shorter call site and the
  single transaction, not for any engine-level speedup — and note that one failure discards the whole
  batch (decision 6).
- **`Field.json<T>()`.** Kormium stores `@Serializable` values as JSON text. IndexedDB's structured
  clone stores nested objects natively, so the useful form here may be "store the object, not a
  string" — which changes what `read`/`toStored` do and whether kotlinx-serialization is involved at
  all. Not designed.
- **Storage quota and eviction.** Native `Blob` storage makes quota real:
  `navigator.storage.persist()`, usage estimates, and a typed error for `QuotaExceededError`.

## Open questions for whoever continues this

Three earlier questions are now answered by the skeleton and by reading the vendored code, and are
folded into the decisions above: **module layout** (one module — see "Architecture"),
**Kotlin/Wasm** (`js { browser() }` and `wasmJs { browser() }` both compile the vendored sources; note
that upstream configures *no* `nodejs()` target, which bears on the test question below), and
**`@RestrictsSuspension`** (upstream does not use it — decision 5).

1. **Does the vendored API stay public?** The vendored sources are `public` and, in one module under
   `explicitApi()`, they land in kidx's own ABI dump — so `com.juul.indexeddb` becomes part of kidx's
   published surface, which undercuts the "zero third-party surface for consumers" half of decision 4.
   The fix is a mechanical `public` → `internal` pass over `vendor/`, which stays re-appliable after
   each upstream port precisely because it is mechanical. Not done yet: it is a large diff against
   upstream and worth deciding deliberately.
2. **Test strategy** — browser-only, `fake-indexeddb`-first, or both (see "Testing"). Sharpened by the
   skeleton: the vendored build declares only `browser()`, so a Node-based test target is something
   kidx would have to add rather than inherit.
3. **Publishing.** The coordinate is `io.github.kidx:kidx`; a `kidx-bom` (as Kormium has) only becomes
   meaningful if there is ever more than one artifact, and with notification built in there is currently
   nothing that wants to be one. The publish plugin is not wired up yet.
4. **Error hierarchy.** Kormium's typed exceptions (`ResultMappingException`,
   `ConcurrencyConflictException`, `MigrationChecksumException`) have IndexedDB counterparts worth
   naming: `ConstraintError`, `QuotaExceededError`, `AbortError`, blocked-by-another-context,
   IndexedDB-unavailable (upstream raises a bare `IllegalStateException` for it, which kidx should
   wrap), plus kidx's own hydrate-time (absent / null / wrong type — decision 9), query-build-time and
   schema-verification failures. The message quality Kormium achieves (name the store, the field, the
   expected type, and what to do) is a feature, not a nicety — budget for it.
5. **None of kidx's own code is written.** Everything under "The DSL" is a sketch for shape and
   ergonomics, not verified to compile. What compiles today is the vendored engine and nothing else.

## Conventions to adopt from day one

Cheap now, expensive to retrofit — and they are how the author's other libraries are consumed:

- `explicitApi()` on every published module, with the public ABI dumped per module in `<module>/api/`
  and checked by CI; `./gradlew apiDump` output is the review artifact for any deliberate API change.
- An `AGENTS.md` in the copy-ready style of Kormium's: mental model first, then the canonical form
  for each task, a "which form for what" table, recipes, and a gotchas list. For kidx the gotchas
  list should include the engine-call table from decision 7 (which call each read becomes is the
  thing users most need and least can guess), the key-ordering rules from decision 11, the
  all-or-nothing abort semantics from decision 6, and the per-store invalidation granularity from
  decision 15. Plus `docs/` with ADRs, `CHANGELOG.md`, `llms.txt`.
- The house comment style: every non-obvious decision carries its "why" inline, next to the code that
  depends on it — including the measurements behind performance choices.
