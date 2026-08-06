# kidx — spec

Status: **design only, nothing implemented yet.** This document is the full record of a design
conversation; it exists so a next agent (human or Claude) can pick up the work without re-deriving
any of it. Read it end to end before writing code — several approaches below were considered and
explicitly rejected, and re-proposing them wastes a round trip.

## What this is

A typed, Kotlin/JS-native storage layer over IndexedDB, built for [stramus](../stramus) but designed
as an independent sibling project (same relationship [kormium](../kormium) and [kromus](../kromus)
have to it). Its declaration syntax deliberately imitates Kormium's `Table`/`Column`/`Entity` DSL —
same author, same codebase, same taste — but it is **not** built on Kormium's SQL machinery. It is a
new, from-scratch typed layer over a vetted low-level IndexedDB engine.

## Why this exists (context for the "why", not just the "what")

stramus's client currently stores everything in SQLite compiled to WASM
(`kormium-sqlite-js`), persisted inside IndexedDB by the WASM SQLite VFS. Two concrete, documented
frictions motivate moving off WASM:

- **Chrome Web Store review friction.** `store/submission.md` and `store/README.md` in the stramus
  repo already have to carry an explicit justification for the `wasm-unsafe-eval` CSP directive the
  WASM SQLite module requires. Dropping WASM removes this from the review surface entirely.
- **Bundle size.** WASM SQLite builds are on the order of 1-2MB; a plain JS/Kotlin-JS IndexedDB layer
  is nowhere near that.

The guiding principle for the whole design, stated by the project owner and applied throughout:
**take the maximum IndexedDB actually offers, and not one gram more.** IndexedDB is a single-index
range-scan engine with no query planner — it is not SQL, and pretending otherwise (by porting a
general SQL-shaped query DSL on top of it) either produces a leaky abstraction or hides silent full
table scans behind a friendly-looking `where{}`. Every decision below follows from taking that
seriously.

## Architecture at a glance

```
┌─────────────────────────────────────────────────────────────┐
│  kidx (this project) — typed schema/index/migration DSL      │
│  Store<R>, Field<R,Z>, Index<R>, Schema/Migration, Engine     │
└───────────────────────────┬───────────────────────────────────┘
                             │  Engine interface (the only platform seam)
              ┌──────────────┴──────────────┐
              ▼                              ▼ (later, not now — see "not building")
   IndexedDbEngine (jsMain)          KormiumEngine (jvmMain/nativeMain)
   built on JuulLabs/indexeddb        built on kormium-sqlite / native SQLite
   (vendored or depended-on)          — only if a non-web stramus client ever exists
```

Full-text search is **not** part of this project. It is a separate concern, already solved by
[kromus](../kromus)'s `TextIndex` (BM25), kept live via `kromus-sync`. See "Search" below.

## Decisions made, and why (read this before changing any of it)

### 1. No `Catalog` phantom type

Kormium parameterizes `Table<G: Catalog, T: Entity>` so that tables belonging to different logical
databases can't be mixed up inside one process — a real concern for a general-purpose library used
by servers that may hold several catalogs. stramus's client opens exactly one local database per
browser instance; there is nothing to disambiguate. `Store<R>` here takes **no** catalog type
parameter. (This still holds even if a non-web engine is added later — that varies the *engine*, not
the *catalog*; there's still exactly one logical schema.)

### 2. No `init { col1; col2; ... }` boilerplate

Kormium's own current schema code (including stramus's live `core/.../Schema.kt`) uses this pattern
to force-reference every declared column, on the assumption that an unreferenced property inside an
otherwise-used `object` could be stripped by dead-code elimination in a production Kotlin/JS build.

**This was tested against stramus's real production build, not assumed.** A throwaway column
(`dceProbeXyz123`) was added to the `Sections` table, once referenced in `init{}`, once not, and
`./gradlew :extension:jsBrowserDistribution` was run both times. In **both** cases the column's live
registration code (not just its name as a string) survived in the minified `stramus.js` bundle —
confirmed by inspecting the surrounding compiled code, not just grepping for the identifier. Kotlin's
object initialization runs all of an object's property initializers together; this toolchain does not
strip individual unused properties out of an otherwise-live object.

Caveat, not tested: a table that is **never referenced by name anywhere outside its own declaration**
(so the whole `object` is never touched) is a different, more drastic scenario and wasn't reproduced.
If that ever becomes a real pattern (a store nothing ever queries directly), re-verify before assuming
it's safe.

Conclusion: **do not carry the `init{}` pattern into kidx.** This also matches Kormium's own canonical,
documented example (`kormium/readme.md`), which doesn't use it either — the pattern in stramus's
current schema appears to be defensive/cargo-culted, not required by the current toolchain.

### 3. Don't reuse Kormium's `Column` / `Entity` / `ColumnType` classes

Investigated directly in `kormium-core/src/commonMain/kotlin/{Column,ColumnType,Entity}.kt`. All three
are SQL-executor-coupled down to the wire level, not reusable as passive type descriptors:

- `Column` implements `Expression`/`Operand` and renders `toSql(builder: ParamBuilder)` — it's a query-DSL
  AST node, not a schema-metadata value.
- `ColumnType<T>.read(rs: ResultSet, index: Int)` reads from a JDBC-shaped `ResultSet` by column index,
  and `toParam` binds SQL parameters. Neither has anything to decode an arbitrary IndexedDB value from.
- `Entity`'s slot storage exists specifically to distinguish "absent" (omit from SQL `INSERT`/`UPDATE`)
  from "explicit null" (write SQL `NULL`) — meaningless for IndexedDB's `put()`, which always writes a
  value whole.

Reusing these would mean faking a `ResultSet` adapter over IndexedDB just to satisfy an interface shape
that doesn't fit — exactly the "force-fitting" the whole project exists to avoid.

**What to keep**: the *vocabulary*. Same logical type names (`UUID`, `Text`, `Instant`, `Int`, `Long`,
`Boolean`, `Bytes`, …) for consistency with the rest of the codebase, but as a fresh, independent
`FieldType`/`Field` implementation with zero dependency on kormium-core.

### 4. Engine: adopt JuulLabs/indexeddb's low-level primitives, not its public surface

[`JuulLabs/indexeddb`](https://github.com/JuulLabs/indexeddb) (Apache 2.0, Kotlin/JS + Kotlin/WASM,
actively maintained — WASM support added 2025) already solves the one genuinely hard, risky part of
this project: **IndexedDB transaction lifetime**. An `IDBTransaction` auto-commits when the JS event
loop returns without a pending `IDBRequest`; suspend across the wrong kind of await and the transaction
can silently close under you. JuulLabs solves this with `Dispatchers.Unconfined` plus a hard rule,
enforced by only exposing transactional operations as extension functions scoped to `Transaction`/
`WriteTransaction` receivers: *"you must not call any suspend function except those provided by this
library and scoped on Transaction."* This is not a documentation convention — the API shape makes it
close to impossible to violate by accident.

Primitives to build on (real signatures, from `core/src/webMain/kotlin/{Database,Transaction,ObjectStore,Index,Queryable}.kt`):

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

Values are raw `IDBValue`/`JsAny` — the library is explicit that passing Kotlin objects straight through
is "probably wrong" and expects an `external interface` at the boundary. **This is exactly the gap kidx
fills**: JuulLabs gives safe transactions and raw storage, kidx gives typed schema, typed queries, and
migrations on top.

Decision still open: vendor JuulLabs' `core` module directly into kidx (full read + fork, given stramus
is a reviewed Chrome extension where minimizing third-party surface has a real, documented payoff — see
`store/submission.md`) vs. take it as a normal dependency. Leaning vendor; not decided. Either way, do
not modify its transaction-scoping discipline — that's the part worth not touching.

### 5. Query surface is deliberately narrower than Kormium's `where{}`

A generic `where{}` builder would let you write predicates IndexedDB cannot execute against an index,
silently falling back to a full scan — precisely the kind of hidden cost the whole project exists to
avoid. Instead, queries are built **against a specific declared `Index<R>`**: leading fields must be
pinned with `.eq()`, only the trailing field may be a range (`gt`/`lt`/`between`), sort order follows
the index, and there is a `limit`. If what you want isn't expressible against a declared index, that's
a compile-time/API-shape problem, not a silent-scan problem — declare the index you need.

### 6. Search is out of scope for kidx — use kromus

Multi-word search (currently a `LIKE`-scan in stramus's SQLite store, capped at 4 words, no ranking)
does not belong on top of IndexedDB at all. [kromus](../kromus)'s `TextIndex` (BM25 inverted index,
pluggable `Analyzer` for stemming/stopwords/n-gram typo-tolerance) is a strict upgrade over the current
behavior, not just a port of it. It runs **in memory**, independent of storage, and is kept fresh via
`kromus-sync`'s `Flow<List<T>>.syncTo(index, keyOf, versionOf) { }` — incremental reconciliation by key
+ version, not a full rebuild per change. The flow it consumes can come from kidx's `Engine` (e.g. a
`getAll` + a change notification) the same way `kormium-observe`'s `Table.observe(db){}` feeds it in
the SQL world.

Open, unresolved:
- **Persistence of the built index across reloads** — via `kromus-kemus` (serialize into a kemus store)
  vs. just rebuilding in memory on startup. Leaning toward rebuild-on-startup given stramus's data
  scale (personal card collections, not large), but not measured.
- **Field weighting** — `TextIndex.add(key, text, attributes)` takes one `text` string per document, no
  built-in per-field boost. Current search matches title/url/content; if title matches should outrank
  content matches, that needs a manual trick (e.g. repeating title tokens in the composed text) — not
  designed yet.
- **Not yet measured**: BM25 query latency on a seeded large card collection (a few thousand cards).
  Worth a quick benchmark before committing to "definitely fine at this scale."

## The DSL (design sketch — not compiled, not tested)

```kotlin
abstract class Row

sealed class FieldType<Z> {
    object UUID : FieldType<kotlin.uuid.Uuid>()
    object Text : FieldType<String>()
    object Instant : FieldType<kotlin.time.Instant>()
    object Int : FieldType<kotlin.Int>()
    object Long : FieldType<kotlin.Long>()
    object Boolean : FieldType<kotlin.Boolean>()
    object Bytes : FieldType<ByteArray>()
}

class Field<R : Row, Z> internal constructor(
    val store: Store<R>,
    val fieldKey: String,
    val name: String,
    val nullable: Boolean,
    val type: FieldType<Z>,
)

sealed class FieldSpec<Z>(private val name: String?, private val type: FieldType<Z>) {
    operator fun <R : Row> provideDelegate(store: Store<R>, prop: KProperty<*>): ReadOnlyProperty<Store<R>, Field<R, Z>> {
        val field = Field(store, prop.name, name ?: prop.name, nullable = false, type)
        store.registerField(field)
        return ReadOnlyProperty { _, _ -> field }
    }
    fun nullable(): NullableFieldSpec<Z> = NullableFieldSpec(name, type)
    fun primaryKey(): PrimaryKeyFieldSpec<Z> = PrimaryKeyFieldSpec(name, type)
}

object Field {
    fun UUID() = object : FieldSpec<kotlin.uuid.Uuid>(null, FieldType.UUID) {}
    fun Text() = object : FieldSpec<String>(null, FieldType.Text) {}
    fun Instant() = object : FieldSpec<kotlin.time.Instant>(null, FieldType.Instant) {}
    fun Boolean() = object : FieldSpec<kotlin.Boolean>(null, FieldType.Boolean) {}
    // Long, Int, Bytes follow the same shape.
}

open class Store<R : Row>(val name: String, val new: () -> R) {
    internal val fields = mutableListOf<Field<R, *>>()
    internal lateinit var primaryKey: Field<R, *>
    internal fun registerField(f: Field<R, *>) { fields += f }
}

class Index<R : Row>(val name: String, val fields: List<Field<R, *>>, val unique: Boolean = false)

fun <R : Row> Store<R>.index(vararg fields: Field<R, *>): ReadOnlyProperty<Store<R>, Index<R>> = TODO()

sealed interface SchemaStep {
    class CreateStore<R : Row>(val store: Store<R>) : SchemaStep
    class AddIndex<R : Row>(val storeName: String, val index: Index<R>) : SchemaStep
    class DropIndex(val storeName: String, val indexName: String) : SchemaStep
    class DropStore(val storeName: String) : SchemaStep
}
class Migration(val version: Int, val steps: List<SchemaStep>)
class Schema(val migrations: List<Migration>) { val version get() = migrations.maxOf { it.version } }

interface Engine {
    suspend fun open(schema: Schema)
    suspend fun <R : Row, K : Any> get(store: Store<R>, key: K): R?
    suspend fun <R : Row> put(store: Store<R>, value: R)
    suspend fun <R : Row, K : Any> delete(store: Store<R>, key: K)
    suspend fun <R : Row> query(index: Index<R>, equals: List<Any?>, limit: Int? = null): List<R>
    suspend fun transaction(vararg stores: Store<*>, block: suspend TxScope.() -> Unit)
}
```

`Migration.version` maps directly onto IndexedDB's `onupgradeneeded(oldVersion, newVersion)`: replay
every migration whose `version > oldVersion`, translating each `SchemaStep` into a call on JuulLabs'
`VersionChangeTransaction` (`createObjectStore`, `createIndex`, …). Same numbered shape as
`kormium-migrate`, deliberately, for consistency with the rest of the author's tooling.

### Worked example: two of stramus's real tables

```kotlin
data class SectionRow(
    val id: String, val title: String, val orderKey: String,
    val deletable: Boolean, val collapsed: Boolean,
    val pinSalt: String?, val pinHash: String?,
    val updatedAt: Long, val deletedAt: Long?,
) : Row()

object Sections : Store<SectionRow>("sections", ::SectionRow) {
    val id by Field.UUID().primaryKey()
    val title by Field.Text()
    val orderKey by Field.Text()
    val deletable by Field.Boolean()
    val collapsed by Field.Boolean()
    val pinSalt by Field.Text().nullable()
    val pinHash by Field.Text().nullable()
    val updatedAt by Field.Instant()
    val deletedAt by Field.Instant().nullable()
}

object Collections : Store<CollectionRow>("collections", ::CollectionRow) {
    val id by Field.UUID().primaryKey()
    val sectionId by Field.UUID()
    val title by Field.Text()
    val orderKey by Field.Text()
    val createdAt by Field.Instant()
    val readOnly by Field.Boolean()
    val updatedAt by Field.Instant()
    val deletedAt by Field.Instant().nullable()

    val bySection by index(sectionId, orderKey)
}
```

Query and transaction shape:

```kotlin
suspend fun Engine.byCollection(collectionId: Uuid): List<CollectionRow> =
    query(Collections.bySection, equals = listOf(collectionId))

suspend fun Engine.deleteCollection(id: Uuid) = transaction(Sections, Collections, Cards) {
    Collections.delete(id)
    Cards.byGroup.eq(id).toList().forEach { Cards.delete(it.id) }
}
```

### Full schema — all 9 client tables

Source of truth for field names/types: stramus's current `core/src/commonMain/kotlin/stramus/core/db/Schema.kt`
(Kormium 0.11.0). Reproduce the same fields for every table below; only the **indexes** differ from the
SQL version, and two spots are deliberate improvements, not just ports:

| Store | Fields (same as current SQLite schema) | Index |
|---|---|---|
| `sections` | id(PK), title, orderKey, deletable, collapsed, pinSalt?, pinHash?, updatedAt, deletedAt? | none (few rows/user, sorted in memory) |
| `collections` | id(PK), sectionId, title, orderKey, createdAt, readOnly, updatedAt, deletedAt? | `bySection = (sectionId, orderKey)` |
| `card_sections` | id(PK), collectionId, title, description?, orderKey, collapsed, updatedAt, deletedAt? | `byCollection = (collectionId, orderKey)` |
| `cards` | id(PK), collectionId, cardSectionId?, kind, title, url, favicon?, content?, thumb?, mime?, blobSha?, orderKey, createdAt, updatedAt, deletedAt? | `byGroup = (collectionId, cardSectionId, orderKey)` — **improvement**: current SQL version only indexes `(collectionId, orderKey)` and post-filters by `cardSectionId` in code; a 3-field compound index makes `byCollection` a pure prefix range-scan |
| `card_blobs` | cardId(PK), data — **improvement**: store raw `Blob`/`ArrayBuffer`, not a base64 `data:` URI (IndexedDB supports binary natively; current SQLite version is forced into base64 text) | none |
| `usage` | url(PK), title, host, hits, lastUsedAt, deletedAt? | none |
| `action_usage` | kind(PK), hits, lastUsedAt | none |
| `favicons` | host(PK), dataUri, updatedAt | none |
| `sync_meta` | composite PK (tbl, rowId), hash, rev | none |
| `sync_state` | k(PK), v | none |

## Explicitly rejected approaches (don't re-propose these without new information)

- **Dexie.js** — would work, but every call site crosses a JS-interop boundary (`external`/`dynamic`),
  which is a permanent ongoing cost, not a one-time one, for a Kotlin/JS-native codebase. Also: Dexie's
  ~25-30KB gzip and cross-browser Safari/private-mode fixes matter less here than they would for a
  general-purpose library, since stramus ships primarily as a Chromium extension (`chromiumapp.org`
  OAuth redirect URIs throughout the repo confirm this).
- **RxDB / PouchDB** — bring their own replication/conflict-resolution machinery that would compete
  with, not complement, stramus's already-built bespoke sync protocol (LWW + tombstones + hash-based
  dirty check in `server/.../Sync.kt`). Heavier, and solves an already-solved problem.
- **Reusing Kormium's `Column`/`Entity`/`ColumnType`** — see decision #3 above.
- **A general Kormium-AST interpreter for IndexedDB** (walking Kormium's `Query`/`Expression`/`Join`
  commonMain AST and executing it against IndexedDB instead of rendering SQL) — considered seriously,
  rejected: IndexedDB's real capability (single-index range scan) doesn't support arbitrary predicate
  trees without silently falling back to full scans, defeating the "take only what IndexedDB offers"
  principle. A purpose-built, index-scoped query surface (see above) was chosen instead.
- **Reimplementing IndexedDB's storage engine on JVM/Native/desktop/mobile** — rejected. IndexedDB's
  API shape is a browser-specific historical artifact, not something with independent merit to port.
  If a non-web stramus client is ever built, give it a `KormiumEngine` implementing the same `Engine`
  interface, backed by real SQLite via Kormium (which already runs on those platforms) — not a
  hand-rolled IndexedDB clone. Not building this now; no non-web stramus client exists or is planned.
- **`Catalog` type parameter** — see decision #1.
- **`init{}` column-reference boilerplate** — see decision #2, tested empirically against stramus's
  real production build.

## Open questions for whoever continues this

1. Vendor JuulLabs/indexeddb's `core` module into kidx, or take it as a Maven dependency? (Leaning vendor.)
2. Persist kromus's `TextIndex` across page reloads, or rebuild in memory on startup? (Leaning rebuild.)
3. Field-weighting scheme for search (title should likely outrank content/url matches) — not designed.
4. Measure kromus BM25 query latency on a few-thousand-card seeded collection before committing.
5. `Codec` — the actual encode/decode between Kotlin domain values (`Uuid`, `Instant`, …) and whatever
   JuulLabs' `IDBValue`/`JsAny` machinery needs — is named in the sketch above but not designed at all.
6. Module layout: mirror kormium's split (e.g. `kidx-core` for the DSL, a separate engine module) —
   not decided; no `settings.gradle.kts` exists yet in this repo.
7. Whether kidx becomes a genuinely independent published library (own Maven coordinates, like
   kormium/kromus) or stays an internal-only module vendored into stramus — current framing assumes
   the former (hence a separate sibling repo), but this hasn't been explicitly confirmed.
8. No code has been written. Everything under "The DSL" is a sketch for shape/ergonomics, not
   verified to compile.

## Wiring this into stramus, later

stramus already has the composite-build pattern for exactly this situation — see stramus's
`README.md`, "Kormium from a sibling checkout": if `../korm` sits next to the stramus checkout, it's
picked up live via composite build; otherwise the published Maven artifact is used. The same mechanism
should be extended (or mirrored) for kidx once it has something to consume — check
`stramus/settings.gradle.kts` for the existing conditional-composite-build logic before wiring a
second one in.
