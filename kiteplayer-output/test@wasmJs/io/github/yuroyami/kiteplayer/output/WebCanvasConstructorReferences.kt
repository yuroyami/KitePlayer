@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("unused")

package io.github.yuroyami.kiteplayer.output

import kotlin.js.JsAny
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3

/**
 * Compile-only references to both exact constructor arities. The reflective function types
 * require the declarations themselves, not a function adapted by supplying a default argument.
 * The references are never invoked: no renderer, factory or canvas is constructed here.
 */
private fun webCanvasConstructorReferenceCompatibility(): List<Any> {
    val originalRenderer: KFunction2<JsAny, WebFramePainter, WebCanvasVideoRenderer> =
        ::WebCanvasVideoRenderer
    val rendererWithWakeOption: KFunction3<JsAny, WebFramePainter, Boolean, WebCanvasVideoRenderer> =
        ::WebCanvasVideoRenderer
    val originalFactory: KFunction2<JsAny, WebFramePainter, WebCanvasVideoRendererFactory> =
        ::WebCanvasVideoRendererFactory
    val factoryWithWakeOption: KFunction3<JsAny, WebFramePainter, Boolean, WebCanvasVideoRendererFactory> =
        ::WebCanvasVideoRendererFactory
    return listOf(originalRenderer, rendererWithWakeOption, originalFactory, factoryWithWakeOption)
}
