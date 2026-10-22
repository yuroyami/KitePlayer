@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.webgl.rememberWebGLRenderTarget
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.combine

/**
 * [KiteVideo] on the web (#125): the picture is drawn into a WebGL render target the size of this
 * node, in Compose's own context, and Compose draws that target as ordinary content.
 *
 * The target's painter scales by nearest neighbour, so the target is kept at the node's size in
 * device pixels and the scaling happens in [WebGlVideoPass], where it can be bilinear or
 * Catmull-Rom. The painter is drawn at one to one, which nearest neighbour draws exactly.
 *
 * Nothing runs per frame unless something changed: the node waits for the renderer's next change
 * or a new size, then draws once on Compose's next frame. A paused picture costs nothing, and a
 * resize redraws it from the texture the node keeps.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun PlatformKiteVideo(state: KiteVideoState, modifier: Modifier) {
    val web = state.web
    var pixelSize by remember { mutableStateOf(IntSize.Zero) }
    val target = rememberWebGLRenderTarget(if (pixelSize == IntSize.Zero) IntSize(1, 1) else pixelSize)
    val viewportOwner = remember(state) { Any() }
    DisposableEffect(state, viewportOwner) {
        onDispose { state.removeViewport(viewportOwner) }
    }
    // Disposed before the target: effects are disposed in reverse order, and the target registered
    // its own disposal first, so the target is still alive when the pass deletes its GL objects.
    val pass = remember(target) { target?.let { WebGlVideoPass(it.webGLContext) } }
    DisposableEffect(pass) {
        onDispose {
            if (pass != null) {
                pass.release()
                target?.markGLStateStale()
            }
        }
    }
    LaunchedEffect(web, target, pass) {
        when {
            target == null -> web.nodeFailed("KiteVideo needs WebGL2 on the web and this page has none")
            pass?.failure != null -> web.nodeFailed(pass.failure)
            pass != null -> combine(web.changes, snapshotFlow { pixelSize }) { change, size -> change to size }
                .collect {
                    // render() answers false until Compose has drawn its first frame and made its
                    // GL context; the next display frame tries again.
                    while (true) {
                        val drawn = withFrameNanos {
                            val picture = web.stage()
                            var uploadMillis = Double.NaN
                            val rendered = target.render {
                                uploadMillis = pass.draw(target.size.width, target.size.height, picture, web.overlay, web.look)
                            }
                            if (picture != null) web.uploaded(picture.generation, uploadMillis)
                            rendered
                        }
                        if (drawn) break
                    }
                }
        }
    }
    Box(
        modifier
            .onSizeChanged { measured ->
                // Device pixels, as Compose lays out; the target follows on the next composition.
                pixelSize = measured
                state.updateViewport(viewportOwner, measured.width, measured.height)
            }
            .drawBehind {
                if (target != null) with(target.painter) { draw(size) }
            },
    )
}
