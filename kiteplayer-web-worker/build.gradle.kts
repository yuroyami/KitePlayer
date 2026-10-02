plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/*
 * The worker binary that `KitePlayerWorker.start` loads (#100): a second wasm executable whose
 * `main` only calls `runKitePlayerWorker()`. Everything it runs lives in `:kiteplayer`; this module
 * exists because a worker is its own program, with its own module file beside the page's.
 *
 * An application, not a library: no explicitApi, no ABI dump, nothing published. Its packaging
 * into the web zip stays with #58.
 */
kotlin {
    wasmJs {
        // The name the page loads it by, kiteplayer-web-worker.mjs, rather than one prefixed with
        // the root project's name.
        outputModuleName.set("kiteplayer-web-worker")
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":kiteplayer"))
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The browser test drives a real worker, so the page needs the worker binary and the codec module
// served beside it. karma.config.d/worker.js serves the development binary from the directory this
// sync fills, and the codec module from the kiteffmpeg web zip, which this module unpacks itself.
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
tasks.named { it == "wasmJsBrowserTest" }.configureEach {
    dependsOn(unpackKiteFFmpegWebModule, "wasmJsDevelopmentExecutableCompileSync")
}
