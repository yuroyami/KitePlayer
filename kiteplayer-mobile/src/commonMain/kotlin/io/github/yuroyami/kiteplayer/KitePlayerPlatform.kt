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
 * Android and iOS provide real backends. Desktop JVM, JavaScript and Wasm deliberately publish
 * unavailable placeholders until their media and output implementations land. That lets an
 * application keep one common dependency and one common integration without pretending those
 * targets can play media.
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
     * Whether the default presentation path is compatible with host-managed picture-in-picture.
     *
     * The host application still owns its Activity/controller and manifest configuration.
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
