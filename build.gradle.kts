plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Validates the public ABI. An API change requires `./gradlew apiDump` and a review of the
    // .api diff — same discipline as kormium.
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.maven.publish)
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
        nodejs {
            testTask { useMocha { timeout = "30s" } }
        }
        browser {
            testTask {
                // No sandbox: the usual state of a container or WSL, where Chromium's sandbox cannot
                // start. The browser only loads a local test bundle here.
                useKarma { useChromeHeadlessNoSandbox() }
                useMocha { timeout = "30s" }
            }
        }
    }

    @Suppress("OPT_IN_USAGE")
    wasmJs {
        browser {
            testTask {
                useKarma { useChromeHeadlessNoSandbox() }
                // A real engine is slower than fake-indexeddb: opening and deleting a database per test
                // overruns mocha's 2s default, which showed up as one flaky timeout rather than an
                // honest failure.
                useMocha { timeout = "30s" }
            }
        }
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
            // Karma needs a browser binary and CI images rarely have one. puppeteer downloads a pinned
            // Chromium in its postinstall — which is why install scripts are re-enabled below.
            implementation(devNpm("puppeteer", "^23.11.1"))
        }
    }
}

// Browser tests are the only place the real engine can be exercised (SPEC.md, "Testing"), but they need
// a browser binary that most machines and the default CI image do not have — without one Karma fails and
// takes `check` with it. Off unless asked for; the CI workflow that installs a browser passes the flag.
// Kotlin's yarn install runs with --ignore-scripts, which stops puppeteer from fetching its Chromium.
//
// SUPPLY-CHAIN NOTE: this re-enables arbitrary postinstall scripts for every js dependency, so a
// compromised (transitive) package could run code at install time. The exposure is bounded — versions are
// pinned and the resolved tree is committed in kotlin-js-store/yarn.lock, so installs are reproducible and
// auditable — but review lockfile changes when bumping a test dependency.
// The wasm target defaults to a newer Node than the js one, and that build links against libatomic —
// absent from plenty of slim Linux images, where it fails as an opaque exit 127 during `yarn`. Pin both
// to the version the js target already downloads.
plugins.withType(org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin::class.java) {
    the<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().version.set("24.10.0")
}

plugins.withType(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin::class.java) {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec>().ignoreScripts.set(false)
}

val browserTestsEnabled = providers.gradleProperty("enableBrowserTests").orNull.toBoolean()

/**
 * Karma finds a browser through `CHROME_BIN`. Prefer whatever the environment already has; otherwise use
 * the Chromium puppeteer downloaded, so a machine (or CI image) with no browser can still run these.
 */
val chromeBinary: String? = System.getenv("CHROME_BIN")
    ?: providers.gradleProperty("chromeBin").orNull
    ?: file("${System.getProperty("user.home")}/.cache/puppeteer/chrome")
        .listFiles()
        ?.sortedBy { it.name }
        ?.lastOrNull()
        ?.let { version -> File(version, "chrome-linux64/chrome").takeIf { it.canExecute() }?.absolutePath }

tasks.matching { it.name in setOf("jsBrowserTest", "wasmJsBrowserTest") }.configureEach {
    onlyIf("browser tests need -PenableBrowserTests=true and a browser binary") {
        browserTestsEnabled && chromeBinary != null
    }
}

// Reported once, because "browser tests silently did not run" is the failure mode this whole gate risks.
if (browserTestsEnabled && chromeBinary == null) {
    logger.warn("Browser tests were requested but no browser was found: set CHROME_BIN or -PchromeBin.")
}

mavenPublishing {
    publishToMavenCentral()

    // Release signing comes from ~/.gradle/gradle.properties or CI env (ORG_GRADLE_PROJECT_*):
    // signingInMemoryKey / signingInMemoryKeyPassword. Guarded so a local build without keys works.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("kidx")
        description.set("Typed Kotlin storage over IndexedDB: schema, rows, indexed queries, migrations.")
        url.set("https://github.com/kormium/kidx")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("sknyazev")
                name.set("Sergey Knyazev")
            }
        }
        scm {
            url.set("https://github.com/kormium/kidx")
            connection.set("scm:git:git://github.com/kormium/kidx.git")
            developerConnection.set("scm:git:ssh://git@github.com/kormium/kidx.git")
        }
    }
}
