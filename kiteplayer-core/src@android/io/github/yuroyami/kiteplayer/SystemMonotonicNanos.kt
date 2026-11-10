package io.github.yuroyami.kiteplayer

/** The time base `android` audio sinks date on: `System.nanoTime()` (#829). */
internal actual fun systemMonotonicNanos(): Long = System.nanoTime()
