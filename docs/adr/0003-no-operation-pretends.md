# ADR 0003 — No operation pretends to be an engine capability

- Status: Accepted
- Date: 2026-08-07

## Context

IndexedDB is a single-index range-scan engine with no query planner. A typed layer over it can either
expose what the engine does, or offer a friendlier surface and paper over the gap. The friendly path is
how a `where { }` builder ends up silently doing a full scan, and how a one-line `update` turns out to
be a read plus a write.

The first formulation of this rule was "v1 emulates nothing", which was too blunt: it also ruled out
change notification, which IndexedDB genuinely lacks and which every consumer needs.

## Decision

Every operation on the data-access surface — read, write, query, migrate — is **one engine call**, or a
cursor, which is the engine's other read primitive. kidx will not offer an operation that *looks* like
one call and is secretly several.

A layer that is openly a kidx layer is fine. The test is not "does this emulate something" but "does
this misrepresent what the engine did".

## Consequences

- Out: `update(patch)` (a read plus a write wearing the shape of a write), insert-or-ignore (which
  cannot work at all — a failed request aborts the transaction), `offset` (N cursor steps disguised as
  a parameter), a `where { }` block, `findOne`.
- In: change notification, which nobody mistakes for a database feature (ADR 0006).
- Each read operation documents the engine call it becomes, because the difference is invisible
  otherwise: `find` ascending is one `getAll`; the same call with `direction = DESC` is a cursor walk,
  since IndexedDB has no descending `getAll`.
- The API is smaller than a comparable SQL-shaped one, and several conveniences are the caller's to
  write. Deliberate: an explicit two-step read-then-write shows its cost at the call site.
