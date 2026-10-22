package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal actual val blockingWork: CoroutineDispatcher get() = Dispatchers.IO
