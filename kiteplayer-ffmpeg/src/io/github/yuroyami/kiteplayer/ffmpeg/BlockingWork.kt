package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where calls that block their thread inside FFmpeg run, off the caller's thread: the shared IO
 * pool on a threaded target, and the one thread on the web.
 */
internal expect val blockingWork: CoroutineDispatcher
