# Vendored: JuulLabs/indexeddb

Apache License 2.0 (see `LICENSE.txt` in this directory). Upstream:
<https://github.com/JuulLabs/indexeddb>.

Why this is vendored rather than depended on, and what that costs, is SPEC.md decision 4. The short
version: kidx's own guarantees rest on this code's transaction-lifetime discipline, so kidx owns it —
and in exchange must port upstream fixes by hand.

## Provenance

| | |
|---|---|
| Revision | `3ca71d4a0b2056df7ae4f0a3b33798e1b460ae1e` (2026-07-15) |
| Built against | Kotlin 2.4.10, kotlinx-coroutines 1.11.0 |
| Upstream modules taken | `core`, `external` |
| Upstream modules skipped | `logging-khronicle` (optional third-party logger) |

`core/` and `external/` here mirror the upstream module layout exactly, so re-checking against a
fresh clone is a `diff -r`:

```sh
git clone --depth 1 https://github.com/JuulLabs/indexeddb.git /tmp/idb
diff -r /tmp/idb/core/src/webMain/kotlin     src/webMain/vendor/core
diff -r /tmp/idb/external/src/webMain/kotlin src/webMain/vendor/external
```

## Modifications

**None yet.** The sources are byte-identical to the upstream revision above.

Every future change goes in the table below, with the reason, so that porting an upstream update is a
matter of re-applying a known list rather than re-deriving it. Do not modify the transaction-scoping
discipline (`Transaction`/`WriteTransaction` receivers, `Dispatchers.Unconfined`, the
`awaitTransaction` serialization) — that is the part kidx vendored this code *for*.

| File | Change | Why |
|---|---|---|
| — | — | — |

### Changes already known to be needed

Findings from reading this code against SPEC.md. Each is a real gap between what kidx's design needs
and what upstream exposes; none is applied yet.

1. **`createObjectStore` cannot combine a key path with a key generator.**
   `Database.createObjectStore(name, keyPath)` and `Database.createObjectStore(name, autoIncrement)`
   are separate overloads, and `AutoIncrement.toOptions()` sets only `autoIncrement = true`. IndexedDB
   allows both together (an in-line key *and* a generator), and SPEC.md decisions 9 and 12 require
   exactly that combination: the generated key must land inside the record. Needs a third overload
   passing both options. `IDBCreateObjectStoreOptions` already declares both fields, so this is
   additive.

2. **A failed request aborts the whole transaction, with no way to opt out.**
   `Transaction.request()` turns an `error` event into `ErrorEventException` but never calls
   `preventDefault()` on it, so per the IndexedDB specification the event bubbles to the transaction
   and aborts it. This confirms the Roadmap's suspicion about insert-or-ignore: `add` plus catching a
   `ConstraintError` cannot work, because by the time the exception is caught the transaction is
   already doomed. If insert-or-ignore is ever added, it needs either a `preventDefault()`-ing request
   variant here or a `get`-then-`add` implementation. It also confirms SPEC.md decision 6: one failed
   write discards every other write in the same scope.

3. **`blocked` fails fast instead of waiting.** `openDatabase` throws `OpenBlockedException` on the
   `blocked` event, and `deleteDatabase` throws `ErrorEventException`. IndexedDB itself does not
   cancel the request — `success` still fires if the other connection closes — so upstream is
   deliberately choosing fail-fast over waiting. SPEC.md decision 14 assumes the opposite (wait, and
   report via `onBlocked`). One of the two has to change.

4. **IndexedDB being unavailable surfaces as `IllegalStateException`.**
   `checkNotNull(indexedDB) { "Your browser doesn't support IndexedDB." }` — Firefox private browsing
   is the common case. kidx wants a typed, named failure here (SPEC.md, "Browser support"), which can
   be done in kidx's own wrapper rather than by changing this code.

5. **No `@RestrictsSuspension`.** The rule "do not call any suspend function except those scoped on
   `Transaction`" is a doc comment on `openDatabase` and on `Transaction.openCursor`. Nothing enforces
   it; `openCursorImpl` merely detects the symptom after the fact
   (`IllegalStateException("Send failed. Did you suspend illegally?")`). kidx can enforce it on its own
   scope types instead, so this needs no upstream change — see SPEC.md decision 5.

6. **`count` returns `Int`.** kidx's `count()` is `Long`, following kormium. A conversion in kidx, not
   a change here.
