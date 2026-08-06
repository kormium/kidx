pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// A single-project build: kidx is one library, so the root *is* the library. There is no
// `kidx-core`, because there is nothing for it to be the core of — one module, one `webMain`
// source set shared by the js and wasmJs targets, one engine, and change notification built in
// rather than split off (SPEC.md decision 15).
//
// If something ever does want to be a second module, this project's sources move into a subproject
// with `artifactId = "kidx"` pinned so the published coordinate does not change.
rootProject.name = "kidx"
