# ADR 0005 — Verify the schema on open instead of journalling migrations

- Status: Accepted
- Date: 2026-08-07

## Context

`kormium-migrate` records every applied migration's id and checksum in a journal table and fails fast if
an already-applied migration's SQL changed since. It is a good mechanism, and it is *more* valuable in a
browser than on a server: a user's database at version N was built by whatever code shipped then, and
there is no way to re-run it.

IndexedDB gives none of that. It gives a monotonic integer version and a single place where schema may
change. Everything else would be kidx's own bookkeeping, in a service store kidx creates and maintains.

There is a cheaper source of truth available for free: the engine reports its own shape —
`objectStoreNames`, a store's `keyPath` and `autoIncrement`, `indexNames`, an index's `keyPath` and
`unique`.

## Decision

Fold the migration list into the shape it should produce (at `Schema` construction, which also catches
an incoherent list before it reaches a user's disk), and compare that shape against what the engine
reports on **every** open, in both directions. Something declared and missing is a mismatch; something
present and undeclared is a mismatch too.

The checksum journal stays in the Roadmap.

## Consequences

- Catches the most common real divergence — a migration that forgot an `AddIndex`, a store created with
  a different key path — with one pass over metadata, no data read, and no state of kidx's own.
- Works on **any** existing database, including ones created before this check existed, because it
  remembers nothing and only compares. A journal cannot say that: added later, it has no checksum for
  anything applied before it.
- Does **not** catch an already-applied migration being edited afterwards, which is exactly what a
  journal is for. The two answer different questions; this one was chosen first because it is free and
  retroactive, not because it subsumes the other.
- Undeclared objects being an error assumes the version number keeps older code away from a newer
  database. It does — IndexedDB refuses to downgrade — and that refusal now surfaces as
  `DatabaseTooNewException`.
