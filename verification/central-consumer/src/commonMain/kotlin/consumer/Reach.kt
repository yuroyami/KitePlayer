package consumer

import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.audioviz.AudioVizState
import io.github.yuroyami.kiteplayer.compose.KiteVideoState

/** One public symbol from each install line, so the consumer compiles against all three. */
fun reachedSymbols(): List<String> = listOf(
    KitePlayerPlatform::class.simpleName.orEmpty(),
    KiteVideoState::class.simpleName.orEmpty(),
    AudioVizState::class.simpleName.orEmpty(),
)
