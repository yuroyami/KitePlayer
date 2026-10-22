package io.github.yuroyami.kiteplayer.output

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Returns once [ready] holds, or after two seconds, and the assertion after it decides. A machine
 * with no audio hardware can call back late, so a fixed sleep races the device.
 */
internal suspend fun awaitDevice(ready: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + 2.seconds
    while (!ready() && deadline.hasNotPassedNow()) delay(10)
}
