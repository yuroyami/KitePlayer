plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/*
 * The KV-6 stop gate for S6 (17.14 X-01). One page, no player, no codec: a synthetic 1080p
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
            // The real web backend (17.14 X-07), not the placeholder.
            implementation("io.github.yuroyami:kitecodec-core:0.0.9")
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.foundation)
        }
    }
}
