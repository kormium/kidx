# Changelog

Versions follow [semantic versioning](https://semver.org). While the major version is `0`, a minor bump
may break API — and two changes are already known to be breaking when they land, so they are wanted
*before* `1.0`: type-level index arity, and whatever finally enforces the suspend discipline (see
SPEC.md's Roadmap and open questions).

## Unreleased

Not published. Cutting `0.1.0` is a one-line version change plus Central credentials, and it is a
deliberate wait rather than an oversight: index arity is a known breaking change that should land first,
so publishing now would be a promise to break the API immediately.

The first version. Nothing is published yet, so everything here is "added".

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
