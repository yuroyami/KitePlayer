package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val blockingWork: CoroutineDispatcher get() = Dispatchers.Default
