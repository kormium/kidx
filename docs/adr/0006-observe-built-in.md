# ADR 0006 — Change notification is built in

- Status: Accepted
- Date: 2026-08-07

## Context

IndexedDB has no change events. None: not per store, not per record, not per database. Every reactive
query in every IndexedDB library is that library's own bookkeeping.

kormium splits its equivalent into `kormium-observe`, so the natural move was a `kidx-observe` module.
But kormium's split exists to keep coroutines and `Flow` out of `kormium-core`; the seam itself
(`WriteListener`, `WriteListeners`, `NotificationTransport`) lives in core there too.

Here every operation is already `suspend` and `Flow` is already public API through `stream()`. And only
`WriteScope` can know which stores a transaction wrote, so an "optional" module would have forced a
public hook into the library anyway.

## Decision

Build it in. `WriteScope` collects the stores it wrote; they are fired at `WriteListeners` after the
transaction completes; `Store.observe(db)` turns that into a conflated `Flow`; `NotificationTransport`
carries the same signals between contexts, with a shipped `BroadcastChannelTransport`.

## Consequences

- Nothing currently wants to be a second module, which is what ADR 0002 rests on.
- "Emulates nothing" had to be restated as "nothing pretends" (ADR 0003) — notification is openly a kidx
  layer, and misrepresents nothing.
- Two costs are documented rather than hidden: invalidation is per store, not per key, so writing one
  row re-runs every query watching it; and the re-fetch plus hydration happens wherever the flow is
  collected, typically the main thread, where kormium's equivalent cannot block a UI.
- Inbound remote signals are delivered locally but never re-published, or two contexts echo forever. A
  signal naming a store this schema does not have is ignored, because the other context may be running a
  different version of the code.
