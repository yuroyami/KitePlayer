package consumer

import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.audioviz.AudioVizState
import io.github.yuroyami.kiteplayer.compose.KiteRenderPath

/**
 * One public symbol from each install line, so the consumer compiles against all three. The
 * Compose one is from `kiteplayer-compose-ui`, which `kiteplayer-compose` exports; `KiteVideoState`
 * is in `kiteplayer-compose-video`, which an app adds itself, so it is not on this compile path (#779).
 */
fun reachedSymbols(): List<String> = listOf(
    KitePlayerPlatform::class.simpleName.orEmpty(),
    KiteRenderPath::class.simpleName.orEmpty(),
    AudioVizState::class.simpleName.orEmpty(),
)
