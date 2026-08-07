# Changelog

Versions follow [semantic versioning](https://semver.org). While the major version is `0`, a minor bump
may break API — and two changes are already known to be breaking when they land, so they are wanted
*before* `1.0`: type-level index arity, and whatever finally enforces the suspend discipline (see
SPEC.md's Roadmap and open questions).

## 0.1.1

### Fixed

- **A query pinning only a prefix of a compound index leaked rows of an unrelated key.** `find`/`stream`
  (and anything built on them, like `Store.observe`) on a multi-field index, constrained with `eq` on the
  leading field(s) and no trailing range — e.g. `Cards.find(Cards.byUser) { Cards.userId eq id }` — encoded
  its lower bound as a bare value instead of an array whenever the *query* pinned exactly one field,
  regardless of how many fields the *index* actually had. IndexedDB then compared that bare value against
  the index's array-shaped stored keys by cross-type ordering (a string always sorts below any array), so
  the lower bound matched nothing — and paired with the correctly array-shaped upper bound, could admit a
  lexicographically-earlier key's rows too. Found via a real cross-collection data leak in a downstream
  app. The bound now keys off the index's own field count rather than how many values a given query
  happens to encode.

## 0.1.0

The first version: everything below is new.

Two breaking changes are already known to be wanted before `1.0` — type-level index arity, and whatever
finally enforces the suspend discipline (SPEC.md's Roadmap and open questions). `0.x` is where they
belong; that is what the leading zero is for.

### The v1 surface

- **Schema declaration** — `Store<R>` over a `Row` with `Field` property delegates; `Field.UUID()`,
  `Text`, `Int`, `Double`, `Instant`, `Boolean`, `Blob`, `enum<E>()`, and `Field.of(yourType)` with
  `convert` for anything else. Nullability and key-validity live in the field's *type*, so an index or a
  primary key over a nullable or non-key field does not compile.
- **Migrations** — `Schema`, `Migration`, `SchemaStep`; kidx owns DDL because IndexedDB allows schema
  changes only inside a version-change transaction. The migration list is folded and validated at
  construction, and the resulting shape is verified against the database on every open, in both
  directions.
- **Scopes** — `db.read(stores) { }` and `db.write(stores) { }` as separate types, so a write inside a
  read does not compile. Reads: `get`, `first`, `all`, `find`, `stream`, `count`. Writes: `add`, `put`,
  `delete`. Each is exactly one engine call, and which one is documented per operation.
- **Queries** — a key range over one declared index: leading fields pinned with `eq`, at most one
  trailing range, `direction`, `limit`. No `where { }`, no `offset`, no boolean logic — none of it exists
  in the engine. `Store.describe(index) { }` returns what a query would ask for, without a database.
- **Change notification, built in** — `WriteListeners` as the non-reactive seam, `Store.observe(db)` as a
  conflated `Flow`, and `NotificationTransport` with a shipped `BroadcastChannelTransport` for other
  tabs, workers and extension service workers.
- **Typed failures** — `KidxException` with `SchemaException`, `SchemaMismatchException`,
  `RowMappingException`, `QueryException`, `FieldTypeException`, `DatabaseBlockedException`,
  `DatabaseClosedException`, `IndexedDbUnavailableException`, and an `EngineException` family
  (`ConstraintViolationException`, `QuotaExceededException`, `DatabaseTooNewException`) carrying the
  engine's own `DOMException` name.
- **Nested transactions are refused** rather than allowed to deadlock.

### Notable non-features

Absent on purpose, each with its reasoning in SPEC.md: patch updates, insert-or-ignore, bulk delete,
`clear()`, batching, `Field.Bytes()`, `Field.json<T>()`, a migration journal, and per-key invalidation
for `observe`.

### Internals

- Targets js and wasmJs from one `webMain` source set; no `expect`/`actual` outside the test host.
- Bundles a modified copy of [JuulLabs/indexeddb](https://github.com/JuulLabs/indexeddb) (Apache 2.0),
  internal to this library so it appears nowhere in the public ABI. Provenance and every modification
  are recorded in `src/webMain/vendor/VENDOR.md`.
