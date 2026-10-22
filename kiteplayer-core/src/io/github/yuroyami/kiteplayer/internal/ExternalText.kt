package io.github.yuroyami.kiteplayer.internal

/** What reading a local subtitle file found. */
internal sealed interface ExternalFile {
    class Read(val bytes: ByteArray) : ExternalFile

    /** The file holds more than the limit it was read with. Nothing past the limit was read. */
    data object TooLarge : ExternalFile

    data object Unreadable : ExternalFile
}

/**
 * Reads a LOCAL text file whole, for external subtitle files, but never more than [limit] bytes.
 *
 * Local means a filesystem path, and the browser targets have no filesystem, so their actuals
 * answer [ExternalFile.Unreadable] and the caller warns typed instead of pretending. The limit is
 * what stops a film picked by mistake from being read into memory whole (#243).
 *
 * BYTES, not text. This used to decode as UTF-8 here, on the belief that "UTF-8 with an optional
 * BOM is what every subtitle file in the wild is". Windows-1256 Arabic and Windows-1251 Cyrillic
 * subtitles are ordinary and are neither, so the encoding is decided from the bytes by
 * [decodeSubtitleBytes] rather than assumed by whichever actual happened to read them.
 */
internal expect fun readExternalFile(path: String, limit: Int): ExternalFile

/** One word for the support bundle's platform block: jvm, android, native, js, wasm. */
internal expect val playerPlatformName: String
