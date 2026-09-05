plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/*
 * The web stop gate. One page, no player, no codec: a synthetic 1080p
 * yuv420p frame converted to RGBA with the same arithmetic Conversions.kt uses, built into a Skia
 * image and drawn through Compose, timed per frame.
 *
 * An application, not a library: no explicitApi, no ABI dump, nothing published.
 */
// The libass web module is hosted by the page, not inherited from the library: copy the two files
// this build produced beside index.html, where the first ASS track looks for ./kiteass.mjs.
val libassModuleDir = project(":kiteplayer-libass").layout.buildDirectory.dir("kiteass")
tasks.matching { it.name == "wasmJsProcessResources" }.configureEach {
    (this as ProcessResources).from(libassModuleDir)
    project(":kiteplayer-libass").tasks.matching { it.name == "buildLibassWasmModule" }.forEach { dependsOn(it) }
}

kotlin {
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            // The real web backend, not the placeholder.
            implementation(libs.kiteffmpeg)
            // The whole player stack, to prove the web defaults resolve.
            implementation(project(":kiteplayer"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.foundation)
        }
    }
}
