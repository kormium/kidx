# ADR 0001 — Vendor the IndexedDB driver instead of depending on it

- Status: Accepted
- Date: 2026-08-07

## Context

The one genuinely hard part of building on IndexedDB is transaction lifetime. An `IDBTransaction`
auto-commits as soon as the event loop returns without a pending request, so suspending on the wrong
thing inside a transaction closes it under you — and the failure is silent and timing-dependent.

[JuulLabs/indexeddb](https://github.com/JuulLabs/indexeddb) (Apache 2.0) solves it: `Dispatchers.Unconfined`
plus a hard rule enforced by exposing transactional operations only as extensions scoped on a
`Transaction` receiver, plus deliberate serialization of overlapping transactions. It is small
(~1300 lines across `core` and `external`), actively maintained, and targets js and wasmJs from a
`webMain` source set — the same shape kidx wants.

The alternative to writing that ourselves is either a normal Maven dependency or a vendored copy.

## Decision

Vendor `core` and `external` into `src/webMain/vendor`, in the upstream module layout, keeping the
`com.juul.indexeddb` package names. Every modification is recorded in
[`VENDOR.md`](../../src/webMain/vendor/VENDOR.md) with its reason. The transaction-scoping discipline
is off limits.

The vendored top-level declarations are `internal`, so nothing from `com.juul.indexeddb` appears in
kidx's ABI.

## Consequences

- **Upstream fixes must be ported by hand**, including fixes to the very code kidx relies on most. The
  mitigation is the preserved layout: re-checking against a fresh clone is a `diff -r`, and every local
  change is a listed, re-appliable item.
- **Apache-2.0 obligations are kidx's now**: `NOTICE`, retained headers, and a statement of what was
  modified.
- **kidx can change what it needs.** This was not hypothetical — four changes were required within days:
  an in-line key path combined with a key generator (which upstream's API cannot express), `globalThis`
  instead of `self` so the module can even load under Node, introspection properties needed to verify a
  schema, and the visibility pass. As a dependency, three of those would have been blocked on upstream.
- **`internal` reaches across the seam.** Because the vendored code compiles into the same module, kidx
  can use `internal` members of the driver. Convenient, and a coupling to watch.
- Making the driver's exceptions internal forced kidx to translate engine failures into its own types
  (ADR 0003's principle applied to errors): a consumer must be able to catch a duplicate key without
  naming a vendored class.
