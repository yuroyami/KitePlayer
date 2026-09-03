package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.FilterChain
import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi
import io.github.yuroyami.kiteplayer.MediaItem

/**
 * The typed route to [MediaItem.videoFilter]: a chain built with KiteFFmpeg's `videoFilters { }`
 * lands on the item as the string the graph builder reads. No opt-in needed, because the DSL is
 * not raw FFmpeg syntax; the raw field stays behind [KitePlayerLowLevelApi] for chains the typed
 * set lacks.
 */
@OptIn(KitePlayerLowLevelApi::class)
public fun MediaItem.withVideoFilter(chain: FilterChain): MediaItem = copy(videoFilter = chain.compile())
