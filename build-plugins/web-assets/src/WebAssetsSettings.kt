/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build.web

import org.jetbrains.amper.plugins.Configurable

/** The files that one web module needs beside its page or its tests. */
@Configurable
interface WebAssetsSettings {
    /** The files for the page, written into `resources/` of an app module. */
    val page: WebAssetSet

    /** The files for the browser tests, written into `testResources@wasmJs/` of a library module. */
    val tests: WebAssetSet
}

/** One set of web files. Each switch adds files to the folder. */
@Configurable
interface WebAssetSet {
    /** `kite.mjs` and `kite.wasm` from the KiteFFmpeg web zip of the catalog version, plus its licenses. */
    val codec: Boolean get() = false

    /** `kiteass.mjs` and `kiteass.wasm`, linked with emscripten from the wasm32 libass chain. */
    val libass: Boolean get() = false

    /** `testmedia/sync1080p30.mp4` as `clip.mp4`. `scripts/testmedia.sh` makes the source file. */
    val clip: Boolean get() = false
}
