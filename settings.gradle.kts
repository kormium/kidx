pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// A single-project build: kidx is one library, so the root *is* the library. There is no
// `kidx-core`, because there is nothing for it to be the core of — one module, one `webMain`
// source set shared by the js and wasmJs targets, one engine.
//
// `kidx-observe` (see SPEC.md, Roadmap) is the first thing that would genuinely be a second module,
// because it is optional for consumers. Adding it means moving this project's sources into a
// subproject and pinning `artifactId = "kidx"` so the published coordinate does not change.
rootProject.name = "kidx"
