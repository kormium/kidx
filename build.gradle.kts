plugins {
    // Applied (without a version) by the subprojects.
    alias(libs.plugins.kotlin.multiplatform) apply false
    // Validates the public ABI of every published module. An API change requires
    // `./gradlew apiDump` and a review of the .api diffs — same discipline as kormium.
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        // js/wasm ABI, since there is no JVM target here at all.
        enabled = true
    }
}

allprojects {
    repositories {
        mavenCentral()
    }
}
