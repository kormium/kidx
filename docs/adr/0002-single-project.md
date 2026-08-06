# ADR 0002 — One project, no `-core` module

- Status: Accepted
- Date: 2026-08-07

## Context

The original design split a platform-free `kidx-core` (the DSL) from a `kidx-indexeddb` engine module,
mirroring kormium's core/backend layout, with an internal engine seam between them.

Two things undermined it while the code was being written. `FieldType` converts values to and from
`kotlin.js.JsAny`, so the DSL is *not* platform-free and cannot be: every source set here is a web
source set. And there is exactly one engine, with no second one planned — reimplementing IndexedDB
elsewhere is explicitly rejected in SPEC.md.

That left a module boundary whose only justification had evaporated. A `-core` name, meanwhile, promises
a sibling that does not exist.

## Decision

A single-project Gradle build: the root **is** the library, published as `io.github.kidx:kidx`. The
vendored driver lives in its own source directory rather than its own module.

## Consequences

- Simpler everything: one build file, one ABI dump, task names without a path prefix.
- The engine seam stays internal, as an organizing principle rather than an extension point.
- If a second artifact is ever wanted, the sources move into a subproject with `artifactId = "kidx"`
  pinned so the coordinate does not change. A mechanical move, and cheaper than carrying an empty
  multi-module scaffold until then.
- Change notification, which kormium *does* split off, is built in here — see ADR 0006.
