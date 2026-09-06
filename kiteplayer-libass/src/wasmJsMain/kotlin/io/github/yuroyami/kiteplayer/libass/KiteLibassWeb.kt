@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.libass

import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * The web half's one setup step, and usually not even that.
 *
 * On every other platform libass is linked in. In a browser it is a separate wasm module,
 * `kiteass.mjs` beside `kiteass.wasm`, which the page hosts the way it hosts the codec module. The
 * two files come as the `web` zip attached to this module's wasmJs artifact; unpack them beside
 * the page. The first ASS track a player meets starts loading [DEFAULT_URL] on its own, so a page
 * that serves the two files needs no code here. Until the module arrives the track draws nothing:
 * the engine records every call and replays it the moment the module lands, and typesetting
 * begins there. Only a load that fails hands the track to the built-in styling, with a warning.
 * A page that wants no blank first seconds calls [load] (or [attach] with a module it instantiated
 * itself) before it creates a player; a page that keeps the files elsewhere must.
 *
 * There is no system font on the web. Fonts reach libass as container attachments, through
 * `SubtitleConfig.fonts`, or from the script's own `[Fonts]` section; a track that names a font
 * nobody supplied draws nothing, honestly, rather than guessing.
 */
public object KiteLibassWeb {

    internal var module: JsAny? = null
    internal var loadFailure: Throwable? = null
    private var loading: Boolean = false

    public val isLoaded: Boolean get() = module != null

    /** Where the first ASS track looks for the module when nothing was loaded or attached. */
    public const val DEFAULT_URL: String = "./kiteass.mjs"

    /** Adopts a module the page instantiated itself: `(await import("./kiteass.mjs")).default()`. */
    public fun attach(libassModule: JsAny) {
        val established = module
        if (established != null) {
            if (established === libassModule) return
            throw IllegalStateException("a different libass module is already attached; attach once per page.")
        }
        if (!webIsLibassModule(libassModule)) {
            throw IllegalArgumentException(
                "This is not the libass web module: it lacks _kass_open or HEAPU8. Host the kiteass.mjs " +
                    "and kiteass.wasm that kiteplayer-libass builds, with HEAPU8 among the exported runtime methods.",
            )
        }
        module = libassModule
        loadFailure = null
    }

    /** Fetches and instantiates the module at [url]. Calling twice is a no-op. */
    public suspend fun load(url: String = DEFAULT_URL) {
        if (module != null) return
        val loaded = webImportModule(url).await<JsAny>()
        if (module != null) return
        attach(loaded)
    }

    /** Starts [load] without waiting; the pending engine polls the outcome on its next render. */
    internal fun loadInBackground(url: String = DEFAULT_URL) {
        if (module != null || loading || loadFailure != null) return
        loading = true
        webImportModule(url).then(
            { loaded ->
                loading = false
                runCatching { attach(loaded) }.exceptionOrNull()?.let { loadFailure = it }
                null
            },
            { error ->
                loading = false
                loadFailure = IllegalStateException("kiteass.mjs could not be loaded from $url: ${webErrorMessage(error)}")
                null
            },
        )
    }
}

// `webpackIgnore` matters: a bundler that sees a bare import(url) resolves it at BUILD time and
// answers "Cannot find module" at run time. The module is fetched by the page at run time.
@JsFun("(url) => import(/* webpackIgnore: true */ url).then(m => m.default())")
private external fun webImportModule(url: String): Promise<JsAny>

@JsFun("(m) => !!m && typeof m._kass_open === 'function' && typeof m._kass_render === 'function' && m.HEAPU8 !== undefined")
private external fun webIsLibassModule(module: JsAny): Boolean

@JsFun("(e) => (e && e.message) ? String(e.message) : String(e)")
private external fun webErrorMessage(error: JsAny): String
