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
kotlin {
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            // The real web backend, not the placeholder.
            implementation(libs.kiteffmpeg.core)
            // The whole player stack, to prove the web defaults resolve.
            implementation(project(":kiteplayer-mobile"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.foundation)
        }
    }
}
