package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaEdition
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.SourceEdition
import kotlin.time.Duration.Companion.microseconds

/**
 * The editions of [source] as the caller sees them (#501): each one's length is the sum of the
 * ranges an ordered edition states, or the file's own length for one that is not ordered.
 */
internal fun mediaEditionsOf(source: PlayerMediaSource): List<MediaEdition> =
    source.editions.mapIndexed { index, edition ->
        MediaEdition(
            index = index,
            title = edition.title,
            uid = edition.uid,
            isDefault = edition.isDefault,
            isHidden = edition.isHidden,
            isOrdered = edition.isOrdered,
            duration = if (edition.isOrdered) {
                edition.parts?.sumOf { (it.end.micros - it.start.micros).coerceAtLeast(0L) }?.microseconds
            } else {
                source.duration?.micros?.microseconds
            },
        )
    }

/**
 * The index of the edition that plays from [editions] (#501): [requested] when the media has it,
 * and otherwise the first one the file marks default, or the first one when none is. Null when the
 * media has no editions.
 */
internal fun chosenEdition(editions: List<SourceEdition>, requested: Int?): Int? {
    if (editions.isEmpty()) return null
    if (requested != null && requested in editions.indices) return requested
    return editions.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
}
