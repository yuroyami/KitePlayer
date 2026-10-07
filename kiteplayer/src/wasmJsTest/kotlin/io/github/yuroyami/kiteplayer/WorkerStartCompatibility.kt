@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("unused")

package io.github.yuroyami.kiteplayer

import kotlin.js.JsAny
import kotlin.reflect.KSuspendFunction4
import kotlin.reflect.KSuspendFunction5

/**
 * Compile-only consumer calls. Starting a real browser worker needs its separately bundled codec
 * and audio context, so this fixture is deliberately not invoked by the protocol unit tests.
 * Existing partial/default/named calls and the explicit wake option must remain resolvable.
 */
private suspend fun workerStartCompatibility(canvas: JsAny?): List<KitePlayerWorker> = listOf(
    KitePlayerWorker.start(canvas),
    KitePlayerWorker.start(canvas, "./worker.mjs"),
    KitePlayerWorker.start(canvas, "./worker.mjs", "./codec.mjs", null),
    KitePlayerWorker.start(canvas = canvas, libassUrl = null),
    KitePlayerWorker.start(canvas, "./worker.mjs", "./codec.mjs", null, false),
    KitePlayerWorker.start(canvas = canvas, keepDisplayAwake = false),
)

/** Exact suspending signatures with a bound companion; no worker, audio context or codec is started. */
private fun workerStartReferenceCompatibility(): List<Any> {
    val original: KSuspendFunction4<JsAny?, String, String, String?, KitePlayerWorker> =
        KitePlayerWorker.Companion::start
    val withWakeOption: KSuspendFunction5<JsAny?, String, String, String?, Boolean, KitePlayerWorker> =
        KitePlayerWorker.Companion::start
    return listOf(original, withWakeOption)
}
