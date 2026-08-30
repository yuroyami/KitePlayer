package io.github.yuroyami.kiteplayer.internal

/**
 * Reads a LOCAL text file whole, or answers null when it cannot (S4.e: external subtitle files).
 *
 * Local means a filesystem path. Network fetching is parked with KPKMP 17.8, and the browser
 * targets have no filesystem, so their actuals answer null and the caller warns typed instead of
 * pretending.
 *
 * BYTES, not text. This used to decode as UTF-8 here, on the belief that "UTF-8 with an optional
 * BOM is what every subtitle file in the wild is". Windows-1256 Arabic and Windows-1251 Cyrillic
 * subtitles are ordinary and are neither, so the encoding is decided from the bytes by
 * [decodeSubtitleBytes] rather than assumed by whichever actual happened to read them.
 */
internal expect fun readExternalBytesOrNull(path: String): ByteArray?

/** One word for the support bundle's platform block (S4.e): jvm, android, native, js, wasm. */
internal expect val playerPlatformName: String
