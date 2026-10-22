package io.github.yuroyami.kiteplayer

/**
 * What a Java app hears from a player (#394), through [KitePlayerJava.addListener].
 *
 * Every method has an empty default, so a listener overrides only what it wants. Each is called on
 * the executor the listener was added with, one call at a time and in order for each method. The
 * order between two methods is the order in which the player published the two things, as seen
 * by two Kotlin collectors.
 *
 * [onState] and [onProgress] are called first with the current values, and then whenever they
 * change. A listener that falls behind gets the latest state and progress, not every one in
 * between, as a Kotlin collector of `state` or `progress` does. Events and warnings are never
 * skipped: each one that happens after [KitePlayerJava.addListener] returns reaches the listener,
 * and none that happened before it does.
 */
public interface KitePlayerListener {

    /** The player's state, as `KitePlayer.state` publishes it. */
    public fun onState(state: PlayerSnapshot) {}

    /** The position and how far ahead the player has read, as `KitePlayer.progress` publishes them. */
    public fun onProgress(progress: Progress) {}

    /** Something happened, as `KitePlayer.events` publishes it. Warnings go to [onWarning] instead. */
    public fun onEvent(event: PlayerEvent) {}

    /** Something went wrong and the player carried on: the warning of each `PlayerEvent.Warning`. */
    public fun onWarning(warning: PlaybackWarning) {}
}
