@file:JvmName("KitePlayers")

package io.github.yuroyami.kiteplayer

import kotlin.jvm.JvmName

/**
 * Builds a player on this platform's default stack: FFmpeg for the media, and the platform's own
 * audio output and clock.
 *
 * ```kotlin
 * val player = KitePlayer()
 * player.open(MediaItem("https://example.com/movie.mkv"))
 * player.play()
 * ```
 *
 * A backend that [PlayerConfig.backends] names is used as given, and only a missing one comes from
 * the defaults. [KitePlayer.create] is the door that uses only what the config names, for a stack
 * of your own.
 *
 * With the default media backend, this also gives the platform's player views their default
 * renderer, so `view.player = player` is all a `KitePlayerView` needs.
 *
 * Android, iOS, macOS and the desktop JVM always have the default stack in a correctly packaged
 * app. The web has it once its codec module is loaded. Where that can be false, check
 * `KitePlayer.isAvailable` first, for example to fall back to another engine.
 *
 * @throws PlaybackException with [PlaybackError.ConfigurationInvalid] when a backend is missing
 *         and this platform has no default for it. The detail says why.
 */
@Throws(PlaybackException::class)
public fun KitePlayer(config: PlayerConfig = PlayerConfig()): KitePlayer {
    val supplied = config.backends
    if (supplied.backend != null && supplied.output != null) return KitePlayer.create(config)
    val defaults = platformKitePlayerDefaults.backendsOrNull()
        ?: throw PlaybackException(PlaybackError.ConfigurationInvalid(noDefaultStackDetail()))
    val player = KitePlayer.create(
        config.copy(
            backends = Backends(
                backend = supplied.backend ?: defaults.backend,
                output = supplied.output ?: defaults.output,
            ),
        ),
    )
    // The default renderer shows the frames of the default media backend, so it comes with that.
    if (supplied.backend == null) platformKitePlayerDefaults.installViewDefaults()
    return player
}

/**
 * [KitePlayer] with its config built in a block, so a nested setting does not need its type named:
 *
 * ```kotlin
 * val player = KitePlayer { subtitles { preferredLanguages = listOf("ja") } }
 * ```
 *
 * @throws PlaybackException as `KitePlayer(config)` does.
 */
@Throws(PlaybackException::class)
public fun KitePlayer(build: PlayerConfigBuilder.() -> Unit): KitePlayer = KitePlayer(PlayerConfig(build))

/** Whether [KitePlayer] can build a player with this platform's default stack, and why not when it cannot. */
public val KitePlayer.Companion.availability: KitePlayerAvailability
    get() = platformKitePlayerDefaults.availability

/** True when [KitePlayer] can build a player with this platform's default stack. */
public val KitePlayer.Companion.isAvailable: Boolean
    get() = platformKitePlayerDefaults.availability.isAvailable

/**
 * Whether this platform can put a player in a picture-in-picture window at all.
 *
 * iOS, macOS, the desktop JVM and the web answer the question itself. On Android the answer needs a
 * `Context`, so this is only a floor there: it says that a player can be built. Ask
 * `KitePlayer.supportsPictureInPicture(context)` on Android instead.
 */
public val KitePlayer.Companion.supportsPictureInPicture: Boolean
    get() = platformKitePlayerDefaults.supportsPictureInPicture

private fun noDefaultStackDetail(): String {
    val reason = (platformKitePlayerDefaults.availability as? KitePlayerAvailability.Unavailable)?.reason
        ?: "the platform supplied no default backends"
    return "KitePlayer has no default stack here: $reason. Name both a media backend and an " +
        "output backend in PlayerConfig.backends to play anyway."
}
