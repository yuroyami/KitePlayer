package io.github.yuroyami.kiteplayer.subtitle

/**
 * One font file an application hands to the subtitle typesetter, by name and bytes.
 *
 * Only the typesetting engine reads these; the Kotlin dialogue tier uses the platform's own font
 * system. The bytes are shared, not copied, and read once when a session starts typesetting, so an
 * application may keep one instance for the life of the process.
 *
 * Where fonts come from otherwise: Apple and Windows builds of libass find system fonts on their
 * own. Android and Linux have no such provider, so those builds load a small set of the system's
 * sans-serif faces, the fonts a container attaches, and whatever is listed here.
 */
public class SubtitleFont(
    /** The file name libass matches against, for example `NotoSans-Regular.ttf`. */
    public val name: String,
    public val data: ByteArray,
) {
    init {
        require(name.isNotBlank()) { "a subtitle font needs a name" }
        require(data.isNotEmpty()) { "subtitle font $name carries no bytes" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is SubtitleFont && name == other.name && data.contentEquals(other.data))

    override fun hashCode(): Int = 31 * name.hashCode() + data.contentHashCode()

    override fun toString(): String = "SubtitleFont($name, ${data.size} bytes)"
}
