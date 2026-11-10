package io.github.yuroyami.kiteplayer

/** No shipped audio sink here, so nanoseconds since the first reading (#829). */
internal actual fun systemMonotonicNanos(): Long = FirstReadingClock.nanos()
