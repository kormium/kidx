pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "kidx"

// One module for now. The DSL and the engine live together because the split the spec originally
// sketched (a platform-free `kidx-core` over a `kidx-indexeddb` engine) has no justification: the
// value codec speaks `kotlin.js.JsAny`, so every source set here is a web source set anyway, and
// there is exactly one engine. `kidx-observe` (see SPEC.md, Roadmap) is the module that will
// genuinely be separate, because it is optional for consumers.
include("kidx-core")
