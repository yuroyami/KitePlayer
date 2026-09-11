package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.PlayerSnapshot

/**
 * True when the media has sound and no picture of its own, which is when to show the visualiser.
 *
 * Album art does not count. The player selects it as the video track when a file has nothing else,
 * so a test on `videoSize` would call a song with a cover a film.
 */
public val PlayerSnapshot.isAudioOnly: Boolean
    get() = tracks.audio.isNotEmpty() && tracks.video.none { !it.isCoverArt }
