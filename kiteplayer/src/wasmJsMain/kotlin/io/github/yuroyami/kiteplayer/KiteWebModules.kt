@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

/**
 * Which codec module a page may load (#58).
 *
 * A multi-threaded build of the codec module needs `SharedArrayBuffer`, which a browser gives
 * only to a page that is cross-origin isolated: one served with
 * `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp`.
 * Imported on a page without them, such a module hangs rather than failing, and the page never
 * finishes loading with nothing in the console. So the choice is made here, before the import:
 *
 * ```kotlin
 * KiteFFmpegWeb.load(KiteWebModules.codecModuleUrl(threaded = "./kite-mt.mjs"))
 * ```
 *
 * KiteFFmpeg publishes only the single-threaded module today, which needs no headers and which
 * every page gets from [codecModuleUrl] with no threaded module named. A worker is isolated
 * exactly when the page that started it is, so `KitePlayerWorker.start` takes the same answer as
 * its `codecUrl`.
 */
public object KiteWebModules {

    /**
     * True when this page, or the worker this runs in, is cross-origin isolated and may use
     * `SharedArrayBuffer`. False in a runtime with no such notion, such as Node.
     */
    public val isCrossOriginIsolated: Boolean
        get() = webCrossOriginIsolated()

    /**
     * [threaded] when there is one and this page is cross-origin isolated, and [singleThreaded]
     * otherwise. Ask before the import: it is the import of a threaded module on a page that is not
     * isolated that hangs.
     */
    public fun codecModuleUrl(singleThreaded: String = "./kite.mjs", threaded: String? = null): String =
        chooseCodecModule(isCrossOriginIsolated, singleThreaded, threaded)
}

/** The choice of [KiteWebModules.codecModuleUrl], for a page whose isolation is [isolated]. */
internal fun chooseCodecModule(isolated: Boolean, singleThreaded: String, threaded: String?): String =
    if (threaded != null && isolated) threaded else singleThreaded

@JsFun("() => globalThis.crossOriginIsolated === true && typeof SharedArrayBuffer === 'function'")
private external fun webCrossOriginIsolated(): Boolean
