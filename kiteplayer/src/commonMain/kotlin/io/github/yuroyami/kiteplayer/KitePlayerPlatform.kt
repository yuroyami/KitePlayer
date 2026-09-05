package io.github.yuroyami.kiteplayer

/** Whether KitePlayer's default backend and output stack can run in this process. */
public sealed interface KitePlayerAvailability {
    /** True only when [KitePlayerPlatform.createOrNull] can build the default player. */
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
 * Android, iOS and the desktop JVM provide real backends. Wasm becomes available after its codec
 * module is loaded; JavaScript retains an explicit unavailable facade. The standard runtime
 * includes automatic HTTP transport without requiring a factory-specific resolver setting.
 *
 * Custom backends remain independent of this facade: pass them directly to [KitePlayer.create].
 */
public object KitePlayerPlatform {
    /** A non-throwing explanation of whether the default stack can be constructed. */
    public val availability: KitePlayerAvailability
        get() = platformKitePlayerDefaults.availability

    /** Convenience form of [availability] for engine registries and feature pickers. */
    public val isAvailable: Boolean
        get() = availability.isAvailable

    /**
     * Whether a player can be built here at all, which is the floor for putting one in a
     * picture-in-picture window. It does NOT ask the device: on Android,
     * `KitePlayerPlatform.supportsPictureInPicture(context)` asks the package manager, and the
     * host application still owns its Activity, its manifest and the user's per-app permission.
     */
    public val supportsPictureInPicture: Boolean
        get() = platformKitePlayerDefaults.supportsPictureInPicture

    /**
     * Creates the default player, or returns null when [availability] is unavailable.
     *
     * [PlayerConfig.backends] is replaced with the platform defaults. Call [KitePlayer.create]
     * directly when supplying custom backends.
     */
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
