package io.github.yuroyami.kiteplayer.sample.web

import io.github.yuroyami.kitecodec.wasm.ffkmp_averror_eof
import io.github.yuroyami.kitecodec.wasm.ffkmp_filter_exists
import io.github.yuroyami.kitecodec.wasm.ffkmp_frame_alloc
import io.github.yuroyami.kitecodec.wasm.ffkmp_frame_free
import io.github.yuroyami.kitecodec.wasm.ffkmp_frame_pts
import io.github.yuroyami.kitecodec.wasm.ffkmp_frame_set_width
import io.github.yuroyami.kitecodec.wasm.ffkmp_frame_width
import io.github.yuroyami.kitecodec.wasm.ffkmp_media_type_audio
import io.github.yuroyami.kitecodec.wasm.ffkmp_media_type_video
import io.github.yuroyami.kitecodec.wasm.kc_ffmpeg_configuration
import kotlin.js.JsAny

/**
 * Proves the GENERATED binding is callable FROM KOTLIN, not merely that it compiles (17.14 X-05).
 *
 * The 196 externals compiling says the types are well formed. It says nothing about whether a call
 * reaches the codec, which is a different question: Kotlin/Wasm and the emscripten module are two
 * wasm modules with separate memories, and every call crosses through JS.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => globalThis.__kite ?? null")
private external fun kiteModuleOrNull(): JsAny?

/** Decodes a C string at [pointer] using the emscripten module's own UTF-8 reader. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, p) => m.UTF8ToString(p)")
private external fun utf8(module: JsAny, pointer: Int): String

/**
 * Waits for the codec module, which a deferred module script publishes after this bundle starts.
 * Bounded rather than open ended: a missing module must report itself, not hang the page.
 */
internal suspend fun runBindingProof(report: (String) -> Unit) {
    var m = kiteModuleOrNull()
    var waited = 0
    while (m == null && waited < 5000) {
        kotlinx.coroutines.delay(50)
        waited += 50
        m = kiteModuleOrNull()
    }
    if (m == null) {
        report("binding: codec module never loaded after ${waited}ms")
        return
    }
    val lines = mutableListOf<String>()

    val config = utf8(m, kc_ffmpeg_configuration(m))
    lines += if ("wasm32" in config) "config OK" else "config WRONG: ${config.take(40)}"

    val eof = ffkmp_averror_eof(m)
    lines += if (eof < 0) "averror_eof $eof" else "averror_eof WRONG $eof"

    // Round trip through a real object: alloc, set, read back, free.
    val frame = ffkmp_frame_alloc(m)
    lines += if (frame != 0) "frame alloc OK" else "frame alloc FAILED"
    ffkmp_frame_set_width(m, frame, 1920)
    val width = ffkmp_frame_width(m, frame)
    lines += if (width == 1920) "width round trip OK" else "width WRONG $width"

    // The 64-bit path, which is the one that would silently truncate if the type mapping were
    // wrong. A fresh frame's pts is AV_NOPTS_VALUE, the most negative Long there is.
    val pts: Long = ffkmp_frame_pts(m, frame)
    lines += if (pts == Long.MIN_VALUE) "int64 OK (AV_NOPTS_VALUE)" else "int64 WRONG $pts"
    ffkmp_frame_free(m, frame)

    lines += if (ffkmp_filter_exists(m, 0) == 0) "null name refused" else "null name accepted"
    lines += if (ffkmp_media_type_video(m) != ffkmp_media_type_audio(m)) {
        "media constants distinct"
    } else {
        "media constants COLLIDE"
    }
    report("binding: " + lines.joinToString(" | "))
}
