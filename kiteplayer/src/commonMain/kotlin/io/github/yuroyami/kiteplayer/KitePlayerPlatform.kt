package io.github.yuroyami.kiteplayer

/** Whether KitePlayer's default backend and output stack can run in this process. */
public sealed interface KitePlayerAvailability {
    /** True only when [KitePlayer] can build the default player. */
    public val isAvailable: Boolean

    /** The platform has a real, usable default stack. */
    public data object Available : KitePlayerAvailability {
        public override val isAvailable: Boolean = true
    }

    /** This publication is an honest placeholder, or its runtime payload is absent. */
    public data class Unavailable(public val reason: String) : KitePlayerAvailability {
        init {
            require(reason.isNotBlank()) { "an unavailable KitePlayer platform needs a reason" }
        }

        public override val isAvailable: Boolean = false
    }
}

/**
 * The default KitePlayer stack for the current target.
 *
 * Deprecated in favour of names a reader finds under `KitePlayer`: `KitePlayer()` builds the
 * default player, and `KitePlayer.availability`, `KitePlayer.isAvailable` and
 * `KitePlayer.supportsPictureInPicture` answer the same questions as the members here.
 *
 * Android, iOS, macOS native and the desktop JVM provide real backends. Wasm becomes available
 * after its codec module is loaded. JavaScript retains an explicit unavailable facade. Linux and
 * Windows native have the FFmpeg backend but no audio output, so they answer unavailable, and a
 * caller there passes its own output backend to [KitePlayer.create]. Except on Linux and Windows
 * native, the standard runtime includes automatic HTTP transport without requiring a
 * factory-specific resolver setting.
 *
 * Custom backends remain independent of this facade: pass them directly to [KitePlayer.create].
 */
public object KitePlayerPlatform {
    /** A non-throwing explanation of whether the default stack can be constructed. */
    @Deprecated(
        "Use KitePlayer.availability.",
        ReplaceWith("KitePlayer.availability", "io.github.yuroyami.kiteplayer.availability"),
    )
    public val availability: KitePlayerAvailability
        get() = platformKitePlayerDefaults.availability

    /** Convenience form of [availability] for engine registries and feature pickers. */
    @Deprecated(
        "Use KitePlayer.isAvailable.",
        ReplaceWith("KitePlayer.isAvailable", "io.github.yuroyami.kiteplayer.isAvailable"),
    )
    public val isAvailable: Boolean
        get() = platformKitePlayerDefaults.availability.isAvailable

    /**
     * Whether this platform can put a player in a picture-in-picture window at all.
     *
     * iOS and macOS answer the system's own static, which needs nothing passed to it. Android cannot
     * answer properly here because the real question needs a context, so it stays a floor and
     * `KitePlayerPlatform.supportsPictureInPicture(context)` asks the package manager instead.
     * Everywhere else it is false.
     *
     * The host application still owns its Activity, its manifest and the viewer's per-app
     * permission, on both platforms.
     */
    @Deprecated(
        "Use KitePlayer.supportsPictureInPicture.",
        ReplaceWith("KitePlayer.supportsPictureInPicture", "io.github.yuroyami.kiteplayer.supportsPictureInPicture"),
    )
    public val supportsPictureInPicture: Boolean
        get() = platformKitePlayerDefaults.supportsPictureInPicture

    /**
     * Creates the default player, or returns null when [availability] is unavailable.
     *
     * [PlayerConfig.backends] is replaced with the platform defaults. Call [KitePlayer.create]
     * directly when supplying custom backends.
     */
    @Deprecated(
        "Use KitePlayer(config). It throws PlaybackException where this returns null, and it keeps " +
            "a backend that config.backends names. Check KitePlayer.isAvailable first where the " +
            "platform may have no default stack.",
        ReplaceWith("KitePlayer(config)", "io.github.yuroyami.kiteplayer.KitePlayer"),
    )
    public fun createOrNull(config: PlayerConfig = PlayerConfig()): KitePlayer? {
        val backends = platformKitePlayerDefaults.backendsOrNull() ?: return null
        return KitePlayer.create(config.copy(backends = backends))
    }

    internal fun backendsOrNull(): Backends? = platformKitePlayerDefaults.backendsOrNull()
}

internal interface KitePlayerPlatformDefaults {
    val availability: KitePlayerAvailability
    val supportsPictureInPicture: Boolean
    fun backendsOrNull(): Backends?
}

internal class UnavailableKitePlayerPlatformDefaults(
    reason: String,
) : KitePlayerPlatformDefaults {
    override val availability: KitePlayerAvailability = KitePlayerAvailability.Unavailable(reason)
    override val supportsPictureInPicture: Boolean = false
    override fun backendsOrNull(): Backends? = null
}

internal expect val platformKitePlayerDefaults: KitePlayerPlatformDefaults
