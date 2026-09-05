@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetter
import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetterProvider

/**
 * What an application may tune about the libass module. Nothing here is needed for it to work:
 * adding the dependency installs the typesetter, and the engine routes every ASS and SSA track
 * through it. These knobs exist for the platforms whose libass cannot find fonts by itself.
 */
public object KiteLibass {

    /** The provider identifier the engine reports in `PlayerSnapshot.subtitleTypesetter`. */
    public const val PROVIDER_ID: String = "io.github.yuroyami.kiteplayer.libass"

    /**
     * Directories scanned for system font files, on Android and Linux only. Apple and Windows
     * builds of libass enumerate system fonts through CoreText and DirectWrite and never read this.
     * Change it before the first ASS track opens; a running typesetter keeps what it loaded.
     */
    public var fontDirectories: List<String> = defaultFontDirectories()

    /**
     * How many bytes of system font files one typesetter loads, sans-serif faces first. The
     * default fits the Latin sans family and one East Asian face on a stock Android system; raise
     * it for full coverage at the cost of memory, lower it on a constrained device.
     */
    public var systemFontBudgetBytes: Long = 24L * 1024 * 1024

    /** True where libass has no font provider of its own and [fontDirectories] are scanned. */
    public val needsSystemFontFiles: Boolean get() = platformNeedsSystemFontFiles

    /** libass' version word, `0x01704000` for 0.17.4, or 0 when this platform build has none. */
    public fun libraryVersion(): Int = LibassEngine.libraryVersion()

    /** Whether this platform build can create a typesetter at all. Loads the native library on the JVM. */
    public fun isAvailable(): Boolean = LibassEngine.libraryVersion() != 0
}

/** The discovery entry. Public with a no-argument constructor because ServiceLoader needs both. */
public class LibassTypesetterProvider : SubtitleTypesetterProvider {
    override val id: String = KiteLibass.PROVIDER_ID

    override fun create(): SubtitleTypesetter? = LibassTypesetter.createOrNull()
}

/** The platform's font directories, empty where libass finds fonts itself. */
internal expect fun defaultFontDirectories(): List<String>

/** True on Android and Linux, where this chain carries no font provider. */
internal expect val platformNeedsSystemFontFiles: Boolean
