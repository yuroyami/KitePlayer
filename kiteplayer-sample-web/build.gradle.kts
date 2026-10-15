plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/*
 * The web sample. By default one page plays ./clip.mp4 through KiteVideo with the library's Compose
 * controls over the picture and reports what each frame costs (#125). With ?probe it is the web stop
 * gate instead: a synthetic 1080p yuv420p frame converted to RGBA with the same arithmetic
 * Conversions.kt uses, built into a Skia image and drawn through Compose, and the same bytes
 * uploaded into Compose's WebGL context, timed per frame.
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

// The codec module comes from the kiteffmpeg web zip, the one a web app unpacks beside its page, and
// the clip is the 1080p house clip when scripts/testmedia.sh has made it; without it the page says so.
val kiteffmpegWebZip = configurations.create("kiteffmpegWebZip") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    kiteffmpegWebZip("io.github.yuroyami:kiteffmpeg-wasm-js:${libs.versions.kiteffmpeg.get()}:web@zip")
}
val unpackKiteFFmpegWebModule =
    tasks.register<io.github.yuroyami.kiteplayer.buildtools.UnpackZipsTask>("unpackKiteFFmpegWebModule") {
        archives.from(kiteffmpegWebZip)
        outputDir.set(layout.buildDirectory.dir("kiteffmpeg-web"))
    }
val houseClip = rootProject.layout.projectDirectory.dir("testmedia")
tasks.matching { it.name == "wasmJsProcessResources" }.configureEach {
    (this as ProcessResources).from(unpackKiteFFmpegWebModule)
    from(houseClip) {
        include("sync1080p30.mp4")
        rename("sync1080p30\\.mp4", "clip.mp4")
    }
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
            // The whole player stack, to prove the web defaults resolve, and the complete Compose entry
            // point, to prove it resolves for wasmJs too (#125).
            implementation(project(":kiteplayer"))
            implementation(project(":kiteplayer-compose"))
            // KiteVideo itself, which kiteplayer-compose uses behind KitePlayerVideo and does not re-export.
            implementation(project(":kiteplayer-compose-video"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.foundation)
        }
    }
}
