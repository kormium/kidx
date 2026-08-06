# kidx

Typed Kotlin storage over IndexedDB — schema, rows, indexed queries, migrations and change
notification, for Kotlin/JS and Kotlin/Wasm.

```kotlin
class User : Row() {
    var id by Users.id
    var email by Users.email
    var createdAt by Users.createdAt
    var note by Users.note                       // String?
}

object Users : Store<User>("users", ::User) {
    val id by Field.UUID().primaryKey()
    val email by Field.Text()
    val createdAt by Field.Instant()
    val note by Field.Text().nullable()

    val byEmail by index(email, unique = true)
    val byCreated by index(createdAt)
}

val db = openDatabase(
    Schema("app", listOf(
        Migration(1, listOf(
            SchemaStep.CreateStore(Users),
            SchemaStep.AddIndex(Users, Users.byEmail),
            SchemaStep.AddIndex(Users, Users.byCreated),
        )),
    )),
)

db.write(Users) { Users.put(user) }

val recent = db.read(Users) {
    Users.find(Users.byCreated) {
        Users.createdAt gt since
        direction = Direction.DESC
        limit = 50
    }
}

Users.observe(db).collect { users -> render(users) }
```

## Why

If your client stores data in SQLite compiled to WASM, you are paying for it twice: a 1-2MB bundle, and
a `wasm-unsafe-eval` in your CSP. If you reach for a JS library instead, every call site crosses an
`external`/`dynamic` boundary — a permanent cost, not a one-time one.

kidx is neither. It is a typed Kotlin layer over the IndexedDB the browser already has.

## What it is not

kidx does not pretend IndexedDB is SQL. It is a single-index range-scan engine with no query planner,
and the API says so:

- A query is a key range over **one declared index** — leading fields pinned, at most one trailing
  range. No `where { }`, no `or`, no joins, no aggregates beyond `count`.
- No `offset`. Skipping N records means stepping a cursor N times, and an expensive query should not
  look cheap. Keyset pagination is the only kind.
- No partial update. `put` writes a whole record, because that is what the engine does.
- Every operation is **one engine call**, and the docs say which — `find` ascending is a single
  `getAll`; the same call descending is a cursor walk, because IndexedDB has no descending `getAll`.

What the engine *can* do that a SQL backend cannot, it does: a `Blob` is stored natively and stays out
of memory until something asks for its contents.

## What the compiler checks

Limitations of the engine are expressed in types where they can be:

```kotlin
val verified by Field.Boolean()
val byVerified by index(verified)      // does not compile — a boolean is not a valid IndexedDB key
Field.Boolean().primaryKey()           // does not compile — same reason
Field.Text().nullable().primaryKey()   // does not compile — a nullable key cannot be expressed

db.read(Users) { Users.put(user) }     // does not compile — a read scope has no writes
```

The rest fails loudly at the call site, naming the store, the field and what to do about it — a query
that is not a prefix of its index, a row that is missing a non-null field, a schema that no longer
matches the database.

## Install

```kotlin
dependencies {
    implementation("io.github.kidx:kidx:0.1.0")
}
```

`0.x`: two breaking changes are knowingly still to come before `1.0` — see
[CHANGELOG.md](CHANGELOG.md).

Targets `js` and `wasmJs`, both browser-only. Requires Kotlin 2.4+.

## Documentation

- **[AGENTS.md](AGENTS.md)** — the API reference: the canonical form for each task, the engine call each
  operation becomes, recipes, gotchas. Start here.
- **[SPEC.md](SPEC.md)** — the design: every decision and its reasoning, what was rejected and why, and
  what is deliberately absent.
- [docs/adr](docs/adr/README.md) — decision records for the choices most likely to be revisited.

## Building

```sh
./gradlew compileKotlinJs compileKotlinWasmJs      # both targets; wasmJs is the stricter one
./gradlew jsNodeTest                               # the suite on fake-indexeddb under Node
CHROME_BIN=... ./gradlew jsBrowserTest wasmJsBrowserTest -PenableBrowserTests=true
./gradlew apiCheck                                 # the public ABI is the review artifact
```

The browser run is the one that is evidence about a browser rather than about kidx; `fake-indexeddb` is
a reimplementation with its own bugs. If no browser is installed, the build falls back to the Chromium
that `puppeteer` downloads during the npm install.

## Licence

Apache 2.0. Bundles a modified copy of [JuulLabs/indexeddb](https://github.com/JuulLabs/indexeddb)
(Apache 2.0) — see [NOTICE](NOTICE) and
[`src/webMain/vendor/VENDOR.md`](src/webMain/vendor/VENDOR.md).
