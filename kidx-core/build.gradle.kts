plugins {
    kotlin("multiplatform")
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
