# Architecture decision records

One file per decision that is likely to be revisited, with the context that produced it and the
consequences accepted along with it. The full design and every decision — including the ones settled
enough not to need an ADR — are in [SPEC.md](../../SPEC.md).

| # | Decision | Status |
|---|----------|--------|
| [0001](0001-vendor-the-engine.md) | Vendor the IndexedDB driver instead of depending on it | Accepted |
| [0002](0002-single-project.md) | One project, no `-core` module | Accepted |
| [0003](0003-no-operation-pretends.md) | No operation pretends to be an engine capability | Accepted |
| [0004](0004-runtime-suspend-discipline.md) | Enforce the transaction discipline at runtime, not at compile time | Accepted |
| [0005](0005-verify-schema-on-open.md) | Verify the schema on open instead of journalling migrations | Accepted |
| [0006](0006-observe-built-in.md) | Change notification is built in | Accepted |
