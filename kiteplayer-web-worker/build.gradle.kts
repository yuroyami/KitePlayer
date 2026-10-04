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
            // The worker reads every address through its own synchronous resolver, and a configured
            // resolver always wins, so the automatic network transport that :kiteplayer brings to a
            // page is never asked here. Linked, its registration keeps Ktor alive: a quarter of the
            // binary, 169,962 bytes after gzip (#519). scripts/check-web-size.sh notices its return.
            implementation(project(":kiteplayer")) {
                exclude(group = "io.github.yuroyami", module = "kiteplayer-network")
            }
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The browser test drives a real worker, so the page needs the worker binary, the codec module and
// the libass module served beside it. karma.config.d/worker.js serves the development binary from
// the directory this sync fills, and the codec module from the kiteffmpeg web zip, which this
// module unpacks itself.
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
// The libass module comes from :kiteplayer-libass's build directory, where processing its wasmJs
// resources links it with emscripten. Without emcc that task links nothing and the libass test fails.
tasks.named { it == "wasmJsBrowserTest" }.configureEach {
    dependsOn(unpackKiteFFmpegWebModule, "wasmJsDevelopmentExecutableCompileSync", ":kiteplayer-libass:wasmJsProcessResources")
}
