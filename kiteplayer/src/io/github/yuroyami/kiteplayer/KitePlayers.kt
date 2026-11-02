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
 * With the FFmpeg media backend, the default one or a `KiteFFmpegMediaBackend` the config names,
 * this also gives the platform's player views their default renderer, so `view.player = player` is
 * all a `KitePlayerView` needs. [KitePlayer.create] gives the views nothing: a view showing a player
 * it built needs a `rendererFactory` of its own, or a player built here first (#601).
 *
 * Android on `arm64-v8a`, `armeabi-v7a` and `x86_64`, iOS, macOS on Apple silicon, and the desktop
 * JVM on macOS on Apple silicon, Linux x64 and arm64 and Windows x64 have the default stack in a
 * correctly packaged app. The web has it once its codec module is loaded. Anywhere else, such as
 * an Intel Mac, Windows on Arm or an `x86` Android process, `KitePlayer.isAvailable` is false and
 * this throws, so check it first where that can happen, for example to fall back to another
 * engine.
 *
 * @throws PlaybackException with [PlaybackError.ConfigurationInvalid] when a backend is missing
 *         and this platform has no default for it. The detail says why.
 */
@Throws(PlaybackException::class)
public fun KitePlayer(config: PlayerConfig = PlayerConfig()): KitePlayer {
    val supplied = config.backends
    val player = if (supplied.backend != null && supplied.output != null) {
        KitePlayer.create(config)
    } else {
        val defaults = platformKitePlayerDefaults.backendsOrNull()
            ?: throw PlaybackException(PlaybackError.ConfigurationInvalid(noDefaultStackDetail()))
        KitePlayer.create(
            config.copy(
                backends = Backends(
                    backend = supplied.backend ?: defaults.backend,
                    output = supplied.output ?: defaults.output,
                ),
            ),
        )
    }
    // The default renderer shows the frames of the FFmpeg backend, the default one or one the app
    // built itself for its decoder options, so it comes with either (#601).
    val media = supplied.backend
    if (media == null || platformKitePlayerDefaults.viewsShowFramesOf(media)) platformKitePlayerDefaults.installViewDefaults()
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
