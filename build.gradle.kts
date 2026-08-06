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
    }
}
