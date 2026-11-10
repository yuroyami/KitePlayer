package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.runtime.Composable

/**
 * Whether the system asks apps to reduce motion, read once where [rememberAudioVizState] makes its
 * state (#964): Reduce Motion on iOS, and on Android the animator duration scale, which Remove
 * animations sets to zero. The desktop JVM has no setting it can read for every system, so false.
 */
@Composable
internal expect fun systemReducesMotion(): Boolean

/** True for an animator duration scale of zero, which is what Android's Remove animations sets. */
internal fun animationsRemoved(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f
