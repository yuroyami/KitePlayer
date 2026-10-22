@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("unused")

package io.github.yuroyami.kiteplayer.mobile

import kotlin.js.JsAny
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2

/** Compile-only exact constructor references; neither is invoked, so no renderer is created. */
private fun webCanvasFactoryReferenceCompatibility(): List<Any> {
    val original: KFunction1<JsAny, WebCanvasRendererFactory> = ::WebCanvasRendererFactory
    val withWakeOption: KFunction2<JsAny, Boolean, WebCanvasRendererFactory> = ::WebCanvasRendererFactory
    return listOf(original, withWakeOption)
}
