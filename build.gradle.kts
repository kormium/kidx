plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Validates the public ABI. An API change requires `./gradlew apiDump` and a review of the
    // .api diff — same discipline as kormium.
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        // The js/wasm ABI, since there is no JVM target here at all.
        enabled = true
    }
}

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()

    js {
        // Node runs the test suite: it needs no browser binary (the Kotlin plugin fetches its own
        // Node), and IndexedDB itself comes from `fake-indexeddb`. Browser tests are the only place
        // the real engine can be exercised, so they exist too — gated on a browser being installed,
        // the way kormium gates its iOS-simulator tests.
        nodejs()
        browser()
    }

    @Suppress("OPT_IN_USAGE")
    wasmJs {
        browser()
    }

    sourceSets {
        all {
            // `kotlin.js.JsAny` and friends: the boundary every FieldType crosses.
            compilerOptions.optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }

        webMain {
            // Vendored JuulLabs/indexeddb sources, kept in the upstream module layout so that a
            // `diff -r` against a fresh checkout stays readable. See src/webMain/vendor/VENDOR.md.
            kotlin.srcDir("src/webMain/vendor/core")
            kotlin.srcDir("src/webMain/vendor/external")

            dependencies {
                api(libs.coroutines.core)
            }
        }

        webTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }

        // Engine-level tests are js-only: they need a working IndexedDB, and outside a browser the
        // only one available is `fake-indexeddb` under Node. The wasmJs side of the same behaviour can
        // only be covered by browser tests. Everything that needs no database lives in `webTest` and
        // runs on both targets.
        jsTest.dependencies {
            implementation(npm("fake-indexeddb", "^6.0.0"))
        }
    }
}
