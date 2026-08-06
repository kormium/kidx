# ADR 0004 — Enforce the transaction discipline at runtime, not at compile time

- Status: Accepted
- Date: 2026-08-07

## Context

An IndexedDB transaction commits by itself once the event loop drains without a pending request. So
inside `db.read { }` / `db.write { }` a caller must not suspend on anything but kidx's own operations —
a `delay`, an HTTP call, or a nested `db.write` all end the transaction under the block. A nested
transaction is worse than the rest: it *deadlocks* when the store sets overlap, and a deadlock is
indistinguishable from a slow query.

Kotlin has a tool that expresses exactly this: `@RestrictsSuspension` on the scope receiver. It was the
design's stated plan, and a stricter guarantee than the vendored driver's (which states the rule in a
doc comment and detects the symptom afterwards).

It was applied, and the compiler rejected kidx's own code: *"restricted suspending functions can invoke
member or extension suspending functions only on their restricted coroutine scope."* kidx invokes the
caller's block from inside the driver's transaction lambda — `driver.writeTransaction { WriteScope(this,
names).block() }` — and that is precisely the call the restriction forbids.

## Decision

Drop the annotation. Install a coroutine-context marker for the duration of a scope, and refuse a
nested `read`/`write` immediately with a message naming both store sets and the word *deadlock*.

## Consequences

- The failure mode that hangs is caught, deterministically, with an actionable message.
- A stray `delay` or network call inside a scope is **not** caught. Documented in `AGENTS.md`'s gotchas
  and in SPEC.md decision 5; it is the largest remaining gap between what the design promises and what
  it enforces.
- The alternative — restructuring the plumbing so the caller's block runs outside the driver's lambda —
  means reimplementing the transaction machinery kidx vendored the driver *for* (ADR 0001). Trading that
  risk for a compile-time check over a documented rule is not worth it today. Revisit if the rule turns
  out to be violated in practice rather than in theory.
